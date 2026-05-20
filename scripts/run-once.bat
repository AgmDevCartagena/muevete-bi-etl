@echo off
REM Ejecuta el ETL una sola vez inmediatamente (sin esperar al scheduler)
REM Uso: doble click, o desde consola  scripts\run-once.bat

cd /d "%~dp0.."

if not defined MUEVETE_BI_JAVA_HOME (
    echo [ERROR] MUEVETE_BI_JAVA_HOME no esta definida. Debe apuntar al JDK 17.
    echo         Ejemplo:  setx /M MUEVETE_BI_JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
    pause
    exit /b 1
)

if not defined MUEVETE_BI_MASTER_KEY (
    echo [WARN] MUEVETE_BI_MASTER_KEY no esta definida. Usando clave por defecto.
)

"%MUEVETE_BI_JAVA_HOME%\bin\java.exe" -Dconfig.file=config\application.yml -jar target\muevete-bi-etl.jar --run-once

pause
