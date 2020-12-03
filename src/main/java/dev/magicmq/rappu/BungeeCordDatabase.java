package dev.magicmq.rappu;

import com.zaxxer.hikari.HikariDataSource;
import net.md_5.bungee.api.plugin.Plugin;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.*;

public class BungeeCordDatabase extends AbstractDatabase<Plugin> {

    public static BungeeCordDatabase newDatabase() {
        return new BungeeCordDatabase();
    }

    public BungeeCordDatabase open() {
        if (using == null)
            throw new IllegalStateException("Cannot open the Database without a plugin using! Did you call Database#withPluginUsing?");
        logger = LoggerFactory.getLogger(using.getDescription().getName() + " - Rappu");
        config.setPoolName(using.getDescription().getName().toLowerCase() + "-rappu-hikari");
        source = new HikariDataSource(config);
        debug("Successfully created a HikariDataSource with the following info: \n"
                + "Jdbc URL: " + config.getJdbcUrl() + "\n"
                + "Username: " + config.getUsername() + "\n"
                + "Password: " + config.getPassword() + "\n"
                + "Properties: " + config.getDataSourceProperties());
        asyncQueue = (ThreadPoolExecutor) Executors.newFixedThreadPool(numOfThreads);
        return this;
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
                        using.getProxy().getScheduler().schedule(using, task, 0L, TimeUnit.MILLISECONDS);
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
                logger.error("Error occurred on the following SQL statement: " + sql);
                e.printStackTrace();
            }
        });
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
                    using.getProxy().getScheduler().schedule(using, task, 0L, TimeUnit.MILLISECONDS);
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
                logger.error("Error occurred on the following SQL statement: " + sql);
                e.printStackTrace();
            }
        });
    }
}
