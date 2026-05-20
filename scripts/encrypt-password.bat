@echo off
REM Cifra una contrasena para pegarla en application.yml
REM Uso: scripts\encrypt-password.bat "miPassword"

cd /d "%~dp0.."

if "%~1"=="" (
    echo Uso: encrypt-password.bat "texto a cifrar"
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
    echo         Asegurate de definirla tambien al ejecutar el servicio.
)

"%MUEVETE_BI_JAVA_HOME%\bin\java.exe" -cp target\muevete-bi-etl.jar com.muevete.bi.etl.util.CryptoUtil encrypt "%~1"

pause
