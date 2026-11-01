package com.bjarne.genericgraphql.inspector;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Reicht Connections durch, die SQL an den laufenden Mitschnitt melden. */
public class TracingDataSource extends DelegatingDataSource {

    public TracingDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return JdbcTracing.wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return JdbcTracing.wrap(super.getConnection(username, password));
    }
}
