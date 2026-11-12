@echo off
rem Dois cliques: instala Rust, Java 25, Git e as C++ Build Tools (winget), acha o
rem server.jar na pasta Downloads e gera horizon-resources.zip na Area de Trabalho.
rem Opcional no CMD: PREPARAR-RECURSOS.bat [server.jar] [pasta-do-.minecraft]
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0preparar-tudo.ps1" -ServerJar "%~1" -MinecraftDir "%~2"
echo.
pause
