package dev.magicmq.rappu;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.*;

public abstract class AbstractDatabase<T> {

    protected HikariConfig config;
    protected HikariDataSource source;
    protected T using;
    protected Logger logger;
    protected int numOfThreads;
    protected long maxBlockTime;
    protected boolean debugLoggingEnabled;

    protected ThreadPoolExecutor asyncQueue;
    protected boolean shuttingDown;

    protected AbstractDatabase() {
        config = new HikariConfig();
        numOfThreads = 5;
        maxBlockTime = 15000L;
    }

    public AbstractDatabase<T> withConnectionInfo(String host, int port, String database) {
        return withConnectionInfo(host, port, database, true);
    }

    public AbstractDatabase<T> withConnectionInfo(String host, int port, String database, boolean useSSL) {
        config.setJdbcUrl(String.format(useSSL ? "jdbc:mysql://%s:%d/%s" : "jdbc:mysql://%s:%d/%s?useSSL=false",
                host,
                port,
                database));
        return this;
    }

    public AbstractDatabase<T> withUsername(String username) {
        config.setUsername(username);
        return this;
    }

    public AbstractDatabase<T> withPassword(String password) {
        config.setPassword(password);
        return this;
    }

    public AbstractDatabase<T> withDefaultProperties() {
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

    public AbstractDatabase<T> withDataSourceProperty(String property, Object value) {
        config.addDataSourceProperty(property, value);
        return this;
    }

    public AbstractDatabase<T> withPluginUsing(T plugin) {
        this.using = plugin;
        return this;
    }

    public AbstractDatabase<T> withNumOfThreads(int numOfThreads) {
        this.numOfThreads = numOfThreads;
        return this;
    }

    public AbstractDatabase<T> withMaxBlockTime(long maxBlockTime) {
        this.maxBlockTime = maxBlockTime;
        return this;
    }

    public AbstractDatabase<T> withDebugLogging() {
        this.debugLoggingEnabled = true;
        return this;
    }

    public abstract AbstractDatabase<T> open();

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

    public abstract void queryAsync(String sql, Object[] toSet, Callback<ResultSet> callback);

    public int update(String sql, Object[] toSet) throws SQLException {
        try (Connection connection = source.getConnection()) {
            debug("(Update) Successfully got a new connection from hikari: " + connection.toString() + ", catalog: " + connection.getCatalog());
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                debug("(Update) Successfully created a PreparedStatement with the following: " + sql);
                if (toSet != null) {
                    for (int i = 0; i < toSet.length; i++) {
                        statement.setObject(i + 1, toSet[i]);
                    }
                }
                debug("(Update) Successfully set objects. Executing the following: " + statement.toString().substring(statement.toString().indexOf('-') + 1));
                return statement.executeUpdate();
            }
        }
    }

    public abstract void updateAsync(String sql, Object[] toSet, Callback<Integer> callback);

    protected void debug(String message) {
        if (debugLoggingEnabled)
            logger.info(message);
    }
}
