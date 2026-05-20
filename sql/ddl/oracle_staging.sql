-- =====================================================================
-- DDL Oracle: Tabla de staging para los numeros de comparendo
-- Ejecutar UNA SOLA VEZ con el usuario MUEVETE
-- =====================================================================

CREATE TABLE MUEVETE.STG_COMPARENDOS_BI (
    NRO_COMPARENDO VARCHAR2(50 CHAR) NOT NULL,
    RUN_ID         VARCHAR2(36 CHAR) NOT NULL,
    FECHA_CARGA    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE INDEX MUEVETE.IX_STG_COMP_BI_RUN ON MUEVETE.STG_COMPARENDOS_BI (RUN_ID, NRO_COMPARENDO);
CREATE INDEX MUEVETE.IX_STG_COMP_BI_NUM ON MUEVETE.STG_COMPARENDOS_BI (NRO_COMPARENDO);

-- Permisos (ajustar si MUEVETE no es el owner que ejecuta el ETL)
-- GRANT SELECT, INSERT, DELETE ON MUEVETE.STG_COMPARENDOS_BI TO MUEVETE;
