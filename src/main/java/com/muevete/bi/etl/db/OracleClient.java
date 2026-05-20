package com.muevete.bi.etl.db;

import com.muevete.bi.etl.config.AppConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

public class OracleClient implements AutoCloseable {

    private final HikariDataSource ds;

    public OracleClient(AppConfig cfg) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.oracle.jdbcUrl());
        hc.setUsername(cfg.oracle.user);
        hc.setPassword(cfg.oraclePassword());
        hc.setMaximumPoolSize(4);
        hc.setMinimumIdle(1);
        hc.setConnectionTimeout(60_000);
        hc.setPoolName("ora-muevete");
        hc.addDataSourceProperty("oracle.jdbc.implicitStatementCacheSize", "20");
        this.ds = new HikariDataSource(hc);
    }

    public Connection getConnection() throws SQLException {
        return ds.getConnection();
    }

    @Override
    public void close() {
        if (ds != null && !ds.isClosed()) ds.close();
    }
}
