@echo off
rem Prepara os recursos do jogo no Windows (CMD ou dois cliques).
rem Uso: prepare-resources.bat C:\caminho\server.jar [pasta-do-.minecraft]
setlocal
set "JAR=%~1"
if "%JAR%"=="" set /p JAR=Arraste o server.jar 26.3 para esta janela e aperte Enter: 
set "JAR=%JAR:"=%"
if "%~2"=="" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0prepare-resources.ps1" -ServerJar "%JAR%"
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0prepare-resources.ps1" -ServerJar "%JAR%" -MinecraftDir "%~2"
)
echo.
pause
