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
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cartera Turbaco extraction.
 *
 * En lugar de una unica query con 4 LEFT JOINs (que tardaba 15+ min y mataba la VPN),
 * partimos en 5 queries simples y hacemos el join en memoria. Cada query individual
 * es predecible (Oracle elige el plan trivialmente con indices simples) y el join
 * Java sobre 4700 filas es instantaneo.
 *
 * Queries:
 *   1. base   -> staging x VIEW_CONTRA_COMPA_IMPUESTOS, sin LEFT JOINs
 *   2. proc   -> CONTRA_PROC_PASO_DESCRIPCION (tabla pequena, full scan barato)
 *   3. seg1   -> CONTRA_COMPARENDO_SEGUIMIENTO para FECHA_ACTUACION/RESOLUCION_ACTUACION
 *   4. seg2   -> CONTRA_COMPARENDO_SEGUIMIENTO para FECHA_SANCION/RESOLUCION_SANCION
 *   5. mp     -> CARTE_MANDAMIENTO_PAGO para MP_*
 */
public class CarteraService {

    private static final Logger log = LoggerFactory.getLogger(CarteraService.class);
    public static final int NUM_COLUMNS = 23;

    private final AppConfig cfg;
    private final OracleClient oracle;

    public CarteraService(AppConfig cfg, OracleClient oracle) {
        this.cfg = cfg;
        this.oracle = oracle;
    }

    public int extraer(String runId, File outputFile) throws Exception {
        long t0 = System.currentTimeMillis();
        int count = 0;

        try (Connection c = oracle.getConnection();
             RowStore store = new RowStore(outputFile)) {

            // ------------------------------------------------------------
            // Query 1: BASE (staging x IM, sin LEFT JOINs)
            // ------------------------------------------------------------
            long t = System.currentTimeMillis();
            // Usar List en vez de Map<COMP_CODIGO, BaseRow>: si la vista devuelve mas de una
            // fila por mismo COMP_CODIGO (caso de comparendo con multiples impuestos), un Map
            // las colapsa a una sola y perdemos data. List preserva el comportamiento del
            // LEFT JOIN original.
            List<BaseRow> baseRows = new ArrayList<>();
            String sqlBase = SqlLoader.load("cartera_base.sql");
            try (PreparedStatement ps = c.prepareStatement(sqlBase)) {
                ps.setFetchSize(cfg.oracle.fetchSize);
                ps.setQueryTimeout(300);
                ps.setString(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        BaseRow b = new BaseRow();
                        b.compNumero            = rs.getString("COMP_NUMERO");
                        b.compFecha             = rs.getTimestamp("COMP_FECHA");
                        b.tipoComparendo        = rs.getString("TIPO_COMPARENDO");
                        b.cartValorInicial      = rs.getBigDecimal("CART_VALOR_INICIAL");
                        b.compEstado            = rs.getString("COMP_ESTADO");
                        b.compCodigo            = rs.getLong("COMP_CODIGO");
                        b.tipoInfractor         = rs.getString("TIPO_INFRACTOR");
                        b.perTipoDocumento      = rs.getString("PER_TIPO_DOCUMENTO");
                        b.perNroIdentificacion  = rs.getString("PER_NRO_IDENTIFICACION");
                        b.perNombres            = rs.getString("PER_NOMBRES");
                        b.perDireccionInfractor = rs.getString("PER_DIRECCION_INFRACTOR");
                        b.perEmailInfractor     = rs.getString("PER_EMAIL_INFRACTOR");
                        b.perTelefonoInfractor  = rs.getString("PER_TELEFONO_INFRACTOR");
                        b.vehiPlaca             = rs.getString("VEHI_PLACA");
                        b.infrCodigoVisisble    = rs.getString("INFR_CODIGO_VISISBLE");
                        // Oracle NUMBER -> BigDecimal por defecto. Convertimos via Number para
                        // evitar ClassCastException, manejando null explicitamente.
                        Object pasoObj = rs.getObject("COMP_PASO_PROCESO_ACTUAL");
                        b.compPasoProcesoActual = (pasoObj != null) ? ((Number) pasoObj).intValue() : null;
                        Object cartObj = rs.getObject("CART_CODIGO");
                        b.cartCodigo = (cartObj != null) ? ((Number) cartObj).longValue() : null;
                        baseRows.add(b);
                    }
                }
            }
            log.info("Cartera fase 1 (base): {} filas en {} ms",
                    baseRows.size(), System.currentTimeMillis() - t);

            // ------------------------------------------------------------
            // Query 2: PROC_PASO_DESCRIPCION (tabla pequena, full scan)
            // ------------------------------------------------------------
            t = System.currentTimeMillis();
            Map<Integer, String> procDesc = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT PROC_PASO_CODIGO, PROC_PASO_DESCRIPCION FROM CONTRA_PROC_PASO_DESCRIPCION")) {
                ps.setFetchSize(cfg.oracle.fetchSize);
                ps.setQueryTimeout(60);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        procDesc.put(rs.getInt(1), rs.getString(2));
                    }
                }
            }
            log.info("Cartera fase 2 (proc): {} filas en {} ms",
                    procDesc.size(), System.currentTimeMillis() - t);

            // ------------------------------------------------------------
            // Query 3: SEGUIMIENTO actuacion (filtro por staging del run)
            // ------------------------------------------------------------
            t = System.currentTimeMillis();
            Map<SegKey, SegVal> segActuacion = new HashMap<>();
            String sqlSegAct = SqlLoader.load("cartera_seg_actuacion.sql");
            try (PreparedStatement ps = c.prepareStatement(sqlSegAct)) {
                ps.setFetchSize(cfg.oracle.fetchSize);
                ps.setQueryTimeout(120);
                ps.setString(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        SegKey k = new SegKey(rs.getLong("COMP_CODIGO"), rs.getInt("SEGUI_CODIGO_PASO"));
                        SegVal v = new SegVal();
                        v.fecha      = rs.getTimestamp("SEGUI_FECHA_RESOL");
                        v.resolucion = rs.getString("SEGUI_RESOLUCION");
                        segActuacion.put(k, v);
                    }
                }
            }
            log.info("Cartera fase 3 (seg actuacion): {} filas en {} ms",
                    segActuacion.size(), System.currentTimeMillis() - t);

            // ------------------------------------------------------------
            // Query 4: SEGUIMIENTO sancion (paso=5, estado=1)
            // ------------------------------------------------------------
            t = System.currentTimeMillis();
            Map<Long, SegVal> segSancion = new HashMap<>();
            String sqlSegSan = SqlLoader.load("cartera_seg_sancion.sql");
            try (PreparedStatement ps = c.prepareStatement(sqlSegSan)) {
                ps.setFetchSize(cfg.oracle.fetchSize);
                ps.setQueryTimeout(120);
                ps.setString(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        SegVal v = new SegVal();
                        v.fecha      = rs.getTimestamp("SEGUI_FECHA_RESOL");
                        v.resolucion = rs.getString("SEGUI_RESOLUCION");
                        segSancion.put(rs.getLong("COMP_CODIGO"), v);
                    }
                }
            }
            log.info("Cartera fase 4 (seg sancion): {} filas en {} ms",
                    segSancion.size(), System.currentTimeMillis() - t);

            // ------------------------------------------------------------
            // Query 5: MANDAMIENTO_PAGO (estado != 3, por CART_CODIGO)
            // ------------------------------------------------------------
            t = System.currentTimeMillis();
            Map<Long, MpVal> mpByCart = new HashMap<>();
            String sqlMp = SqlLoader.load("cartera_mp.sql");
            try (PreparedStatement ps = c.prepareStatement(sqlMp)) {
                ps.setFetchSize(cfg.oracle.fetchSize);
                ps.setQueryTimeout(120);
                ps.setString(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        MpVal v = new MpVal();
                        v.mpFecha      = rs.getTimestamp("MP_FECHA");
                        v.mpResolucion = rs.getString("MP_RESOLUCION");
                        v.notifFecha   = rs.getTimestamp("NOTIF_FECHA");
                        mpByCart.put(rs.getLong("CART_CODIGO"), v);
                    }
                }
            }
            log.info("Cartera fase 5 (mp): {} filas en {} ms",
                    mpByCart.size(), System.currentTimeMillis() - t);

            // ------------------------------------------------------------
            // Phase 6: ensamblar resultado en memoria y escribir al store
            // ------------------------------------------------------------
            t = System.currentTimeMillis();
            for (BaseRow b : baseRows) {
                String procPasoDesc = (b.compPasoProcesoActual != null)
                        ? procDesc.get(b.compPasoProcesoActual) : null;
                SegVal sa = (b.compPasoProcesoActual != null)
                        ? segActuacion.get(new SegKey(b.compCodigo, b.compPasoProcesoActual)) : null;
                SegVal ss = segSancion.get(b.compCodigo);
                MpVal  mp = (b.cartCodigo != null) ? mpByCart.get(b.cartCodigo) : null;

                Object[] row = new Object[NUM_COLUMNS];
                row[0]  = b.compNumero;
                row[1]  = b.compFecha;
                row[2]  = b.tipoComparendo;
                row[3]  = b.cartValorInicial;
                row[4]  = b.compEstado;
                row[5]  = b.compCodigo;
                row[6]  = b.tipoInfractor;
                row[7]  = b.perTipoDocumento;
                row[8]  = b.perNroIdentificacion;
                row[9]  = b.perNombres;
                row[10] = b.perDireccionInfractor;
                row[11] = b.perEmailInfractor;
                row[12] = b.perTelefonoInfractor;
                row[13] = b.vehiPlaca;
                row[14] = b.infrCodigoVisisble;
                row[15] = procPasoDesc;
                row[16] = (sa != null) ? sa.fecha      : null;
                row[17] = (sa != null) ? sa.resolucion : null;
                row[18] = (ss != null) ? ss.fecha      : null;
                row[19] = (ss != null) ? ss.resolucion : null;
                row[20] = (mp != null) ? mp.mpFecha      : null;
                row[21] = (mp != null) ? mp.mpResolucion : null;
                row[22] = (mp != null) ? mp.notifFecha   : null;
                store.write(row);
                count++;
            }
            log.info("Cartera fase 6 (ensamble): {} filas en {} ms",
                    count, System.currentTimeMillis() - t);
        }

        log.info("Cartera extraida: {} filas en {} ms -> {}",
                count, System.currentTimeMillis() - t0, outputFile.getName());
        return count;
    }

    // ---------- DTOs internos ----------

    private static class BaseRow {
        String compNumero;
        Timestamp compFecha;
        String tipoComparendo;
        java.math.BigDecimal cartValorInicial;
        String compEstado;
        Long compCodigo;
        String tipoInfractor;
        String perTipoDocumento;
        String perNroIdentificacion;
        String perNombres;
        String perDireccionInfractor;
        String perEmailInfractor;
        String perTelefonoInfractor;
        String vehiPlaca;
        String infrCodigoVisisble;
        Integer compPasoProcesoActual;
        Long cartCodigo;
    }

    private static final class SegKey {
        final long compCodigo;
        final int paso;
        SegKey(long compCodigo, int paso) {
            this.compCodigo = compCodigo;
            this.paso = paso;
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SegKey)) return false;
            SegKey k = (SegKey) o;
            return compCodigo == k.compCodigo && paso == k.paso;
        }
        @Override public int hashCode() {
            return Long.hashCode(compCodigo) * 31 + paso;
        }
    }

    private static class SegVal {
        Timestamp fecha;
        String resolucion;
    }

    private static class MpVal {
        Timestamp mpFecha;
        String mpResolucion;
        Timestamp notifFecha;
    }
}
