package com.muevete.bi.etl.db;

import com.muevete.bi.etl.config.AppConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

public class PostgresClient implements AutoCloseable {

    private final HikariDataSource ds;

    public PostgresClient(AppConfig cfg) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.postgres.jdbcUrl());
        hc.setUsername(cfg.postgres.user);
        hc.setPassword(cfg.postgresPassword());
        hc.setMaximumPoolSize(4);
        hc.setMinimumIdle(1);
        hc.setConnectionTimeout(30_000);
        hc.setPoolName("pg-muevete");
        // El servidor tiene DateStyle en formato local (DMY); forzamos ISO
        // para que el driver JDBC pueda parsear timestamps correctamente.
        hc.setConnectionInitSql("SET DateStyle = 'ISO, MDY'");
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
