# Muevete BI ETL

Aplicación headless en Java 17 que automatiza el proceso diario de extracción
de comparendos y carga de información de recaudo y cartera Turbaco.

## Flujo automatizado

```
[18:00 Bogotá todos los días]
 1. Conecta VPN FortiClient (IFX - MUEVETE BI)
 2. PG Bogotá (136.20.0.52):
      - Lee incremental de rp_evidencias_validadas_view por id_evidencia
      - Acumula en bi_comparendos_maestro
 3. Desconecta VPN
 4. Oracle local (192.168.30.240):
      - Carga los comparendos en STG_COMPARENDOS_BI (staging con RUN_ID)
      - Ejecuta 3 consultas (recaudo interno + externo + cartera)
      - Los resultados se vuelcan a archivos binarios temporales
      - Limpia staging del runId
 5. Reconecta VPN
 6. PG Bogotá:
      - TRUNCATE + INSERT en RECAUDO_MULTAS_SAST_TURBACO
      - TRUNCATE + INSERT en CARTERA_MULTAS_SAST_TURBACO
      - Actualiza bi_control_proceso
 7. Desconecta VPN
 8. Borra archivos temporales
```

## Requisitos del servidor

- Windows + Java 17 (OpenJDK o Oracle)
- Maven 3.9+ para compilar
- FortiClient VPN (edición gratuita VPN-only) instalado y con el perfil
  **"IFX - MUEVETE BI"** guardado una sola vez desde la GUI
- Permisos de administrador (necesarios para levantar la VPN)
- Acceso de red a Oracle 192.168.30.240:1521 (LAN) y, a través de la VPN,
  a PostgreSQL 136.20.0.52:5432

## Instalación

### 1. Compilar
```bat
mvn clean package
```
Esto genera `target/muevete-bi-etl.jar` (fat-jar con todas las dependencias).

### 2. Crear tablas en Oracle (una sola vez)
Ejecutar con SQL Developer como usuario MUEVETE:
```
sql/ddl/oracle_staging.sql
```
(Si el usuario MUEVETE tiene `CREATE TABLE`, la app también lo hace automáticamente al primer arranque.)

### 3. Definir la master key de cifrado (una sola vez)
```bat
setx /M MUEVETE_BI_MASTER_KEY "una-frase-secreta-muy-larga"
```
Reiniciar la consola para que tome efecto.

### 4. Cifrar contraseñas
```bat
scripts\encrypt-password.bat "MueV2026&&VpnBI"
scripts\encrypt-password.bat "mutVBI2026$"
scripts\encrypt-password.bat "MU3V373"
```
Copiar cada valor resultante (`ENC(xxxxx)`) en el campo correspondiente
de `config/application.yml`.

### 5. Probar manualmente
```bat
scripts\run-once.bat
```
Debe conectarse a la VPN, ejecutar todo el flujo y dejar los logs en `logs/`.

### 6. Instalar como servicio Windows
1. Descargar [WinSW](https://github.com/winsw/winsw/releases) (WinSW.NET4.exe)
2. Copiarlo a `scripts/` con el nombre **muevete-bi-etl-service.exe**
3. Revisar `scripts/muevete-bi-etl-service.xml` y ajustar la variable
   `MUEVETE_BI_MASTER_KEY`
4. Ejecutar como administrador: `scripts\install-service.bat`

El servicio queda residente y dispara el ETL todos los días a las 18:00
(hora Bogotá) según el cron definido en `config/application.yml`.

## Estructura del proyecto

```
muevete-bi-etl/
├── pom.xml
├── README.md
├── config/
│   ├── application.yml       # toda la configuración
│   └── logback.xml
├── sql/
│   ├── recaudo_interno.sql   # consultas Oracle adaptadas
│   ├── recaudo_externo.sql
│   ├── cartera.sql
│   └── ddl/
│       ├── oracle_staging.sql
│       ├── pg_control.sql
│       └── pg_destinos.sql
├── scripts/
│   ├── run-once.bat
│   ├── encrypt-password.bat
│   ├── install-service.bat
│   └── muevete-bi-etl-service.xml
├── logs/                     # logs rotados diariamente (30 días)
└── src/main/java/com/muevete/bi/etl/
    ├── App.java              # main + Quartz scheduler
    ├── job/EtlJob.java       # orquestador del flujo
    ├── vpn/VpnManager.java   # conecta/desconecta FortiClient + healthcheck
    ├── db/{PostgresClient,OracleClient}.java
    ├── service/
    │   ├── ComparendosService.java   # incremental PG
    │   ├── OracleStagingService.java # staging Oracle
    │   ├── RecaudoService.java       # queries de recaudo
    │   ├── CarteraService.java       # query de cartera
    │   └── CargaDestinoService.java  # truncate+insert PG
    ├── config/AppConfig.java
    └── util/{CryptoUtil,SqlLoader,RowStore}.java
```

## Configuración clave (application.yml)

| Sección | Campo | Descripción |
|---|---|---|
| `scheduler` | `cron` | Expresión Quartz. Por defecto `0 0 18 * * ?` (18:00 diario) |
| `scheduler` | `runOnStartup` | Si `true`, ejecuta una vez al arrancar el servicio |
| `vpn` | `forticlientPath` | Ruta al `FortiSSLVPNclient.exe` |
| `vpn` | `profileName` | Nombre del perfil guardado en FortiClient |
| `vpn` | `healthcheckHost/Port` | Host+puerto a probar para confirmar que la VPN levantó |
| `vpn` | `retries` | Cantidad de intentos de conexión VPN |
| `oracle` | `stagingTable` | Tabla de staging (default `MUEVETE.STG_COMPARENDOS_BI`) |
| `oracle` | `batchSize` | Tamaño de batch al insertar al staging (default 5000) |
| `etl` | `insertBatchSize` | Tamaño batch al insertar en destinos PG |
| `etl` | `truncateBeforeInsert` | `true` = TRUNCATE+INSERT en destinos |

## Observabilidad

- **logs/muevete-bi-etl.log** — log técnico completo (rotación diaria, 30 días)
- **logs/audit.log** — log de auditoría con los hitos del ETL y los totales
- **public.bi_control_proceso** — última ejecución, estado, duración y totales

## Manejo de fallos

- **VPN no conecta:** reintenta `retries` veces con backoff, luego aborta.
- **Query Oracle falla:** se aborta y no se toca ninguna tabla destino.
- **Carga PG falla:** la transacción hace rollback completo; los destinos
  quedan como estaban antes del run.
- Cualquier error deja `bi_control_proceso.estado_ult_ejecucion = 'ERROR'`
  con el mensaje.

## Seguridad de contraseñas

Las contraseñas se guardan cifradas con Jasypt (`ENC(...)`). La clave maestra
se toma de la variable de entorno `MUEVETE_BI_MASTER_KEY`. Si no se define,
se usa una clave por defecto (sólo aceptable para pruebas, NO para producción).
