package dev.magicmq.rappu;

import java.sql.SQLException;

public interface Callback<T> {

    void callback(T result) throws SQLException;

}
