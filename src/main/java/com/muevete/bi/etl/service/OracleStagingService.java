package com.muevete.bi.etl.service;

import com.muevete.bi.etl.config.AppConfig;
import com.muevete.bi.etl.db.OracleClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * Gestiona la tabla de staging en Oracle: crea (si no existe), carga por lotes
 * los numeros de comparendo del run actual, y limpia despues.
 */
public class OracleStagingService {

    private static final Logger log = LoggerFactory.getLogger(OracleStagingService.class);
    private final AppConfig cfg;
    private final OracleClient oracle;

    public OracleStagingService(AppConfig cfg, OracleClient oracle) {
        this.cfg = cfg;
        this.oracle = oracle;
    }

    /** Verifica que la tabla exista; si no, la crea. */
    public void ensureStaging() throws SQLException {
        String check = "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME = 'STG_COMPARENDOS_BI'";
        try (Connection c = oracle.getConnection();
             PreparedStatement ps = c.prepareStatement(check);
             var rs = ps.executeQuery()) {
            rs.next();
            if (rs.getInt(1) == 0) {
                log.info("Creando tabla de staging {}", cfg.oracle.stagingTable);
                try (var st = c.createStatement()) {
                    st.execute("CREATE TABLE " + cfg.oracle.stagingTable +
                            " (NRO_COMPARENDO VARCHAR2(50 CHAR) NOT NULL, " +
                            "  RUN_ID VARCHAR2(36 CHAR) NOT NULL, " +
                            "  FECHA_CARGA TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL)");
                    st.execute("CREATE INDEX IX_STG_COMP_BI_RUN ON " +
                            cfg.oracle.stagingTable + " (RUN_ID, NRO_COMPARENDO)");
                    st.execute("CREATE INDEX IX_STG_COMP_BI_NUM ON " +
                            cfg.oracle.stagingTable + " (NRO_COMPARENDO)");
                }
            }
        }
    }

    /** Carga los comparendos en el staging en lotes. */
    public int cargarComparendos(String runId, List<String> comparendos) throws SQLException {
        // Limpieza defensiva
        limpiarRun(runId);

        int total = 0;
        String sql = "INSERT INTO " + cfg.oracle.stagingTable +
                " (NRO_COMPARENDO, RUN_ID) VALUES (?, ?)";
        try (Connection c = oracle.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            c.setAutoCommit(false);
            int batch = 0;
            for (String num : comparendos) {
                if (num == null || num.isBlank()) continue;
                ps.setString(1, num);
                ps.setString(2, runId);
                ps.addBatch();
                total++;
                if (++batch % cfg.oracle.batchSize == 0) {
                    ps.executeBatch();
                    c.commit();
                }
            }
            ps.executeBatch();
            c.commit();
        }
        log.info("Staging Oracle cargado: {} comparendos (runId={})", total, runId);
        return total;
    }

    public void limpiarRun(String runId) throws SQLException {
        try (Connection c = oracle.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM " + cfg.oracle.stagingTable + " WHERE RUN_ID = ?")) {
            ps.setString(1, runId);
            int n = ps.executeUpdate();
            if (n > 0) log.debug("Staging limpiado: {} filas para runId={}", n, runId);
        }
    }
}
