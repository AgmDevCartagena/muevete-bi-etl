-- =====================================================================
-- DDL PostgreSQL: Tablas destino donde se carga la informacion de Oracle
-- Estas tablas las crea automaticamente la app al primer arranque.
-- Las columnas mantienen el nombre EXACTO de las consultas Oracle.
-- =====================================================================

CREATE TABLE IF NOT EXISTS public."RECAUDO_MULTAS_SAST_TURBACO" (
    "FUENTE"                     VARCHAR(50),
    "TIPO_CARTERA"               INTEGER,
    "FECHA_PAGO"                 DATE,
    "RECIBO"                     VARCHAR(50),
    "VALOR_RECIBO"               NUMERIC(18,2),
    "TIPO_DOCUMENTO"             VARCHAR(50),
    "IDENTIFICACION"             VARCHAR(50),
    "NOMBRE"                     VARCHAR(300),
    "VEHI_PLACA"                 VARCHAR(20),
    "COMP_NUMERO"                VARCHAR(50),
    "COMP_FECHA"                 DATE,
    "ANIO_COMPARENDO"            VARCHAR(4),
    "PRESCRIPCION"               VARCHAR(20),
    "TIPO_COMPARENDO"            VARCHAR(50),
    "CLASE_VEHICULO"             VARCHAR(100),
    "TIPO"                       VARCHAR(50),
    "SERVICIO_VEHICULO"          VARCHAR(100),
    "VALOR_PAGADO"               NUMERIC(18,2),
    "DISTRI_FECHA"               DATE,
    "RESOLUCION_MP"              VARCHAR(100),
    "CART_VALOR_INICIAL"         NUMERIC(18,2),
    "CONCEPTO"                   VARCHAR(100),
    "ESTADO_CARTERA"             VARCHAR(200),
    "CONCEPTO_PRINCIPAL"         VARCHAR(200),
    "GESTION"                    VARCHAR(50),
    "PORC_DESCUENTO"             VARCHAR(20),
    "DESCUENTO_CARTERA"          NUMERIC(18,2),
    "DESCUENTO_INTERESES"        NUMERIC(18,2),
    "CANT_DESCUENTO_CARTERA"     INTEGER,
    "CANT_DESCUENTO_INTERESES"   INTEGER,
    "SEGUI_RESOLUCION"           VARCHAR(100),
    "SEGUI_FECHA_RESOL"          DATE,
    "VALOR_INTERES"              NUMERIC(18,2)
);

CREATE TABLE IF NOT EXISTS public."CARTERA_MULTAS_SAST_TURBACO" (
    "COMP_NUMERO"                VARCHAR(20),
    "COMP_FECHA"                 TIMESTAMP,
    "TIPO_COMPARENDO"            VARCHAR(7),
    "CART_VALOR_INICIAL"         NUMERIC(20),
    "COMP_ESTADO"                VARCHAR(80),
    "COMP_CODIGO"                NUMERIC(20),
    "TIPO_INFRACTOR"             VARCHAR(80),
    "PER_TIPO_DOCUMENTO"         VARCHAR(80),
    "PER_NRO_IDENTIFICACION"     VARCHAR(20),
    "PER_NOMBRES"                VARCHAR(201),
    "PER_DIRECCION_INFRACTOR"    VARCHAR(80),
    "PER_EMAIL_INFRACTOR"        VARCHAR(50),
    "PER_TELEFONO_INFRACTOR"     VARCHAR(15),
    "VEHI_PLACA"                 VARCHAR(10),
    "INFR_CODIGO_VISISBLE"       VARCHAR(5),
    "PROCESO_ESTADO"             VARCHAR(100),
    "FECHA_ACTUACION"            TIMESTAMP,
    "RESOLUCION_ACTUACION"       VARCHAR(80),
    "FECHA_SANCION"              TIMESTAMP,
    "RESOLUCION_SANCION"         VARCHAR(80),
    "MP_FECHA"                   TIMESTAMP,
    "MP_RESOLUCION"              VARCHAR(20),
    "MP_NOTIF_FECHA"             TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_recaudo_compnum ON public."RECAUDO_MULTAS_SAST_TURBACO" ("COMP_NUMERO");
CREATE INDEX IF NOT EXISTS ix_cartera_compnum ON public."CARTERA_MULTAS_SAST_TURBACO" ("COMP_NUMERO");
