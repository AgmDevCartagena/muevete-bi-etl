# Muevete BI ETL — Documentación Funcional

Descripción de qué hace el sistema, cómo lo hace, y qué esperar de él día a día.

---

## 1. Propósito

El proceso ETL **Muevete BI Turbaco** integra datos de comparendos y recaudo del municipio de Turbaco entre dos sistemas:

- **Origen de comparendos:** PostgreSQL operacional en Bogotá (red interna de Intraway).
- **Motor analítico:** Oracle local del municipio, que aplica reglas de negocio (recaudo interno, externo, cartera).
- **Destino BI:** vuelve a PostgreSQL Bogotá donde otras herramientas de Business Intelligence consultan los resultados.

El objetivo es disponer cada día, después de las 18:00 hora Colombia, de tres vistas actualizadas con la operación del día:

- Recaudo interno (pagos procesados dentro del municipio).
- Recaudo externo (pagos procesados por terceros).
- Cartera (saldos pendientes por cobrar).

---

## 2. Cadena de valor (qué recibe y qué entrega)

### Entrada

| Fuente | Objeto | Qué contiene |
|---|---|---|
| PostgreSQL Bogotá | `rp_evidencias_validadas_view` | Vista con todos los comparendos validados del día (evidencia de infracción). |

### Salida

| Destino | Objeto | Qué contiene |
|---|---|---|
| PostgreSQL Bogotá | `bi_comparendos_maestro` | Acumulado histórico incremental de todos los comparendos procesados. |
| PostgreSQL Bogotá | `RECAUDO_MULTAS_SAST_TURBACO` | Resultado de la consulta de recaudo interno + externo (completo, no incremental). |
| PostgreSQL Bogotá | `CARTERA_MULTAS_SAST_TURBACO` | Resultado de la consulta de cartera (completo, no incremental). |
| PostgreSQL Bogotá | `bi_control_proceso` | Una fila por cada ejecución, con inicio, fin, estado, totales, mensaje de error. |

---

## 3. Flujo operativo diario

### 3.1 Cronología (hora Colombia)

```
17:50  →  Tarea Windows "MueveteBI-VPN-AutoConnect"
          Conecta la VPN FortiClient al túnel de Intraway.
          Duración típica: 15-30 segundos.

18:00  →  Servicio "MueveteBIETL" dispara Quartz.
          Arranca EtlJob.run().
          Duración típica: 1-3 minutos (depende del volumen diario).

~18:03 →  Tablas destino actualizadas en PostgreSQL Bogotá.
          bi_control_proceso refleja estado OK.
```

### 3.2 Los pasos del ETL

Cuando `EtlJob.run()` arranca, ejecuta esta secuencia:

1. **Inicia control**: registra inicio en `bi_control_proceso`.
2. **Conecta a PostgreSQL Bogotá** (via VPN, ya conectada desde 17:50).
3. **Ingesta evidencias**: lee TODOS los registros TURBACO de `rp_evidencias_validadas_view` (sin filtro de watermark) y los upsertea en `bi_comparendos_maestro`. La vista origen puede devolver varios `numero_comparendo` por evidencia; el upsert por `id_evidencia` se queda con uno, pero el siguiente paso lee del origen así no se pierde data.
4. **Obtiene lista completa de comparendos**: SELECT DISTINCT de `numero_comparendo` directamente desde `rp_evidencias_validadas_view` (NO desde maestra). Esto garantiza que se incluyan TODOS los comparendos por evidencia.
5. **Conecta a Oracle local**.
6. **Carga staging Oracle**: vuelca la lista de comparendos a `MUEVETE.STG_COMPARENDOS_BI` con un `RUN_ID` único.
7. **Ejecuta recaudo interno** (`sql/recaudo_interno.sql`), filtrado por `RUN_ID`.
8. **Ejecuta recaudo externo** (`sql/recaudo_externo.sql`), filtrado por `RUN_ID`.
9. **Extrae cartera** — pipeline de 5 sub-queries pequeñas:
   - `cartera_base.sql` — staging × VIEW_CONTRA_COMPA_IMPUESTOS (SAST), sin LEFT JOINs
   - `CONTRA_PROC_PASO_DESCRIPCION` — tabla pequeña, full scan
   - `cartera_seg_actuacion.sql` — seguimientos para fecha/resolucion actuación
   - `cartera_seg_sancion.sql` — seguimientos paso=5 estado=1
   - `cartera_mp.sql` — mandamiento de pago
   - **Ensamble en memoria Java**: une todo en una sola lista de filas finales
10. **Vuelca resultados a PostgreSQL** (TRUNCATE + INSERT) en `RECAUDO_MULTAS_SAST_TURBACO` y `CARTERA_MULTAS_SAST_TURBACO`.
11. **Limpia staging** del `RUN_ID` actual en Oracle.
12. **Cierra control**: actualiza `bi_control_proceso` con `OK`, totales y duración.

> **Nota:** anteriormente la query de cartera era un único SELECT con 4 LEFT JOINs que tardaba 15+ minutos y rompía la VPN por inactividad. Se refactorizó al pipeline de 5 sub-queries con ensamble en memoria — ahora tarda <1 minuto. Ver sección 17 de la documentación técnica para detalles.

---

## 4. Componentes del sistema

### 4.1 Servicio `MueveteBIETL`

- **Qué es:** proceso Java que corre indefinidamente en el servidor, wrappeado por WinSW como servicio de Windows.
- **Función:** mantener vivo el scheduler Quartz. Quartz dispara `EtlJob` según el cron configurado.
- **Arranque:** automático al boot del servidor. El scheduler se inicializa unos segundos después y queda esperando el próximo disparo.
- **Configuración:** `config\application.yml` → sección `scheduler.cron` controla el horario.

### 4.2 Tarea programada `MueveteBI-VPN-AutoConnect`

- **Qué es:** tarea de Windows Task Scheduler que ejecuta un script PowerShell.
- **Función:** asegurar que la VPN FortiClient esté conectada 10 minutos antes del ETL.
- **Configuración requerida:** flag `/IT` (interactive task) — la tarea corre dentro de la sesión interactiva del usuario `Administrador`, no en una sesión batch separada. Sin esto, `SendKeys` no llega a ninguna ventana.
- **Cómo funciona (script `vpn-connect.ps1`):**
  1. Healthcheck TCP a `136.20.0.52:5432`. Si ya responde (VPN arriba), exit OK.
  2. **Reset agresivo de FortiClient**: mata UI + detiene servicios Forti + mata daemons residuales + reinicia servicios + lanza UI fresh. Esto es ~30s pero evita el caso "FortiClient colgado, ni manualmente abre".
  3. Espera 20s para que la UI Electron termine de inicializarse.
  4. Encuentra la ventana de FortiClient via Win32 `EnumWindows`.
  5. La trae al foreground usando el truco "Alt-tap antes de SetForegroundWindow" (workaround del bloqueo de foreground en sesiones spawneadas por Task Scheduler).
  6. Simula `TAB × 6 + ENTER` que activa el botón "Conectar" del perfil guardado.
  7. Durante 30s polea en paralelo el modal "Server Certificate Warning" (si aparece, le da ENTER) y el healthcheck VPN. Si conecta, exit OK.
  8. Si no conectó en 30s, reintenta el paso 6 hasta 3 veces (cubre la flakiness del cold-start UI).
- **Log:** `logs\vpn-connect.log`.

### 4.3 FortiClient

- **Daemons** (servicios Windows, siempre corriendo):
  - `FortiSSLVPNdaemon` — establece el túnel SSL.
  - `FortiVPN` — controlador de VPN.
  - `FortiSettings` — gestor de config.
  - `FortiTray` — icono de la bandeja del sistema.
- **UI (`FortiClient.exe`):** aplicación Electron. Normalmente minimizada a la bandeja; la UI solo existe cuando el usuario o el script la abre.

---

## 5. Scheduler y horario

El scheduler Quartz se configura en `config\application.yml`:

```yaml
scheduler:
  cron: "0 0 18 * * ?"          # segundos minutos horas día-mes mes día-semana
  timezone: "America/Bogota"
  runOnStartup: false
```

- **Por defecto:** todos los días a las 18:00:00 hora Bogotá.
- **Para cambiar el horario:** editar `cron` y reiniciar el servicio.
- **Para forzar una corrida al arrancar** (útil después de un despliegue): poner `runOnStartup: true` y reiniciar. Volver a `false` después para no dispararlo en cada reinicio.

---

## 6. Cifrado de contraseñas

Las contraseñas en `application.yml` nunca están en claro. Se guardan como `ENC(...)` usando Jasypt `PBEWithMD5AndDES`, con una **master key** inyectada por variable de entorno `MUEVETE_BI_MASTER_KEY`.

- **Cifrar** (genera el valor para poner en YAML):
  ```cmd
  scripts\encrypt-password.bat "miPasswordEnClaro"
  ```
- **Descifrar** (validar que quedó bien cifrado):
  ```cmd
  scripts\decrypt-password.bat "ENC(valorCifrado)"
  ```

La master key no se guarda en el proyecto — solo en la variable de entorno del servidor. Si se pierde, hay que regenerar todos los `ENC()`.

---

## 7. Observabilidad

### 7.1 Logs

| Archivo | Contenido | Rotación |
|---|---|---|
| `logs\muevete-bi-etl.log` | Log técnico detallado (todas las trazas) | Diaria, 30 días |
| `logs\audit.log` | Hitos del ETL y totales por ejecución | Diaria, 30 días |
| `logs\vpn-connect.log` | Log del script de VPN | Apendido (no rota) |

### 7.2 Tabla de control

`public.bi_control_proceso` — una fila por ejecución:

| Campo | Descripción |
|---|---|
| `proceso_nombre` | Siempre `ETL_MUEVETE_BI_TURBACO` |
| `inicio` / `fin` | Timestamps |
| `duracion_segundos` | Tiempo total |
| `estado_ult_ejecucion` | `OK`, `ERROR`, `EN_CURSO` |
| `total_comparendos` | Registros leídos de PostgreSQL |
| `total_recaudo_interno` | Filas en `RECAUDO_MULTAS_SAST_TURBACO` |
| `total_cartera` | Filas en `CARTERA_MULTAS_SAST_TURBACO` |
| `mensaje_ult_ejecucion` | Error o `OK` |

Consulta rápida del último estado:

```sql
SELECT estado_ult_ejecucion, inicio, fin, duracion_segundos, total_comparendos
FROM public.bi_control_proceso
ORDER BY inicio DESC
LIMIT 5;
```

### 7.3 Consultas útiles de verificación

```sql
-- ¿Cuándo se actualizaron las tablas destino?
SELECT 'recaudo' AS t, COUNT(*), MAX(fecha_actualizacion) FROM public.recaudo_multas_sast_turbaco
UNION ALL
SELECT 'cartera' AS t, COUNT(*), MAX(fecha_actualizacion) FROM public.cartera_multas_sast_turbaco;

-- ¿Cuántos comparendos llevan acumulados?
SELECT COUNT(*), MAX(id_evidencia) FROM public.bi_comparendos_maestro;

-- ¿Cuántas ejecuciones han fallado últimamente?
SELECT estado_ult_ejecucion, COUNT(*)
FROM public.bi_control_proceso
WHERE inicio > now() - interval '7 days'
GROUP BY estado_ult_ejecucion;
```

---

## 8. Manejo de errores

### 8.1 Transaccionalidad

- **Lectura PostgreSQL:** no transaccional (solo SELECT).
- **Staging Oracle:** inserta con batch. Si falla a mitad, el run termina con ERROR y la staging queda con residuos; el siguiente run limpia por `RUN_ID`.
- **Carga final a PostgreSQL:** `TRUNCATE + INSERT` dentro de una transacción. Si falla el INSERT, el ROLLBACK restaura el estado anterior — **las tablas destino nunca quedan parciales**.

### 8.2 Qué pasa si VPN no conecta

- La tarea de las 17:50 loguea `"ERROR: VPN no respondio tras 60s"` en `vpn-connect.log`.
- A las 18:00 el ETL intenta conectar a PostgreSQL `136.20.0.52` y falla con timeout.
- `bi_control_proceso` marca la ejecución como `ERROR`.
- **No hay reintento automático del ETL al día siguiente** — hay que disparar manualmente (ver sección 10).

### 8.3 Qué pasa si una query Oracle falla

- El ETL aborta inmediatamente.
- Las tablas destino de PostgreSQL **no se tocan** (quedan con los datos del día anterior).
- `bi_control_proceso` marca `ERROR` con el mensaje.

### 8.4 Qué pasa si la carga final a PostgreSQL falla

- ROLLBACK. Las tablas destino quedan como estaban antes del run.
- `bi_control_proceso` marca `ERROR`.

---

## 9. Supuestos operativos

Para que el sistema funcione automáticamente, deben cumplirse estas precondiciones:

1. **El servidor está encendido** a las 17:50.
2. **El usuario `Administrador` tiene sesión `Active` o `Active locked`** (NO `Disc` desconectada). Si te conectás por RDP y necesitás irte, usá **`Win+L` para bloquear**, NO cierres con la X del cliente RDP — eso pone la sesión en estado `Disc` y la tarea programada no corre.
3. **La tarea `MueveteBI-VPN-AutoConnect` está configurada con `/IT`** (interactive task). Verificable con `Get-ScheduledTask -TaskName "MueveteBI-VPN-AutoConnect" | Select Principal` — `LogonType` debe ser `Interactive`.
4. **FortiClient VPN tiene configurado** el perfil `IFX - MUEVETE BI` con "Guardar Contraseña" marcado.
5. **El servicio `MueveteBIETL` está en estado `Running`**.
6. **Los servicios Forti** (`FortiSSLVPNdaemon`, `FortiVPN`, `FortiSettings`, `FortiTray`) están con startup `Automatic`.
7. **Oracle (192.168.30.240:1521) y PostgreSQL (136.20.0.52:5432 vía VPN)** están disponibles.

Si cualquiera de estas falla, revisar sección 15 de la Documentación Técnica.

---

## 10. Operaciones manuales comunes

### 10.1 Ejecutar el ETL bajo demanda (fuera del horario)

⚠️ **Mantener VPN viva durante el run**: abrir DOS ventanas de PowerShell.

**Ventana 1 — Keepalive VPN** (deja corriendo hasta que termine el ETL):
```powershell
while ($true) {
  Test-NetConnection -ComputerName 136.20.0.52 -Port 5432 -InformationLevel Quiet | Out-Null
  "$(Get-Date -Format HH:mm:ss) ping ok"
  Start-Sleep -Seconds 20
}
```

**Ventana 2 — Disparar el ETL**:
```cmd
cd /d C:\muevete-bi-etl
scripts\run-once.bat
```

Sin el keepalive, FortiClient puede tirar el túnel por inactividad mientras Oracle procesa, y el ETL fallaría al volver a PG.

Para el run automático de las 18:00 esto no es necesario porque las queries optimizadas tardan <1 min total.

### 10.2 Forzar conexión VPN sin esperar a las 17:50

```cmd
schtasks /Run /TN "MueveteBI-VPN-AutoConnect"
```

### 10.3 Detener el ETL si está corriendo

```cmd
sc stop MueveteBIETL
```

Esto mata la JVM. Si el ETL estaba a mitad de un run, la transacción PostgreSQL final hace rollback.

### 10.4 Cambiar el horario del ETL

1. Detener servicio: `sc stop MueveteBIETL`.
2. Editar `config\application.yml`, campo `scheduler.cron`.
3. Arrancar servicio: `sc start MueveteBIETL`.
4. Verificar en el log la línea `"Scheduler iniciado. Proximo disparo: ..."`.

### 10.5 Cambiar contraseña de PostgreSQL u Oracle

1. Cifrar la nueva contraseña:
   ```cmd
   scripts\encrypt-password.bat "nuevaPassword"
   ```
2. Pegar el `ENC(...)` en `config\application.yml` en el campo correspondiente.
3. Reiniciar servicio:
   ```cmd
   sc stop MueveteBIETL && sc start MueveteBIETL
   ```

### 10.6 Ver estado actual del sistema

```powershell
# Servicio
Get-Service MueveteBIETL | Format-Table Name, Status

# Tarea programada (debe decir LogonType: Interactive)
Get-ScheduledTask -TaskName "MueveteBI-VPN-AutoConnect" | Select-Object -ExpandProperty Principal | Format-List

# Sesión activa (debe decir Administrador Active)
query session

# VPN activa
Test-NetConnection -ComputerName 136.20.0.52 -Port 5432
```

En PostgreSQL Bogotá:
```sql
SELECT proceso, ultimo_id_evidencia, fecha_ult_ejecucion, estado_ult_ejecucion,
       registros_pg, registros_recaudo, registros_cartera, duracion_seg
FROM public.bi_control_proceso
WHERE proceso = 'ETL_MUEVETE_BI_TURBACO';
```

### 10.7 Verificar comparendos faltantes en cartera

Si te reportan que un comparendo específico debería estar en cartera y no aparece, usá este flujo:

**En PostgreSQL** — verificar si está en la vista origen:
```sql
SELECT id_evidencia, organismo, numero_comparendo
FROM rp_evidencias_validadas_view
WHERE numero_comparendo IN ('NUMERO_COMPARENDO');
```

Si no aparece → no es problema del ETL, el dato no existe en la fuente.

**Si sí aparece**, verificar en Oracle:
```sql
SELECT COMP_NUMERO, TIPO_COMPARENDO, COMP_ESTADO
FROM MUEVETE.VIEW_CONTRA_COMPA_IMPUESTOS
WHERE COMP_NUMERO IN ('NUMERO_COMPARENDO');
```

3 escenarios:
| TIPO_COMPARENDO | Significado |
|---|---|
| `SAST` | Debería estar en cartera. Si no está después del próximo run, revisar el log del ETL. |
| Otro (`SEMA`, etc.) | Excluido a propósito por el filtro `WHERE IM.TIPO_COMPARENDO = 'SAST'` en `cartera_base.sql`. |
| (no devuelve nada) | Oracle no tiene info de cartera para ese comparendo. Hablar con el área de Oracle. |

**Para listar TODOS los faltantes a la vez** (en PostgreSQL):
```sql
WITH pg_source AS (
    SELECT DISTINCT TRIM(numero_comparendo) AS numero_comparendo
    FROM public.rp_evidencias_validadas_view
    WHERE UPPER(TRIM(organismo)) = 'TURBACO'
      AND numero_comparendo IS NOT NULL
      AND TRIM(numero_comparendo) <> ''
)
SELECT s.numero_comparendo
FROM pg_source s
LEFT JOIN public."CARTERA_MULTAS_SAST_TURBACO" c
       ON c."COMP_NUMERO" = s.numero_comparendo
WHERE c."COMP_NUMERO" IS NULL
ORDER BY s.numero_comparendo;
```

---

## 11. Limitaciones conocidas

1. **Requiere sesión interactiva activa** para el envío de teclas a FortiClient. No funciona con el servidor en pantalla de login.
2. **Dependencia de la UI de FortiClient**: si FortiClient se actualiza y cambia el layout, la secuencia `TAB × 6 + ENTER` puede dejar de funcionar y hay que re-ajustarla.
3. **Sin alta disponibilidad**: es un único servidor. Si falla el hardware, el ETL se detiene hasta que se restaure.
4. **Sin reintentos automáticos del ETL**: si un día falla, no hay retry automático — requiere intervención manual.
5. **Ventana de ejecución fija**: todo corre a las 18:00. Cambios requieren reinicio del servicio.

---

## 12. Glosario

| Término | Significado |
|---|---|
| **ETL** | Extract, Transform, Load — proceso de integración de datos. |
| **Staging** | Área de paso temporal en Oracle donde se cargan los comparendos para que las queries de negocio operen sobre ellos. |
| **RUN_ID** | Identificador único de una ejecución del ETL. Aisla los datos de un run de otros concurrentes en el staging. |
| **Fat JAR** | Archivo `.jar` autocontenido que incluye todas las librerías dependientes. |
| **Jasypt** | Librería de cifrado usada para proteger las contraseñas en `application.yml`. |
| **Quartz** | Scheduler Java que dispara `EtlJob` según la expresión cron. |
| **WinSW** | Wrapper que corre una aplicación Java como servicio de Windows. |
| **FortiClient** | Cliente VPN SSL del proveedor Fortinet. Edición gratuita usada aquí. |
| **Daemon** | Proceso de fondo que corre como servicio del sistema. |
