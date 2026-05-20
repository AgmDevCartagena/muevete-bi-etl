@echo off
REM =====================================================================
REM Instala la aplicacion como servicio de Windows usando WinSW.
REM Requisitos previos:
REM   1) Descargar WinSW (https://github.com/winsw/winsw/releases)
REM      y copiar WinSW.NET4.exe a esta carpeta scripts\ con el nombre
REM      muevete-bi-etl-service.exe
REM   2) Haber compilado el jar (mvn package)
REM   3) Haber definido MUEVETE_BI_JAVA_HOME apuntando al JDK 17 a nivel
REM      de sistema (setx /M MUEVETE_BI_JAVA_HOME "C:\Program Files\...")
REM   4) VPN FortiClient conectada antes de cada ejecucion del ETL
REM   5) Haber definido la variable de entorno MUEVETE_BI_MASTER_KEY
REM      a nivel de sistema (setx /M MUEVETE_BI_MASTER_KEY "..."), o
REM      dejar que use la clave por defecto.
REM =====================================================================

cd /d "%~dp0"

if not exist muevete-bi-etl-service.exe (
    echo [ERROR] Falta muevete-bi-etl-service.exe en la carpeta scripts\
    echo         Descargalo de: https://github.com/winsw/winsw/releases
    pause
    exit /b 1
)

muevete-bi-etl-service.exe install
if errorlevel 1 (
    echo [ERROR] Fallo la instalacion del servicio.
    pause
    exit /b 1
)

muevete-bi-etl-service.exe start

echo.
echo Servicio instalado y arrancado. Verificar con:
echo    sc query MueveteBIETL
echo.
pause
