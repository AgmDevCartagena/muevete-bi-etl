# Muevete BI ETL — Documentación Técnica

Guía paso a paso para desplegar el proyecto desde cero en un servidor Windows. Cubre desde la instalación del JDK hasta la configuración de las tareas programadas.

---

## 1. Arquitectura general

El proyecto es una aplicación Java 17 headless empaquetada como fat JAR. Se ejecuta como servicio de Windows y dispara internamente un scheduler Quartz que corre el flujo ETL todos los días a las 18:00 hora Colombia.

**Componentes desplegados en el servidor:**

| Componente | Qué es | Cómo corre |
|---|---|---|
| **Servicio `MueveteBIETL`** | Wrapper WinSW que mantiene viva la JVM con el fat JAR. Dentro del proceso Java vive el scheduler Quartz. | Servicio Windows, arranque automático |
| **Tarea programada `MueveteBI-VPN-AutoConnect`** | Script PowerShell que conecta la VPN FortiClient antes del ETL. | Task Scheduler, 17:50 diario |
| **FortiClient (daemons + UI)** | Cliente VPN SSL. Los daemons son servicios Windows, la UI es la app Electron que el script de VPN maneja con SendKeys. | Daemons: servicios; UI: user app |

**Flujo en alto nivel:**

```
17:50  →  Task Scheduler ejecuta vpn-connect.ps1
          → Abre FortiClient.exe, envía TAB×6 + ENTER, acepta certificado
          → VPN queda conectada (ping a PostgreSQL 136.20.0.52:5432 OK)

18:00  →  Quartz dentro del servicio MueveteBIETL dispara EtlJob.run()
          → Extrae de PostgreSQL Bogotá
          → Carga a Oracle staging
          → Ejecuta queries de recaudo interno/externo + cartera
          → Vuelca resultados a PostgreSQL Bogotá (TRUNCATE + INSERT)
          → Actualiza tabla de control
```

---

## 2. Prerequisitos

### 2.1 Servidor

- **Sistema operativo:** Windows Server 2019 (o superior).
- **Usuario:** `Administrador` (la cuenta built-in). Es con la que se instalan y corren todos los servicios.
- **Conectividad requerida:**
  - LAN hacia Oracle `192.168.30.240:1521`.
  - Vía VPN FortiClient hacia PostgreSQL `136.20.0.52:5432` (red Bogotá).
- **Espacio en disco:** mínimo 2 GB libres en `C:\` para la app, JDK, logs y backups.

### 2.2 Credenciales que necesitas tener a mano antes de empezar

- Contraseña de PostgreSQL Bogotá (usuario `muevete_bi`).
- Contraseña de Oracle local (usuario `MUEVETE`).
- Contraseña de la VPN FortiClient (perfil `IFX - MUEVETE BI`).
- Una **master key** inventada para cifrar las contraseñas anteriores (cualquier frase larga).
- Contraseña del usuario `Administrador` del servidor (para programar la tarea).

---

## 3. Preparación del servidor

### 3.1 Crear estructura de carpetas

El proyecto se despliega bajo `C:\muevete-bi-etl\`. Crea la raíz:

```cmd
mkdir C:\muevete-bi-etl
mkdir C:\muevete-bi-etl\logs
mkdir C:\muevete-bi-etl\chat-backups
```

> **Importante:** el repo de trabajo puede estar en otra ruta (ej. `C:\Users\EQUIPO\muevete-bi-etl\`). Para producción, copia/clona a `C:\muevete-bi-etl\` para que las rutas absolutas usadas en scripts y servicios coincidan.

### 3.2 Estructura esperada después del despliegue

```
C:\muevete-bi-etl\
├── config\
│   ├── application.yml        ← configuración editable
│   └── logback.xml
├── logs\
│   ├── muevete-bi-etl.log     ← log técnico (rotación diaria, 30 días)
│   ├── audit.log              ← log de auditoría
│   └── vpn-connect.log        ← log del script VPN
├── scripts\
│   ├── muevete-bi-etl-service.exe   ← WinSW (descarga manual)
│   ├── muevete-bi-etl-service.xml
│   ├── vpn-connect.ps1
│   ├── install-service.bat
│   ├── run-once.bat
│   ├── encrypt-password.bat
│   └── decrypt-password.bat
├── sql\
│   ├── cartera_base.sql              ← base de cartera (staging × IM, sin LEFT JOINs)
│   ├── cartera_seg_actuacion.sql     ← enriquecimiento FECHA_ACTUACION
│   ├── cartera_seg_sancion.sql       ← enriquecimiento FECHA_SANCION
│   ├── cartera_mp.sql                ← enriquecimiento mandamiento de pago
│   ├── recaudo_interno.sql
│   ├── recaudo_externo.sql
│   └── ddl\
│       ├── oracle_staging.sql
│       ├── pg_control.sql
│       └── pg_destinos.sql
├── target\
│   └── muevete-bi-etl.jar     ← fat JAR compilado con Maven
├── pom.xml
└── README.md
```

---

## 4. Instalación del JDK 17

### 4.1 Descargar e instalar

1. Descarga **Eclipse Temurin 17 (LTS)** desde https://adoptium.net/temurin/releases/ (elegir Windows x64 `.msi`).
2. Instala en la ruta por defecto: `C:\Program Files\Eclipse Adoptium\jdk-17.0.x.x-hotspot\`.
3. Durante la instalación, **no marques** "Set JAVA_HOME" porque en este servidor ya existe otra aplicación con Java 8 y no queremos tocar la variable global.

### 4.2 Crear variable `MUEVETE_BI_JAVA_HOME`

Este proyecto usa su **propia** variable de entorno para no chocar con el Java 8 existente. Abre **cmd como Administrador** y ejecuta (ajusta la ruta a la versión real instalada):

```cmd
setx /M MUEVETE_BI_JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
```

### 4.3 Verificar

Cierra y reabre la consola. Luego:

```cmd
"%MUEVETE_BI_JAVA_HOME%\bin\java.exe" -version
```

Debe mostrar `openjdk version "17.0.x"`. Si ejecutas solo `java -version` verás Java 8 — es correcto, el proyecto siempre usa la variable propia.

---

## 5. Instalación de Maven (solo si vas a compilar en el servidor)

Si el JAR se compila en otra máquina y se sube compilado, **salta esta sección**.

1. Descargar Maven 3.9+ desde https://maven.apache.org/download.cgi (binary zip).
2. Extraer a `C:\Program Files\apache-maven-3.9.x\`.
3. Agregar `C:\Program Files\apache-maven-3.9.x\bin` al **PATH del sistema** (Panel → Sistema → Variables de entorno).
4. Verificar:
   ```cmd
   mvn -version
   ```

---

## 6. Definir la master key de cifrado

La master key se usa para cifrar/descifrar las contraseñas en `application.yml` con Jasypt. Es una **frase secreta larga que tú inventas** y debe ser consistente entre la máquina donde cifras y la máquina donde se ejecuta el ETL.

Como Administrador:

```cmd
setx /M MUEVETE_BI_MASTER_KEY "una-frase-secreta-muy-larga-al-menos-32-caracteres"
```

> **Importante:**
> - **Guarda esta clave en un gestor de contraseñas.** Si se pierde, hay que regenerar todos los ENC() en `application.yml`.
> - Requiere reiniciar la consola para que tome efecto.
> - Esta variable también la necesita el servicio al ejecutar — por eso se define a nivel de **sistema** (`setx /M`), no de usuario.

---

## 7. Desplegar el código

### 7.1 Copiar archivos

Copia el repositorio completo a `C:\muevete-bi-etl\`. Puedes usar `git clone` si el repo está en un remoto, o copiar manualmente.

### 7.2 Compilar el fat JAR

Desde el directorio del proyecto:

```cmd
cd /d C:\muevete-bi-etl
mvn clean package
```

Al terminar, verifica que existe `C:\muevete-bi-etl\target\muevete-bi-etl.jar` (típicamente 30-60 MB porque incluye todas las dependencias).

---

## 8. Preparar las bases de datos

### 8.1 Oracle: crear tabla de staging

Con SQL Developer, conectado como usuario `MUEVETE`, ejecuta:

```
sql/ddl/oracle_staging.sql
```

Esto crea `MUEVETE.STG_COMPARENDOS_BI` que el ETL usa como área de paso. Si el usuario `MUEVETE` tiene privilegio `CREATE TABLE`, la app lo crea sola en el primer arranque; aun así es buena práctica crearla manualmente una vez.

### 8.2 PostgreSQL: verificar permisos del usuario `muevete_bi`

El usuario de PostgreSQL debe tener permiso de:
- `SELECT` sobre `rp_evidencias_validadas_view`.
- `CREATE / TRUNCATE / INSERT / UPDATE / DELETE` sobre el esquema `public` (para las tablas `bi_*`, `RECAUDO_MULTAS_SAST_TURBACO`, `CARTERA_MULTAS_SAST_TURBACO`).

Los DDL de las tablas de control los crea el ETL al primer arranque si no existen (ver `sql/ddl/pg_control.sql` y `sql/ddl/pg_destinos.sql`).

---

## 9. Cifrar contraseñas y configurar `application.yml`

### 9.1 Cifrar las tres contraseñas

Desde `C:\muevete-bi-etl\` con `cmd`:

```cmd
scripts\encrypt-password.bat "PASSWORD_POSTGRES"
scripts\encrypt-password.bat "PASSWORD_ORACLE"
```

Cada uno imprime algo como `ENC(xxxxxxxxxxxxxxxxxxxxxxxxx)`. **Copia el valor completo incluyendo `ENC(` y `)`**.

> Si estás en PowerShell en vez de cmd, usa:
> ```powershell
> .\scripts\encrypt-password.bat "PASSWORD"
> ```
> (el `.\` es obligatorio en PowerShell).

### 9.2 Editar `config\application.yml`

Abre el archivo y verifica/reemplaza los valores. **Hosts actuales (mayo 2026):**

```yaml
postgres:
  host: "136.20.0.52"                            # ← PG Bogotá (Intraway), via VPN
  port: 5432
  database: "MUEVETE"
  user: "muevete_bi"
  passwordEnc: "ENC(VALOR_CIFRADO_POSTGRES)"     # ← pegar aqui el ENC(...) del paso 9.1
  schema: "public"

oracle:
  host: "192.168.30.240"                         # ← Oracle local Turbaco (LAN)
  port: 1521
  sid: "ORCL"
  user: "MUEVETE"
  passwordEnc: "ENC(VALOR_CIFRADO_ORACLE)"       # ← pegar aqui el ENC(...) del paso 9.1
```

> **Nota:** si el host de PG Bogotá cambia, también hay que actualizar el **healthcheck** en `scripts\vpn-connect.ps1` (3 lugares con el IP) — ese script usa el host de PG para detectar si la VPN está arriba.

### 9.3 (Opcional) Verificar que el descifrado funciona

```cmd
scripts\decrypt-password.bat "ENC(VALOR_CIFRADO)"
```

Debe imprimir la contraseña en claro. Si aparece error, la `MUEVETE_BI_MASTER_KEY` usada para cifrar es distinta a la actual — vuelve a cifrar.

---

## 10. Instalar y configurar FortiClient

### 10.1 Instalar FortiClient VPN

1. Descargar FortiClient VPN (edición gratuita, solo VPN) desde https://www.fortinet.com/support/product-downloads.
2. Instalar en la ruta por defecto: `C:\Program Files\Fortinet\FortiClient\`.

### 10.2 Configurar el perfil VPN

1. Abrir FortiClient.
2. Ir a **VPN** → **Configurar VPN**.
3. Crear un perfil con estos valores (exactos — el script depende de ellos):
   - **Nombre de la conexión:** `IFX - MUEVETE BI`
   - **Tipo:** SSL VPN
   - **Gateway remoto:** proveído por Intraway (ej. `vpn.intraway.com:10443`)
   - **Usuario:** usuario VPN proveído
   - **Contraseña:** ✅ marcar **"Guardar Contraseña"** (crítico — el script no escribe la clave)
4. Guardar.
5. Probar la conexión manualmente desde FortiClient. Debe conectar y el botón cambiar a "Desconectar".
6. Aceptar el diálogo "Server Certificate Warning" si aparece (la primera vez).
7. Desconectar.

### 10.3 Verificar daemons de FortiClient

En PowerShell:

```powershell
Get-Process | Where-Object { $_.ProcessName -match "Forti" } | Select-Object ProcessName, Id
```

Debes ver al menos: `FortiSettings`, `FortiSSLVPNdaemon`, `FortiTray`, `FortiVPN`. Estos son servicios del sistema y **no se deben matar nunca**.

### 10.4 Verificar que la secuencia de teclas sigue siendo TAB×6 + ENTER

Cierra FortiClient completamente. Ábrelo de nuevo. Sin hacer click en nada, presiona TAB seis veces y luego ENTER. Debe activarse "Conectar" y comenzar la conexión.

> **Si FortiClient se actualiza** a una versión con UI distinta, la secuencia puede cambiar. En ese caso hay que ajustar `$keys = "{TAB}{TAB}..."` en `scripts\vpn-connect.ps1`.

---

## 11. Instalar el servicio de Windows `MueveteBIETL`

### 11.1 Descargar WinSW

1. Ir a https://github.com/winsw/winsw/releases y descargar `WinSW-x64.exe` (versión .NET 4).
2. Copiarlo a `C:\muevete-bi-etl\scripts\` renombrado como `muevete-bi-etl-service.exe`.

### 11.2 Revisar `muevete-bi-etl-service.xml`

Abrir `C:\muevete-bi-etl\scripts\muevete-bi-etl-service.xml` y verificar que las rutas relativas `%BASE%\..\` resuelven correctamente (el servicio se instala desde `scripts\`, así que `%BASE%\..\` apunta a `C:\muevete-bi-etl\`).

El XML ya usa la variable `MUEVETE_BI_JAVA_HOME`:

```xml
<executable>%MUEVETE_BI_JAVA_HOME%\bin\java.exe</executable>
```

### 11.3 Instalar y arrancar

Desde **cmd como Administrador**:

```cmd
cd /d C:\muevete-bi-etl\scripts
install-service.bat
```

### 11.4 Verificar

```cmd
sc query MueveteBIETL
```

Debe aparecer `STATE: 4  RUNNING`. También revisa el log:

```cmd
type C:\muevete-bi-etl\logs\muevete-bi-etl.log
```

Debe aparecer una línea como `Scheduler iniciado. Proximo disparo: ... 18:00:00 COT`.

### 11.5 Comandos útiles del servicio

| Acción | Comando |
|---|---|
| Detener | `sc stop MueveteBIETL` |
| Arrancar | `sc start MueveteBIETL` |
| Reiniciar | `sc stop MueveteBIETL && sc start MueveteBIETL` |
| Desinstalar | `cd scripts && muevete-bi-etl-service.exe uninstall` |
| Ver estado | `sc query MueveteBIETL` |

---

## 12. Configurar la tarea programada de VPN (17:50)

### 12.1 Probar el script manualmente primero

**Con VPN desconectada y FortiClient cerrado (o en bandeja):**

```powershell
powershell -ExecutionPolicy Bypass -File C:\muevete-bi-etl\scripts\vpn-connect.ps1
```

Debe abrir FortiClient, enviar teclas, aceptar certificado y conectar. Revisa:

```powershell
type C:\muevete-bi-etl\logs\vpn-connect.log
```

Esperado: línea final `"VPN conectada tras Ns."`.

### 12.2 Crear la tarea

Desde **cmd como Administrador**. **CRÍTICO: usar el flag `/IT`** para que la tarea corra en la sesión interactiva del usuario logueado (sin `/IT`, `SendKeys` no llega a ninguna ventana):

```cmd
schtasks /Create ^
  /TN "MueveteBI-VPN-AutoConnect" ^
  /TR "powershell.exe -ExecutionPolicy Bypass -WindowStyle Hidden -File C:\muevete-bi-etl\scripts\vpn-connect.ps1" ^
  /SC DAILY ^
  /ST 17:50 ^
  /RU Administrador ^
  /RL HIGHEST ^
  /IT
```

> **Nota sobre `/IT`:** la tarea solo corre cuando el usuario `Administrador` está logueado en la sesión interactiva. Si la sesión está **desconectada** (cerrada con la X del cliente RDP), la tarea no dispara y la VPN no levanta. Por eso siempre **bloquear con `Win+L`** antes de cerrar el RDP, no desconectar.

### 12.3 Configurar "reintentar si se perdió el disparo"

```cmd
schtasks /Change /TN "MueveteBI-VPN-AutoConnect" /RI 5 /DU 0:10
```

Esto hace que si la tarea no corrió puntualmente (ej. servidor reiniciándose), la reintente cada 5 min durante 10 min.

### 12.4 Probar la tarea disparada por Task Scheduler

1. Desconectar VPN manualmente.
2. Disparar:
   ```cmd
   schtasks /Run /TN "MueveteBI-VPN-AutoConnect"
   ```
3. Esperar 30-40 segundos y revisar el log:
   ```powershell
   type C:\muevete-bi-etl\logs\vpn-connect.log
   ```

---

## 13. Verificación integral (primera corrida end-to-end)

### 13.1 Ejecución manual del ETL

⚠️ **Crítico para corridas manuales:** abrir DOS ventanas de PowerShell antes de arrancar.

**Ventana 1 — Keepalive de VPN** (impide que el túnel caiga por inactividad):
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

**Ventana 3 (opcional) — Mirar log en vivo**:
```powershell
Get-Content C:\muevete-bi-etl\logs\muevete-bi-etl.log -Wait -Tail 50
```

Al finalizar (~1-3 min) debes ver `"Ejecucion manual completada con exito"` y `FIN RUN <uuid> OK duracion=Ns`.

### 13.2 Validar resultados en base de datos

En PostgreSQL Bogotá, verificar que las tablas destino tienen datos frescos:

```sql
SELECT COUNT(*) FROM public."RECAUDO_MULTAS_SAST_TURBACO";
SELECT COUNT(*) FROM public."CARTERA_MULTAS_SAST_TURBACO";
SELECT proceso, ultimo_id_evidencia, fecha_ult_ejecucion, estado_ult_ejecucion,
       registros_pg, registros_recaudo, registros_cartera, duracion_seg
FROM public.bi_control_proceso
WHERE proceso = 'ETL_MUEVETE_BI_TURBACO';
```

La fila de `bi_control_proceso` debe tener `estado_ult_ejecucion = 'OK'` y `fecha_ult_ejecucion` reciente.

---

## 14. Checklist post-reinicio del servidor

Cuando el servidor se reinicia (planeado o inesperado):

1. **Iniciar sesión por RDP como `Administrador`.** 🚨 Crítico: el script VPN requiere **sesión interactiva activa**.
   - ✅ Bloquear la sesión con `Win+L` (deja la sesión `Active locked`) y cerrar el cliente RDP — OK, la tarea va a correr.
   - ❌ Cerrar el cliente RDP con la X (sesión queda `Disc` = desconectada) — la tarea NO corre, SendKeys no llega.

2. Verificar servicios:
   ```powershell
   Get-Service MueveteBIETL | Format-Table Name, Status
   Get-Service | Where-Object { $_.Name -like "*Forti*" } | Format-Table Name, Status, StartType
   ```
   `MueveteBIETL` y los servicios Forti `Automatic` deben estar `Running`.

3. Verificar tarea programada y que use `Interactive`:
   ```powershell
   Get-ScheduledTask -TaskName "MueveteBI-VPN-AutoConnect" | Select-Object -ExpandProperty Principal | Format-List
   ```
   `LogonType` debe ser `Interactive`. Si dice `Password` o `S4U`, hay que recrear la tarea con `/IT` (ver sección 12.2).

4. Verificar que `query session` muestra tu sesión `Active`:
   ```powershell
   query session
   ```
   Tu sesión `Administrador` debe figurar como `Active`. Si dice `Disc`, te desconectaste mal — reconectate y bloqueá con Win+L.

5. (Opcional) Si necesitas datos antes de las 18:00 de ese día, disparar manualmente:
   ```cmd
   schtasks /Run /TN "MueveteBI-VPN-AutoConnect"
   ```
   Esperar ~1-2 min, verificar VPN arriba, luego (con keepalive en otra ventana, ver 13.1):
   ```cmd
   cd /d C:\muevete-bi-etl && scripts\run-once.bat
   ```

---

## 15. Troubleshooting

### 15.1 FortiClient UI da error JavaScript al abrir

**Síntoma:** `A JavaScript error occurred in the main process. TypeError: Cannot read properties of null (reading 'TraceLog')`.

**Causa:** FortiClient se lanzó sin `-WorkingDirectory` correcto, o hay procesos residuales en estado inconsistente (ej. se mataron los daemons).

**Solución:**
1. Matar toda la UI residual (daemons NO):
   ```powershell
   Get-Process -Name FortiClient -ErrorAction SilentlyContinue | Stop-Process -Force
   ```
2. Intentar abrir manualmente desde el acceso directo del escritorio. Si falla, reiniciar el servidor.
3. Verificar que en `vpn-connect.ps1` el `Start-Process` usa `-WorkingDirectory "C:\Program Files\Fortinet\FortiClient"`.

### 15.2 VPN no conecta — log muestra "VPN no respondio tras 60s"

Posibles causas:
- **FortiClient está minimizado a bandeja sin ventana**: el script nuevo (usa `EnumWindows`) ya maneja esto. Verifica que el log muestra `Restaurando ventana hwnd=...`.
- **Secuencia de teclas cambió**: prueba manualmente si TAB×6 + ENTER sigue conectando. Si FortiClient cambió de UI, ajustar `$keys` en el script.
- **No hay sesión interactiva**: confirma que `Administrador` está logueado (RDP conectado o dejado iniciado).

### 15.3 ETL falla con "connection refused" a PostgreSQL 136.20.0.52

La VPN no está arriba. Confirmar:

```powershell
Test-NetConnection -ComputerName 136.20.0.52 -Port 5432
```

Si `TcpTestSucceeded : False`, disparar manualmente la tarea de VPN:
```cmd
schtasks /Run /TN "MueveteBI-VPN-AutoConnect"
```

### 15.4 ETL falla con `UnsupportedClassVersionError` (class file version 61.0)

La JVM en uso es Java 8, no Java 17. Verificar:

```cmd
echo %MUEVETE_BI_JAVA_HOME%
"%MUEVETE_BI_JAVA_HOME%\bin\java.exe" -version
```

Si sale Java 8 o la variable está vacía, re-definir:
```cmd
setx /M MUEVETE_BI_JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
```

Reiniciar el servicio:
```cmd
sc stop MueveteBIETL && sc start MueveteBIETL
```

### 15.5 `bi_control_proceso.estado_ult_ejecucion = 'ERROR'`

Mirar el campo `mensaje_ult_ejecucion` para identificar la causa. Cruzar con `logs\muevete-bi-etl.log` de la misma fecha-hora.

### 15.6 Error "column X is of type boolean but expression is of type character varying"

La vista de PostgreSQL origen devuelve 'SI'/'NO' y el destino es `boolean`. Ya está manejado en código con el helper `parseSiNo()`. Si aparece este error en una nueva columna, aplicar el mismo patrón en `ComparendosService.java`.

### 15.7 Error "Bad value for type timestamp/date/time: ..."

La columna origen es `text` con formato DMY local. Ya está manejado con `parseTextTimestamp()`. Si aparece en una nueva columna, cambiar el `rs.getTimestamp()` por `parseTextTimestamp(rs.getString())`.

### 15.8 La VPN se cae a mitad del ETL (después de ~15 min)

**Síntoma:** el ETL extrae recaudo OK, pero cuando intenta cargar a PostgreSQL falla con timeout / connection reset. En el log de FortiClient ves "Desconectado por inactividad".

**Causa:** FortiClient tiene timeout de inactividad. Mientras el ETL ejecuta las queries pesadas en Oracle (cartera puede tardar minutos), no hay tráfico VPN y el túnel se cierra.

**Workaround obligatorio:** mantener un keepalive en otra ventana de PowerShell **mientras corre el ETL manualmente**:

```powershell
while ($true) {
  Test-NetConnection -ComputerName 136.20.0.52 -Port 5432 -InformationLevel Quiet | Out-Null
  "$(Get-Date -Format HH:mm:ss) ping ok"
  Start-Sleep -Seconds 20
}
```

⚠️ Importante: la ventana debe ser **PowerShell** (`PS C:\...>`), no `cmd` (`C:\...>`). En cmd este script no parsea.

Para el run automático de las 18:00 esto no es necesario porque el ETL nuevo tiene queries de cartera optimizadas (~30s en vez de 15+ min).

### 15.9 RDP me saca apenas conecto / no me deja entrar al servidor

**Síntoma:** apenas haces RDP al server, te bumpea con "Te has desconectado porque se realizó otra conexión con el equipo remoto".

**Causa:** alguien dejó el `ForceAutoLogon=1` o `AutoAdminLogon=1` en el registro de Windows. Eso fuerza que `Administrador` siempre tenga una sesión activa, y RDP entra en conflicto.

**Solución desde otra máquina Windows (sin necesidad de consola):**

1. Abrir `cmd` como Administrador
2. Autenticarse al server:
   ```cmd
   net use \\IP_DEL_SERVER\IPC$ /user:Administrador
   ```
   (te pide la password)
3. Crear un .bat local que arregle el registro:
   ```cmd
   echo @echo off > %TEMP%\fix.bat
   echo reg delete "HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon" /v ForceAutoLogon /f >> %TEMP%\fix.bat
   echo reg delete "HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon" /v AutoAdminLogon /f >> %TEMP%\fix.bat
   echo reg delete "HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon" /v DefaultPassword /f >> %TEMP%\fix.bat
   echo shutdown /r /t 30 /f >> %TEMP%\fix.bat
   ```
4. Copiar el .bat al server:
   ```cmd
   copy %TEMP%\fix.bat \\IP_DEL_SERVER\C$\Windows\Temp\fix.bat
   ```
5. Crear y ejecutar tarea remota:
   ```cmd
   schtasks /Create /S IP_DEL_SERVER /U Administrador /P "PASS" /TN "FixAutoLogon" /TR "C:\Windows\Temp\fix.bat" /SC ONCE /ST 23:59 /F
   schtasks /Run /S IP_DEL_SERVER /U Administrador /P "PASS" /TN "FixAutoLogon"
   ```
6. Esperar reboot (~3 min). Probar RDP — ya funciona.

### 15.10 FortiClient se cuelga (ni manualmente abre)

**Síntoma:** click en el ícono de FortiClient y no abre. Procesos quedan en zombie.

**Solución (PowerShell como Admin):** reset total:

```powershell
# 1. Matar UI
Get-Process -Name "FortiClient" -ErrorAction SilentlyContinue | Stop-Process -Force
# 2. Detener todos los servicios Forti
Get-Service | Where-Object { $_.Name -like "*Forti*" } | Stop-Service -Force
# 3. Matar daemons residuales
Get-Process | Where-Object { $_.ProcessName -like "*Forti*" } | Stop-Process -Force
Start-Sleep -Seconds 5
# 4. Reiniciar servicios
Get-Service | Where-Object { $_.Name -like "*Forti*" -and $_.StartType -eq "Automatic" } | Start-Service
Start-Sleep -Seconds 8
# 5. Abrir UI
Start-Process -FilePath "C:\Program Files\Fortinet\FortiClient\FortiClient.exe" -WorkingDirectory "C:\Program Files\Fortinet\FortiClient"
```

El script `vpn-connect.ps1` hace exactamente esto al inicio de cada ejecución, por eso el run automático nunca debería quedar bloqueado.

### 15.11 Faltan comparendos en CARTERA aunque están en PG source

**Diagnóstico:** ejecutar en PostgreSQL Bogotá:

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
LEFT JOIN public."CARTERA_MULTAS_SAST_TURBACO" c ON c."COMP_NUMERO" = s.numero_comparendo
WHERE c."COMP_NUMERO" IS NULL
ORDER BY s.numero_comparendo;
```

Esto te lista los que están en PG pero no en cartera. Después en Oracle, para cada uno, verificar:

```sql
SELECT IM.COMP_NUMERO, IM.TIPO_COMPARENDO
FROM MUEVETE.VIEW_CONTRA_COMPA_IMPUESTOS IM
WHERE IM.COMP_NUMERO IN ('comparendo1', 'comparendo2');
```

| TIPO_COMPARENDO devuelto | Significado |
|---|---|
| `SAST` | Bug del ETL — debería estar y no está. Revisar log del último run. |
| Otro tipo (`SEMA`, etc.) | Filtro `WHERE IM.TIPO_COMPARENDO = 'SAST'` los excluye intencionalmente |
| (no aparece) | Oracle no tiene datos de cartera para ese comparendo (legítimo) |

---

## 16. Variables de entorno del sistema (resumen)

| Variable | Valor | Alcance | Definida con |
|---|---|---|---|
| `MUEVETE_BI_JAVA_HOME` | Ruta al JDK 17 | Sistema (M) | `setx /M` |
| `MUEVETE_BI_MASTER_KEY` | Frase secreta para Jasypt | Sistema (M) | `setx /M` |

Ver valores actuales:
```cmd
echo %MUEVETE_BI_JAVA_HOME%
echo %MUEVETE_BI_MASTER_KEY%
```

(En PowerShell: `$env:MUEVETE_BI_JAVA_HOME`)

---

## 17. Arquitectura interna del ETL de Cartera (importante)

### 17.1 Por qué se partió en 5 queries

La query original de cartera tenía 4 `LEFT JOIN` contra tablas grandes (`CONTRA_COMPARENDO_SEGUIMIENTO`, `CARTE_MANDAMIENTO_PAGO`, etc.) sobre 25.000+ comparendos. Oracle materializaba un plan que tardaba **15+ minutos** y reventaba la VPN por inactividad.

### 17.2 Nuevo flujo (CarteraService.java)

```
1. cartera_base.sql               → SELECT IM × STG  (sin LEFT JOINs)
   → List<BaseRow> baseRows       → ~5000 filas en ~3-5s

2. SELECT CONTRA_PROC_PASO_DESCRIPCION (toda la tabla, pequeña)
   → Map<COMP_PASO_PROCESO, descripcion>

3. cartera_seg_actuacion.sql      → seguimientos con join contra staging
   → Map<(COMP_CODIGO, paso), (fecha, resolucion)>

4. cartera_seg_sancion.sql        → seguimientos paso=5 estado=1
   → Map<COMP_CODIGO, (fecha, resolucion)>

5. cartera_mp.sql                 → mandamientos pago, estado != 3
   → Map<CART_CODIGO, (fecha, resolucion, notificacion)>

6. Ensamble en memoria (Java)     → iterar baseRows + lookup en los 4 Maps
   → escribir RowStore .bin
```

Cada query es simple (Oracle elige plan trivial con índices), el ensamble en Java es instantáneo. Total: **<60 segundos** vs los 15 min originales.

### 17.3 Por qué `obtenerTodosLosComparendos()` lee de `rp_evidencias_validadas_view` y NO de `bi_comparendos_maestro`

La vista origen devuelve **múltiples filas por `id_evidencia`** (una evidencia → varios `numero_comparendo` secuenciales: ej. id 79932 → ...384, ...385, ...386).

La tabla `bi_comparendos_maestro` tiene `id_evidencia` como PK, así que el `ON CONFLICT DO UPDATE` se queda con UN SOLO `numero_comparendo` por id_evidencia — el último que vino en el batch. Los otros se pierden.

Si Oracle necesita TODOS los comparendos para joinear con `VIEW_CONTRA_COMPA_IMPUESTOS`, hay que tomarlos directo de la vista origen, no de maestra. Por eso `obtenerTodosLosComparendos()` hace:

```sql
SELECT DISTINCT TRIM(numero_comparendo) 
FROM public.rp_evidencias_validadas_view
WHERE UPPER(TRIM(organismo)) = 'TURBACO' AND numero_comparendo IS NOT NULL
```

> Si en el futuro se quiere cambiar a un esquema de maestra con `(id_evidencia, numero_comparendo)` como PK compuesta, se podría volver a leer de maestra. Pero como está hoy, **leer de la vista directo es lo correcto**.

### 17.4 Por qué `cargarIncrementalDesdeVista()` ya NO usa watermark

El watermark `id_evidencia > X` saltaba comparendos validados tardíamente (id viejo pero validación reciente). Ahora se procesan todos los TURBACO de la vista en cada run; `ON CONFLICT DO UPDATE` hace que los repetidos sean no-op barato. Costo: ~20s extra por run, ganancia: correctitud total.

---

## 18. Backup y recuperación

### 18.1 Qué respaldar

- `C:\muevete-bi-etl\config\application.yml` (contraseñas cifradas, configuración).
- `C:\muevete-bi-etl\scripts\*.ps1` y `*.bat` (scripts operativos).
- `C:\muevete-bi-etl\scripts\muevete-bi-etl-service.xml` (config del servicio).
- El valor de `MUEVETE_BI_MASTER_KEY` — guardarlo en gestor de contraseñas.

### 18.2 Qué NO hace falta respaldar

- `target\muevete-bi-etl.jar` — reconstruible con `mvn package`.
- `logs\*.log` — opcional, solo si hay auditoría contractual de logs.

### 18.3 Recuperación ante fallo de disco

1. Reinstalar Windows, FortiClient, JDK 17.
2. Restaurar `C:\muevete-bi-etl\` desde backup.
3. Definir las dos variables de entorno.
4. Compilar el JAR: `mvn clean package`.
5. Instalar WinSW (paso 11) y reinstalar la tarea programada (paso 12).
6. Validar con `run-once.bat`.
