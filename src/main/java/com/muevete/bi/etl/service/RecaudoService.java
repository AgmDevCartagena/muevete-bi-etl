package com.muevete.bi.etl.service;

import com.muevete.bi.etl.config.AppConfig;
import com.muevete.bi.etl.db.OracleClient;
import com.muevete.bi.etl.util.RowStore;
import com.muevete.bi.etl.util.SqlLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;

/**
 * Ejecuta las dos consultas de recaudo (INTERNO y EXTERNO) en Oracle,
 * filtradas por el staging del runId, y escribe los resultados al RowStore.
 *
 * Las dos consultas devuelven las mismas 33 columnas en el mismo orden,
 * por lo que ambas escriben al mismo archivo (equivalente a UNION ALL).
 */
public class RecaudoService {

    private static final Logger log = LoggerFactory.getLogger(RecaudoService.class);
    public static final int NUM_COLUMNS = 33;

    private final AppConfig cfg;
    private final OracleClient oracle;

    public RecaudoService(AppConfig cfg, OracleClient oracle) {
        this.cfg = cfg;
        this.oracle = oracle;
    }

    public int extraer(String runId, File outputFile) throws Exception {
        int total = 0;
        try (RowStore store = new RowStore(outputFile)) {
            total += ejecutar(store, "recaudo_interno.sql", runId, "RECAUDO_INTERNO");
            total += ejecutar(store, "recaudo_externo.sql", runId, "RECAUDO_EXTERNO");
        }
        log.info("Recaudo extraido: {} filas totales -> {}", total, outputFile.getName());
        return total;
    }

    private int ejecutar(RowStore store, String sqlFile, String runId, String label) throws Exception {
        String sql = SqlLoader.load(sqlFile);
        int count = 0;
        long t0 = System.currentTimeMillis();
        try (Connection c = oracle.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setFetchSize(cfg.oracle.fetchSize);
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                if (cols != NUM_COLUMNS) {
                    throw new IllegalStateException("Se esperaban " + NUM_COLUMNS +
                            " columnas en " + sqlFile + " pero vinieron " + cols);
                }
                while (rs.next()) {
                    Object[] row = new Object[cols];
                    for (int i = 0; i < cols; i++) {
                        row[i] = rs.getObject(i + 1);
                    }
                    store.write(row);
                    count++;
                }
            }
        }
        log.info("  {} -> {} filas en {} ms", label, count, System.currentTimeMillis() - t0);
        return count;
    }
}
