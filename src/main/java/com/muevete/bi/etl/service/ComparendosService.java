package com.muevete.bi.etl.service;

import com.muevete.bi.etl.config.AppConfig;
import com.muevete.bi.etl.db.PostgresClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Paso 1-2 del ETL:
 *  - Lee incremental (por id_evidencia) la vista rp_evidencias_validadas_view
 *  - Acumula en la tabla maestra bi_comparendos_maestro
 *  - Devuelve la lista COMPLETA de numeros de comparendo (todos los historicos)
 *    para alimentar el staging en Oracle.
 *
 * Tambien se encarga de crear las tablas de control y maestra si no existen.
 */
public class ComparendosService {

    private static final Logger log = LoggerFactory.getLogger(ComparendosService.class);

    // La vista rp_evidencias_validadas_view devuelve varias fechas como TEXT
    // en formato DMY ("dd/MM/yyyy HH:mm:ss"). Aceptamos tambien ISO por robustez.
    private static final DateTimeFormatter DMY_DATETIME = DateTimeFormatter.ofPattern("d/M/yyyy H:mm:ss");
    private static final DateTimeFormatter DMY_DATE     = DateTimeFormatter.ofPattern("d/M/yyyy");

    private final AppConfig cfg;
    private final PostgresClient pg;

    public ComparendosService(AppConfig cfg, PostgresClient pg) {
        this.cfg = cfg;
        this.pg = pg;
    }

    public void ensureSchema() throws SQLException {
        try (Connection c = pg.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS public.bi_control_proceso (
                    proceso              VARCHAR(100) PRIMARY KEY,
                    ultimo_id_evidencia  BIGINT       NOT NULL DEFAULT 0,
                    fecha_ult_ejecucion  TIMESTAMP,
                    estado_ult_ejecucion VARCHAR(20),
                    mensaje_ult          TEXT,
                    registros_pg         INTEGER,
                    registros_recaudo    INTEGER,
                    registros_cartera    INTEGER,
                    duracion_seg         INTEGER
                )
                """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS public.bi_comparendos_maestro (
                    id_evidencia                 BIGINT PRIMARY KEY,
                    organismo                    VARCHAR(200),
                    nombre_camara                VARCHAR(200),
                    placa                        VARCHAR(20),
                    fecha_infraccion             TIMESTAMP,
                    velocidad_limite             NUMERIC(10,2),
                    velocidad_reportada          NUMERIC(10,2),
                    porcentaje_ia                NUMERIC(10,2),
                    estado_general_prevalidacion VARCHAR(100),
                    esta_sincronizado_runt       BOOLEAN,
                    fecha_sync_runt              TIMESTAMP,
                    infraccion                   VARCHAR(50),
                    numero_comparendo            VARCHAR(50),
                    fase_actual                  VARCHAR(100),
                    resultado_validacion         VARCHAR(100),
                    motivo_rechazo               TEXT,
                    fecha_hora_validacion        TIMESTAMP,
                    usuario_validador            VARCHAR(100),
                    nombre_validador             VARCHAR(200),
                    apellidos_validador          VARCHAR(200),
                    fecha_validacion             TIMESTAMP,
                    fecha_carga_etl              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
            st.execute("CREATE INDEX IF NOT EXISTS ix_bi_maestro_numcomp ON public.bi_comparendos_maestro (numero_comparendo)");

            // Inicializar registro de control si no existe
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO public.bi_control_proceso (proceso, ultimo_id_evidencia) VALUES (?, 0) ON CONFLICT (proceso) DO NOTHING")) {
                ps.setString(1, cfg.etl.procesoNombre);
                ps.executeUpdate();
            }
        }
    }

    public long getUltimoIdEvidencia() throws SQLException {
        try (Connection c = pg.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ultimo_id_evidencia FROM public.bi_control_proceso WHERE proceso = ?")) {
            ps.setString(1, cfg.etl.procesoNombre);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /**
     * Lee de la vista rp_evidencias_validadas_view los registros cuyo id_evidencia > lastId,
     * los inserta en la tabla maestra (upsert por id_evidencia) y retorna cuantos ingresaron.
     */
    public int cargarIncrementalDesdeVista(long lastId) throws SQLException {
        // Procesamos TODOS los comparendos TURBACO de la vista, sin filtro por watermark.
        // Razon: id_evidencia no es monotonico en tiempo de validacion - un comparendo
        // puede tener id viejo pero validarse hoy y aparecer recien ahora en la vista.
        // El filtro id_evidencia > X (que teniamos antes) saltaba esos casos para siempre.
        // ON CONFLICT (id_evidencia) DO UPDATE convierte los repetidos en no-op barato.
        // Costo: ~20s extra por run leyendo 26k filas; garantia de correctitud total.
        // El parametro lastId queda solo para logging del rango procesado.
        String sel = """
            SELECT id_evidencia, organismo, nombre_camara, placa, fecha_infraccion,
                   velocidad_limite, velocidad_reportada, porcentaje_ia,
                   estado_general_prevalidacion, esta_sincronizado_runt, fecha_sync_runt,
                   infraccion, numero_comparendo, fase_actual, resultado_validacion,
                   motivo_rechazo, fecha_hora_validacion, usuario_validador,
                   nombre_validador, apellidos_validador, fecha_validacion
            FROM public.rp_evidencias_validadas_view
            WHERE UPPER(TRIM(organismo)) = 'TURBACO'
              AND numero_comparendo IS NOT NULL
              AND TRIM(numero_comparendo) <> ''
            ORDER BY id_evidencia
            """;

        String upsert = """
            INSERT INTO public.bi_comparendos_maestro (
                id_evidencia, organismo, nombre_camara, placa, fecha_infraccion,
                velocidad_limite, velocidad_reportada, porcentaje_ia,
                estado_general_prevalidacion, esta_sincronizado_runt, fecha_sync_runt,
                infraccion, numero_comparendo, fase_actual, resultado_validacion,
                motivo_rechazo, fecha_hora_validacion, usuario_validador,
                nombre_validador, apellidos_validador, fecha_validacion
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (id_evidencia) DO UPDATE SET
                organismo=EXCLUDED.organismo,
                nombre_camara=EXCLUDED.nombre_camara,
                placa=EXCLUDED.placa,
                fecha_infraccion=EXCLUDED.fecha_infraccion,
                velocidad_limite=EXCLUDED.velocidad_limite,
                velocidad_reportada=EXCLUDED.velocidad_reportada,
                porcentaje_ia=EXCLUDED.porcentaje_ia,
                estado_general_prevalidacion=EXCLUDED.estado_general_prevalidacion,
                esta_sincronizado_runt=EXCLUDED.esta_sincronizado_runt,
                fecha_sync_runt=EXCLUDED.fecha_sync_runt,
                infraccion=EXCLUDED.infraccion,
                numero_comparendo=EXCLUDED.numero_comparendo,
                fase_actual=EXCLUDED.fase_actual,
                resultado_validacion=EXCLUDED.resultado_validacion,
                motivo_rechazo=EXCLUDED.motivo_rechazo,
                fecha_hora_validacion=EXCLUDED.fecha_hora_validacion,
                usuario_validador=EXCLUDED.usuario_validador,
                nombre_validador=EXCLUDED.nombre_validador,
                apellidos_validador=EXCLUDED.apellidos_validador,
                fecha_validacion=EXCLUDED.fecha_validacion,
                fecha_carga_etl=CURRENT_TIMESTAMP
            """;

        int insertados = 0;
        long maxId = lastId;

        try (Connection cSel = pg.getConnection();
             Connection cIns = pg.getConnection();
             PreparedStatement psSel = cSel.prepareStatement(sel);
             PreparedStatement psIns = cIns.prepareStatement(upsert)) {

            cIns.setAutoCommit(false);
            // Postgres requiere autoCommit=false en cSel para que setFetchSize haga streaming
            // del cursor en lugar de cargar todo en memoria. Sin esto el driver bufferea las
            // 26k filas completas y consume mucha RAM.
            cSel.setAutoCommit(false);
            psSel.setFetchSize(5000);
            // Sin parametro: la query ya no tiene placeholder porque sacamos el filtro de watermark

            try (ResultSet rs = psSel.executeQuery()) {
                int batch = 0;
                while (rs.next()) {
                    long idEv = rs.getLong("id_evidencia");
                    psIns.setLong(1, idEv);
                    psIns.setString(2, rs.getString("organismo"));
                    psIns.setString(3, rs.getString("nombre_camara"));
                    psIns.setString(4, rs.getString("placa"));
                    psIns.setTimestamp(5, parseTextTimestamp(rs.getString("fecha_infraccion")));
                    psIns.setBigDecimal(6, rs.getBigDecimal("velocidad_limite"));
                    psIns.setBigDecimal(7, rs.getBigDecimal("velocidad_reportada"));
                    psIns.setBigDecimal(8, rs.getBigDecimal("porcentaje_ia"));
                    psIns.setString(9, rs.getString("estado_general_prevalidacion"));
                    Boolean sync = parseSiNo(rs.getString("esta_sincronizado_runt"));
                    if (sync == null) psIns.setNull(10, Types.BOOLEAN);
                    else psIns.setBoolean(10, sync);
                    psIns.setTimestamp(11, parseTextTimestamp(rs.getString("fecha_sync_runt")));
                    psIns.setString(12, rs.getString("infraccion"));
                    // Normalizar numero_comparendo: TRIM agresivo para sacar espacios, tabs,
                    // newlines o cualquier whitespace invisible que la vista origen pueda
                    // estar devolviendo. Si queda blank, insertamos NULL.
                    String numComp = rs.getString("numero_comparendo");
                    if (numComp != null) {
                        numComp = numComp.trim();
                        if (numComp.isEmpty()) numComp = null;
                    }
                    psIns.setString(13, numComp);
                    psIns.setString(14, rs.getString("fase_actual"));
                    psIns.setString(15, rs.getString("resultado_validacion"));
                    psIns.setString(16, rs.getString("motivo_rechazo"));
                    psIns.setTimestamp(17, parseTextTimestamp(rs.getString("fecha_hora_validacion")));
                    psIns.setString(18, rs.getString("usuario_validador"));
                    psIns.setString(19, rs.getString("nombre_validador"));
                    psIns.setString(20, rs.getString("apellidos_validador"));
                    psIns.setTimestamp(21, rs.getTimestamp("fecha_validacion"));
                    psIns.addBatch();

                    if (idEv > maxId) maxId = idEv;
                    insertados++;
                    if (++batch % cfg.etl.insertBatchSize == 0) {
                        psIns.executeBatch();
                        cIns.commit();
                    }
                }
                psIns.executeBatch();
                cIns.commit();
            }

            // Actualiza el control con el max id procesado (solo informativo - ya no se usa
            // como filtro porque siempre procesamos todo)
            if (maxId > lastId) {
                try (PreparedStatement psUp = cIns.prepareStatement(
                        "UPDATE public.bi_control_proceso SET ultimo_id_evidencia = ? WHERE proceso = ?")) {
                    psUp.setLong(1, maxId);
                    psUp.setString(2, cfg.etl.procesoNombre);
                    psUp.executeUpdate();
                    cIns.commit();
                }
            }
        }
        log.info("Procesamiento PG (full): {} registros (id_evidencia max actual = {})", insertados, maxId);
        return insertados;
    }

    /**
     * Devuelve TODOS los numeros de comparendo TURBACO desde la vista origen.
     *
     * IMPORTANTE: leemos DIRECTO de rp_evidencias_validadas_view, NO de bi_comparendos_maestro.
     * Razon: la vista origen devuelve VARIAS filas por id_evidencia (un evidencia puede tener
     * varios comparendos secuenciales: ej. id 79932 -> 13836000000045323384, ...385, ...386).
     * Como maestra tiene id_evidencia como PK, el upsert se queda con solo UNO de esos
     * numeros y los otros se pierden. Si los necesitamos todos para cartera/recaudo, hay que
     * tomarlos de la fuente. Maestra sigue existiendo para preservar columnas de evidencia
     * pero NO la usamos como pivote de numeros de comparendo.
     */
    public List<String> obtenerTodosLosComparendos() throws SQLException {
        List<String> lista = new ArrayList<>(50_000);
        try (Connection c = pg.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT DISTINCT TRIM(numero_comparendo) FROM public.rp_evidencias_validadas_view " +
                     "WHERE UPPER(TRIM(organismo)) = 'TURBACO' " +
                     "AND numero_comparendo IS NOT NULL " +
                     "AND TRIM(numero_comparendo) <> ''")) {
            ps.setFetchSize(5000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String v = rs.getString(1);
                    if (v != null && !v.isBlank()) lista.add(v);
                }
            }
        }
        log.info("Total comparendos desde vista origen: {}", lista.size());
        return lista;
    }

    private static Boolean parseSiNo(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim().toUpperCase();
        return switch (v) {
            case "SI", "S", "TRUE", "T", "1", "Y", "YES" -> Boolean.TRUE;
            case "NO", "N", "FALSE", "F", "0"           -> Boolean.FALSE;
            default -> null;
        };
    }

    private static Timestamp parseTextTimestamp(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        try { return Timestamp.valueOf(LocalDateTime.parse(t, DMY_DATETIME)); } catch (Exception ignored) {}
        try { return Timestamp.valueOf(LocalDate.parse(t, DMY_DATE).atStartOfDay()); } catch (Exception ignored) {}
        try { return Timestamp.valueOf(t); } catch (Exception ignored) {}
        log.warn("No se pudo parsear fecha '{}', se inserta NULL", s);
        return null;
    }

    public void actualizarResumen(String estado, String mensaje,
                                  int regPg, int regRecaudo, int regCartera, int duracionSeg) throws SQLException {
        try (Connection c = pg.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE public.bi_control_proceso SET fecha_ult_ejecucion=CURRENT_TIMESTAMP, " +
                     "estado_ult_ejecucion=?, mensaje_ult=?, registros_pg=?, registros_recaudo=?, " +
                     "registros_cartera=?, duracion_seg=? WHERE proceso=?")) {
            ps.setString(1, estado);
            ps.setString(2, mensaje);
            ps.setInt(3, regPg);
            ps.setInt(4, regRecaudo);
            ps.setInt(5, regCartera);
            ps.setInt(6, duracionSeg);
            ps.setString(7, cfg.etl.procesoNombre);
            ps.executeUpdate();
        }
    }
}
