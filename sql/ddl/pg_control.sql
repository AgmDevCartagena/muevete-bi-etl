-- =====================================================================
-- DDL PostgreSQL: Tablas de control y maestra de comparendos
-- Estas tablas las crea automaticamente la app al primer arranque,
-- pero se incluyen aqui por si se necesita crearlas manualmente.
-- =====================================================================

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
);

CREATE TABLE IF NOT EXISTS public.bi_comparendos_maestro (
    id_evidencia                   BIGINT PRIMARY KEY,
    organismo                      VARCHAR(200),
    nombre_camara                  VARCHAR(200),
    placa                          VARCHAR(20),
    fecha_infraccion               TIMESTAMP,
    velocidad_limite               NUMERIC(10,2),
    velocidad_reportada            NUMERIC(10,2),
    porcentaje_ia                  NUMERIC(10,2),
    estado_general_prevalidacion   VARCHAR(100),
    esta_sincronizado_runt         BOOLEAN,
    fecha_sync_runt                TIMESTAMP,
    infraccion                     VARCHAR(50),
    numero_comparendo              VARCHAR(50),
    fase_actual                    VARCHAR(100),
    resultado_validacion           VARCHAR(100),
    motivo_rechazo                 TEXT,
    fecha_hora_validacion          TIMESTAMP,
    usuario_validador              VARCHAR(100),
    nombre_validador               VARCHAR(200),
    apellidos_validador            VARCHAR(200),
    fecha_validacion               TIMESTAMP,
    fecha_carga_etl                TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_bi_maestro_numcomp ON public.bi_comparendos_maestro (numero_comparendo);
