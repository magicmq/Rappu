package dev.magicmq.rappu;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.*;

public class Database {

    private HikariConfig config;
    private HikariDataSource source;
    private JavaPlugin using;
    private Logger logger;
    private int numOfThreads;
    private long maxBlockTime;
    private boolean debugLoggingEnabled;

    private ThreadPoolExecutor asyncQueue;
    private boolean shuttingDown;

    private Database() {
        config = new HikariConfig();
        numOfThreads = 5;
        maxBlockTime = 15000L;
    }

    public static Database newDatabase() {
        return new Database();
    }

    public Database withConnectionInfo(String host, int port, String database) {
        return withConnectionInfo(host, port, database, true);
    }

    public Database withConnectionInfo(String host, int port, String database, boolean useSSL) {
        config.setJdbcUrl(String.format(useSSL ? "jdbc:mysql://%s:%d/%s" : "jdbc:mysql://%s:%d/%s?useSSL=false",
                host,
                port,
                database));
        return this;
    }

    public Database withUsername(String username) {
        config.setUsername(username);
        return this;
    }

    public Database withPassword(String password) {
        config.setPassword(password);
        return this;
    }

    public Database withDefaultProperties() {
        config.addDataSourceProperty("cachePrepStmts", true);
        config.addDataSourceProperty("prepStmtCacheSize", 250);
        config.addDataSourceProperty("prepStmtCacheSqlLimit", 2048);
        config.addDataSourceProperty("useServerPrepStmts", true);
        config.addDataSourceProperty("useLocalSessionState", true);
        config.addDataSourceProperty("rewriteBatchedStatements", true);
        config.addDataSourceProperty("cacheResultSetMetadata", true);
        config.addDataSourceProperty("cacheServerConfiguration", true);
        config.addDataSourceProperty("elideSetAutoCommit", true);
        config.addDataSourceProperty("maintainTimeStats", false);
        return this;
    }

    public Database withDataSourceProperty(String property, Object value) {
        config.addDataSourceProperty(property, value);
        return this;
    }

    public Database withPluginUsing(JavaPlugin plugin) {
        this.using = plugin;
        return this;
    }

    public Database withNumOfThreads(int numOfThreads) {
        this.numOfThreads = numOfThreads;
        return this;
    }

    public Database withMaxBlockTime(long maxBlockTime) {
        this.maxBlockTime = maxBlockTime;
        return this;
    }

    public Database withDebugLogging() {
        this.debugLoggingEnabled = true;
        return this;
    }

    public Database open() {
        logger = LoggerFactory.getLogger(using.getName() + " - Rappu");
        config.setPoolName(using.getName().toLowerCase() + "-rappu-hikari");
        source = new HikariDataSource(config);
        debug("Successfully created a HikariDataSource with the following info: \n"
                    + "Jdbc URL: " + config.getJdbcUrl() + "\n"
                    + "Username: " + config.getUsername() + "\n"
                    + "Password: " + config.getPassword() + "\n"
                    + "Properties: " + config.getDataSourceProperties());
        asyncQueue = (ThreadPoolExecutor) Executors.newFixedThreadPool(numOfThreads);
        return this;
    }

    public HikariDataSource getDataSource() {
        if (source == null)
            throw new IllegalArgumentException("The data source has not been instantiated! The database must be opened first.");
        return source;
    }

    public void close() {
        shuttingDown = true;
        asyncQueue.shutdown();
        try {
            asyncQueue.awaitTermination(maxBlockTime, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            logger.error("Thread was interrupted while waiting for SQL statements to finish executing!");
            e.printStackTrace();
        }
        source.close();
    }

    public boolean isClosed() {
        return source.isClosed();
    }

    public int createTableFromFile(String file, Class<?> mainClass) throws IOException, SQLException {
        URL resource = Resources.getResource(mainClass, "/" + file);
        String databaseStructure = Resources.toString(resource, Charsets.UTF_8);
        debug("(Create Table) Successfully loaded an SQL statement from the " + file + " file.");
        return createTableFromStatement(databaseStructure);
    }

    public int createTableFromStatement(String sql) throws SQLException {
        try (Connection connection = source.getConnection()) {
            debug("(Create Table) Successfully got a new connection from hikari: " + connection.toString() + ", catalog: " + connection.getCatalog());
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                debug("(Create Table) Successfully created a PreparedStatement. Executing the following: " + sql);
                return statement.executeUpdate();
            }
        }
    }

    public ResultSet query(String sql, Object[] toSet) throws SQLException {
        try (Connection connection = source.getConnection()) {
            debug("(Query) Successfully got a new connection from hikari: " + connection.toString() + ", catalog: " + connection.getCatalog());
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                debug("(Query) Successfully created a PreparedStatement with the following: " + sql);
                if (toSet != null) {
                    for (int i = 0; i < toSet.length; i++) {
                        statement.setObject(i + 1, toSet[i]);
                    }
                }
                debug("(Query) Successfully set objects. Executing the following: " + statement.toString().substring(statement.toString().indexOf('-') + 1));
                ResultSet result = statement.executeQuery();
                return result;
            }
        }
    }

    public void queryAsync(String sql, Object[] toSet, Callback<ResultSet> callback) {
        asyncQueue.execute(() -> {
            try (Connection connection = source.getConnection()) {
                debug("(Query) Successfully got a new connection from hikari: " + connection.toString() + ", catalog: " + connection.getCatalog());
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    debug("(Query) Successfully created a PreparedStatement with the following: " + sql);
                    if (toSet != null) {
                        for (int i = 0; i < toSet.length; i++) {
                            statement.setObject(i + 1, toSet[i]);
                        }
                    }
                    debug("(Query) Successfully set objects. Executing the following: " + statement.toString().substring(statement.toString().indexOf('-') + 1));
                    ResultSet result = statement.executeQuery();
                    if (!shuttingDown) {
                        RunnableFuture<Void> task = new FutureTask<>(() -> {
                            try {
                                callback.callback(result);
                            } catch (SQLException e) {
                                logger.error("There was an error while reading the query result!");
                                e.printStackTrace();
                            }
                            return null;
                        });
                        Bukkit.getScheduler().runTask(using, task);
                        try {
                            task.get();
                        } catch (InterruptedException | ExecutionException e) {
                            logger.error("There was an error while waiting for the query callback to complete!");
                            e.printStackTrace();
                        }
                        result.close();
                    } else {
                        try {
                            logger.warn("SQL statement executed asynchronously during shutdown, so the synchronous callback was not run. This occurred during a query, so data will not be loaded.");
                            result.close();
                        } catch (SQLException ignored) {}
                    }
                } catch (SQLException e) {
                    logger.error("There was an error when querying the database!");
                    e.printStackTrace();
                }
            } catch (SQLException e) {
                logger.error("There was an error when querying the database!");
                e.printStackTrace();
            }
        });
    }

    public int update(String sql, Object[] toSet) throws SQLException {
        try (Connection connection = source.getConnection()) {
            debug("(Update) Successfully got a new connection from hikari: " + connection.toString() + ", catalog: " + connection.getCatalog());
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                debug("(Update) Successfully created a PreparedStatement with the following: " + sql);
                for (int i = 0; i < toSet.length; i++) {
                    statement.setObject(i + 1, toSet[i]);
                }
                debug("(Update) Successfully set objects. Executing the following: " + statement.toString().substring(statement.toString().indexOf('-') + 1));
                return statement.executeUpdate();
            }
        }
    }

    public void updateAsync(String sql, Object[] toSet, Callback<Integer> callback) {
        asyncQueue.execute(() -> {
            try {
                int toReturn = update(sql, toSet);
                if (!shuttingDown) {
                    RunnableFuture<Void> task = new FutureTask<>(() -> {
                        try {
                            callback.callback(toReturn);
                        } catch (SQLException ignored) {}
                        return null;
                    });
                    Bukkit.getScheduler().runTask(using, task);
                    try {
                        task.get();
                    } catch (InterruptedException | ExecutionException e) {
                        logger.error("There was an error while waiting for the update callback to complete!");
                        e.printStackTrace();
                    }
                } else {
                    logger.warn("SQL statement executed asynchronously during shutdown, so the synchronous callback was not run. This occurred during an update, so no data loss has occurred.");
                }
            } catch (SQLException e) {
                logger.error("There was an error when updating the database!");
                e.printStackTrace();
            }
        });
    }

    private void debug(String message) {
        if (debugLoggingEnabled)
            logger.info(message);
    }
}
