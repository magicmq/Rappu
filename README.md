[![Generic badge](https://img.shields.io/badge/version-1.7-C.svg)](https://repo.magicmq.dev/repository/archetypes/)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/f730c7825efa43bdae2326d87da0f920)](https://www.codacy.com/manual/magicmq/Rappu?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=magicmq/Rappu&amp;utm_campaign=Badge_Grade)
[![Apache license](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

# Rappu

Rappu is a lightweight wrapper for [HikariCP](https://github.com/brettwooldridge/HikariCP), designed to be used with Bukkit/Spigot plugins. Rappu greatly simplfiies the usage of MySQL in your plugins by removing a large amount of boilerplate code (such as creating Connections, PreparedStatements, handling exceptions, and running queries/updates asynchronously with a synchronous callback). Rappu was designed and written with ease of use and flexbility in mind, so it caters to both beginners looking to branch out into database storage as well as experienced evelopers looking for a simpler and cleaner way to work with MySQL. 

## Features

### Creation and management of a HikariConfig and HikariDataSource internally
Rappu handles all interaction with hikari internally, so working with MySQL is greatly simplified. Only a few methods need to be called to open up a connection pool. Included is a `Database#withDefaultProperties` method, so that all the recommended hikari properties can be automatically applied. No need to remember them every time you need to work with hikari! 

### Internal handling of boilerplate code
Rappu takes care of creating connections, PreparedStatements, exception handling, and querying/updating asynchronous code for you, so that your own code is as clean as possible. Rappu allows you to take code like this:
``` java
public void insert(final String uuid) {
    BukkitRunnable r = new BukkitRunnable() {
        @Override
        public void run() {
            PreparedStatement preparedStatement = null;
            ResultSet resultSet = null;
            try {
                resultSet = connection.createStatement().executeQuery("SELECT * FROM players WHERE uuid= '" + uuid + "';");
                if (!resultSet.next()) {
                    preparedStatement = connection.prepareStatement("INSERT INTO players (uuid, kills, deaths, xp, level, lastseen) VALUES(?, ?, ?, ?, ?, ?);");
                    preparedStatement.setString(1, uuid);
                    preparedStatement.setLong(2, 0L);
                    preparedStatement.setLong(3, 0L);
                    preparedStatement.setLong(4, 0L);
                    preparedStatement.setLong(5, 0L);
                    preparedStatement.setTimestamp(6, new Timestamp(new Date().getTime()));
                    preparedStatement.executeUpdate();
                    if (debug_database) 
                        plugin.textUtils.debug("[Database] Inserting default values for UUID: " + uuid);
                }
            } catch (SQLException exception) {
                plugin.textUtils.exception(exception.getStackTrace(), exception.getMessage());
            } finally {
                if (resultSet != null)
                    try {
                        resultSet.close();
                    } catch (SQLException exception) {
                        plugin.textUtils.exception(exception.getStackTrace(), exception.getMessage());
                    }
                if (preparedStatement != null)
                    try {
                        preparedStatement.close();
                    } catch (SQLException exception) {
                        plugin.textUtils.exception(exception.getStackTrace(), exception.getMessage());
                    }
            }
        }
    };
    r.runTaskAsynchronously(plugin);
}
```
and shrink it to something like this:
``` java
public void insert(final String uuid) {
    String sql = "SELECT * FROM players WHERE uuid = ?;";
    database.queryAsync(sql, new Object[]{uuid}, resultSet -> {
        if (!resultSet.next()) {
            String insertSQL = "INSERT INTO PLAYERS ";
            insertSQL += "(uuid, kills, deaths, xp, level, lastseen) ";
            insertSQL += "VALUES(?, ?, ?, ?, ?, ?);";
            Object[] toSet = new Object[]{uuid, 0L, 0L, 0L, 0L, new Timestamp(new Date().getTime())};
            database.updateAsync(insertSQL, toSet, integer -> {});
            if (debug_database) 
                plugin.textUtils.debug("[Database] Inserting default values for UUID: " + uuid);
        }
    });
}
```

### Asynchronous Queries/Updates with Synchronous Callbacks
Rappu includes support for querying and updating asynchronously. A callback is provided for asynchronous queries/updates called synchronously such that results of queries/updates can be dealt with in a thread-safe manner.

### Usage of a ThreadPoolExecutor for Asynchronous Execution
Rappu utilizes Java's [ThreadPoolExecutor](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ThreadPoolExecutor.html) class, which works as an executor service to provide multiple threads to run submitted tasks asynchronously. Using a thread pool to execute SQL statements asynchronously provides many benefits, including:
- Much less overhead than when utilizing Bukkit's scheduling system to schedule/run asynchronous tasks. This confers a significant performance benefit.
- Improved performance when executing large numbers of SQL statements simultaneously due to reduced per-task invocation overhead
- Cleaner and safer handling of reloads, server shutdowns. Should the server shutdown or reload when SQL statements are pending/undergoing execution, Rappu will pause shutdown and wait until all pending/current SQL statements have finished execution. This protects against data loss and rollbacks.
- No hang on an unresponsive database. If the remote database becomes unresponsive for whatever reason, thread(s) will wait until the database becomes responsive again in the background without disturbing the server or other pending SQL statements.

### Simple but Feature-Rich API
Rappu was designed with simplicity in mind, and only takes a few method calls to work. Rappu is no less robust, however, and it contains several features to make almost anything possible. Check out the wiki or Javadocs for information on the API.

## Using Rappu as a Dependency

### Maven
Add the following repository:
``` maven
<repositories>
    <repository>
        <id>magicmq-repo</id>
        <url>https://repo.magicmq.dev/repository/maven-snapshots/</url>
    </repository>
</repositories>
```
Then, add the following dependency:
``` maven
<dependency>
    <groupId>dev.magicmq</groupId>
    <artifactId>rappu</artifactId>
    <version>{VERSION}</version>
</dependency>
```
Replace `{VERSION}` with the version shown above.

### Gradle
Add the following repository:
``` groovy
repositories {
    ...
    magicmq-repo { url 'https://repo.magicmq.dev/repository/maven-snapshots/' }
}
```
Then, add the following dependency:
``` groovy
dependencies {
        implementation 'dev.magicmq.rappu:{VERSION}'
}
```
Replace `{VERSION}` with the version shown above.

## Issues/Suggestions
Do you have any issues or suggestions? [Submit an issue report.](https://github.com/magicmq/Rappu/issues/new)


