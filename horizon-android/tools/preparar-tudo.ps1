# Tudo de uma vez no Windows: instala o que falta (setup-windows.ps1), acha o
# server.jar e prepara os recursos (prepare-resources.ps1). Chamado pelo
# PREPARAR-RECURSOS.bat.
param([string]$ServerJar = "", [string]$MinecraftDir = "")
$ErrorActionPreference = "Stop"

& (Join-Path $PSScriptRoot "setup-windows.ps1")
if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if (-not $ServerJar) {
    # O server.jar mais novo na pasta Downloads, se houver.
    $found = Get-ChildItem (Join-Path $env:USERPROFILE "Downloads") -Filter "server*.jar" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($found) {
        Write-Host "Usando $($found.FullName)" -ForegroundColor Yellow
        $ServerJar = $found.FullName
    } else {
        Write-Host ""
        Write-Host "Baixe o server.jar da versão 26.3 em minecraft.net (Download > Minecraft Server)." -ForegroundColor Yellow
        $ServerJar = (Read-Host "Arraste o server.jar para esta janela e aperte Enter").Trim('"', ' ')
    }
}

$prep = @{ ServerJar = $ServerJar; Out = (Join-Path ([Environment]::GetFolderPath("Desktop")) "horizon-resources.zip") }
if ($MinecraftDir) { $prep.MinecraftDir = $MinecraftDir }
& (Join-Path $PSScriptRoot "prepare-resources.ps1") @prep
exit $LASTEXITCODE
