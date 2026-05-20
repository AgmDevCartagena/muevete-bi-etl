package com.muevete.bi.etl.job;

import com.muevete.bi.etl.config.AppConfig;
import com.muevete.bi.etl.db.OracleClient;
import com.muevete.bi.etl.db.PostgresClient;
import com.muevete.bi.etl.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.UUID;

/**
 * Orquestador del proceso ETL completo. Es lo que se ejecuta en cada disparo
 * del scheduler (o manualmente con --run-once).
 *
 * Flujo:
 *   1. Abrir PG, crear tablas si no existen
 *   2. Incremental desde rp_evidencias_validadas_view -> bi_comparendos_maestro
 *   3. Obtener lista total de comparendos
 *   4. Abrir Oracle, crear staging si no existe
 *   5. Cargar comparendos en staging
 *   6. Extraer recaudo (interno + externo) a archivo temporal
 *   7. Extraer cartera a archivo temporal
 *   8. Limpiar staging runId
 *   9. Cerrar Oracle
 *  10. Abrir PG, TRUNCATE+INSERT en tablas destino
 *  11. Actualizar tabla de control
 *  12. Borrar archivos temporales
 */
public class EtlJob {

    private static final Logger log = LoggerFactory.getLogger(EtlJob.class);
    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    private final AppConfig cfg;

    public EtlJob(AppConfig cfg) {
        this.cfg = cfg;
    }

    public void run() throws Exception {
        String runId = UUID.randomUUID().toString();
        long tStart = System.currentTimeMillis();

        File tmpDir = new File("logs/tmp");
        if (!tmpDir.exists()) tmpDir.mkdirs();
        File recaudoFile = new File(tmpDir, "recaudo-" + runId + ".bin");
        File carteraFile = new File(tmpDir, "cartera-" + runId + ".bin");

        audit.info("===== INICIO RUN {} =====", runId);
        int regPg = 0, regRecaudo = 0, regCartera = 0;
        String estado = "OK";
        String mensaje = "";

        try {
            //-------- FASE 1: PostgreSQL incremental --------
            long lastId;
            List<String> comparendos;
            try (PostgresClient pg = new PostgresClient(cfg)) {
                ComparendosService svc = new ComparendosService(cfg, pg);
                svc.ensureSchema();
                lastId = svc.getUltimoIdEvidencia();
                audit.info("Ultimo id_evidencia procesado: {}", lastId);
                regPg = svc.cargarIncrementalDesdeVista(lastId);
                audit.info("Nuevos registros PG incorporados: {}", regPg);
                comparendos = svc.obtenerTodosLosComparendos();
                audit.info("Total comparendos a consultar en Oracle: {}", comparendos.size());
            }

            //-------- FASE 2: Oracle (staging + queries) --------
            try (OracleClient oracle = new OracleClient(cfg)) {
                OracleStagingService stg = new OracleStagingService(cfg, oracle);
                stg.ensureStaging();
                stg.cargarComparendos(runId, comparendos);

                RecaudoService recaudoSvc = new RecaudoService(cfg, oracle);
                regRecaudo = recaudoSvc.extraer(runId, recaudoFile);
                audit.info("Recaudo extraido de Oracle: {} filas", regRecaudo);

                CarteraService carteraSvc = new CarteraService(cfg, oracle);
                regCartera = carteraSvc.extraer(runId, carteraFile);
                audit.info("Cartera extraida de Oracle: {} filas", regCartera);

                stg.limpiarRun(runId);
            }

            //-------- FASE 3: PostgreSQL carga destino --------
            try (PostgresClient pg = new PostgresClient(cfg)) {
                CargaDestinoService cd = new CargaDestinoService(cfg, pg);
                cd.ensureDestinos();
                int insR = cd.cargarRecaudo(recaudoFile);
                int insC = cd.cargarCartera(carteraFile);
                audit.info("Insertados en destino: recaudo={}, cartera={}", insR, insC);

                int dur = (int) ((System.currentTimeMillis() - tStart) / 1000);
                new ComparendosService(cfg, pg)
                        .actualizarResumen("OK", "Ejecucion exitosa", regPg, insR, insC, dur);
            }

            audit.info("===== FIN RUN {} OK duracion={}s =====",
                    runId, (System.currentTimeMillis() - tStart) / 1000);

        } catch (Exception e) {
            estado = "ERROR";
            mensaje = e.getMessage();
            log.error("Fallo del ETL: {}", mensaje, e);
            audit.error("===== FIN RUN {} ERROR: {} =====", runId, mensaje);

            // Intentar registrar el fallo en la tabla de control
            try {
                try (PostgresClient pg = new PostgresClient(cfg)) {
                    int dur = (int) ((System.currentTimeMillis() - tStart) / 1000);
                    new ComparendosService(cfg, pg)
                            .actualizarResumen(estado, safeMsg(mensaje), regPg, regRecaudo, regCartera, dur);
                }
            } catch (Exception e2) {
                log.warn("No se pudo actualizar tabla de control: {}", e2.getMessage());
            }
            throw e;
        } finally {
            deleteQuietly(recaudoFile);
            deleteQuietly(carteraFile);
        }
    }

    private String safeMsg(String s) {
        if (s == null) return "";
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }

    private void deleteQuietly(File f) {
        try { if (f != null && f.exists()) f.delete(); } catch (Exception ignored) {}
    }
}
