@echo off
REM Descifra una contrasena para validar que se cifro correctamente
REM Uso: scripts\decrypt-password.bat "ENC(valor_cifrado)"

cd /d "%~dp0.."

if "%~1"=="" (
    echo Uso: decrypt-password.bat "ENC(valor_cifrado)"
    pause
    exit /b 1
)

if not defined MUEVETE_BI_JAVA_HOME (
    echo [ERROR] MUEVETE_BI_JAVA_HOME no esta definida. Debe apuntar al JDK 17.
    echo         Ejemplo:  setx /M MUEVETE_BI_JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
    pause
    exit /b 1
)

if not defined MUEVETE_BI_MASTER_KEY (
    echo [WARN] MUEVETE_BI_MASTER_KEY no esta definida. Usando clave por defecto.
)

"%MUEVETE_BI_JAVA_HOME%\bin\java.exe" -cp target\muevete-bi-etl.jar com.muevete.bi.etl.util.CryptoUtil decrypt "%~1"

pause
