# Prepara, no Windows, os recursos do jogo a partir da SUA cópia do Minecraft: Java
# Edition 26.3 e gera um .zip para importar no Zenith (Ajustes -> Recursos do jogo).
#
# Uso (PowerShell ou pelo prepare-resources.bat no CMD):
#   .\prepare-resources.ps1 -ServerJar C:\Users\voce\Downloads\server.jar
#   .\prepare-resources.ps1 -ServerJar ... -MinecraftDir D:\Jogos\.minecraft -Out D:\horizon-resources.zip
#
# Precisa de:
#   - Rust (https://rustup.rs) com as "C++ build tools" do Visual Studio (o instalador oferece)
#   - Java 25 (JAVA_HOME ou no PATH)
#   - Minecraft 26.3 instalado e aberto uma vez pelo launcher oficial (%APPDATA%\.minecraft)
#   - o server.jar 26.3 que você baixou da Mojang
# Nada do jogo é baixado: o horizon-prepare confere cada arquivo contra os hashes do Horizon.
param(
    [Parameter(Mandatory = $true)][string]$ServerJar,
    [string]$MinecraftDir = "",
    [string]$Out = (Join-Path (Get-Location) "horizon-resources.zip")
)
$ErrorActionPreference = "Stop"

$Here = Split-Path -Parent $PSScriptRoot          # horizon-android
$Repo = Split-Path -Parent $Here                  # raiz do repositório
$Hz = Join-Path $Here "third_party\horizon"
$Commit = "fc02103818c212920fd24aa34a2b2f91ba49e56f"   # o Horizon fixado (o mesmo do submódulo)

function Fail($msg) { Write-Host "ERRO: $msg" -ForegroundColor Red; exit 1 }
function Step($msg) { Write-Host "== $msg" -ForegroundColor Cyan }

if (-not (Test-Path $ServerJar)) { Fail "não achei o server.jar em $ServerJar" }
$ServerJar = (Resolve-Path $ServerJar).Path
$Out = [System.IO.Path]::GetFullPath($Out)
if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) { Fail "instale o Rust: https://rustup.rs (feche e abra o terminal depois)" }
if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) { Fail "instale o Java 25 e defina JAVA_HOME (ou ponha o java no PATH)" }

# 1. O código do Horizon (git, ou o zip do GitHub se não houver git)
if (-not (Test-Path (Join-Path $Hz "Cargo.toml"))) {
    if ((Get-Command git -ErrorAction SilentlyContinue) -and (Test-Path (Join-Path $Repo ".git"))) {
        Step "Baixando o Horizon (submódulo)"
        git -C $Repo submodule update --init --depth 1 horizon-android/third_party/horizon
        if ($LASTEXITCODE -ne 0) { Fail "git submodule falhou" }
    } else {
        Step "Baixando o Horizon $($Commit.Substring(0, 12)) do GitHub"
        $zip = Join-Path $env:TEMP "horizon-$Commit.zip"
        $tmp = Join-Path $env:TEMP "horizon-src-$Commit"
        Invoke-WebRequest -Uri "https://github.com/horizon-rust/horizon/archive/$Commit.zip" -OutFile $zip -UseBasicParsing
        if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
        Expand-Archive -Path $zip -DestinationPath $tmp
        if (Test-Path $Hz) { Remove-Item -Recurse -Force $Hz }
        Move-Item (Join-Path $tmp "horizon-$Commit") $Hz
        Remove-Item -Force $zip; Remove-Item -Recurse -Force $tmp
    }
}

# 2. O preparador
Step "Compilando o horizon-prepare (a primeira vez demora alguns minutos)"
cargo build --release --manifest-path (Join-Path $Hz "Cargo.toml") -p horizon-prepare
if ($LASTEXITCODE -ne 0) { Fail "a compilação falhou (instalou as C++ build tools do Visual Studio?)" }
$Exe = Join-Path $Hz "target\release\horizon-prepare.exe"

# 3. Preparar (o --root aponta para o research-lock.json do Horizon, com os hashes fixados)
$Work = Join-Path $env:TEMP ("horizon-prepare-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$Prepared = Join-Path $Work "preparado"
New-Item -ItemType Directory -Force -Path $Work | Out-Null
$PrepArgs = @("--root", $Hz, "--prepare", "--output", $Prepared, "--server-jar", $ServerJar, "--complete")
if ($MinecraftDir) { $PrepArgs += @("--minecraft-dir", $MinecraftDir) }
Step "Preparando a partir da sua cópia (roda o gerador de dados do vanilla com o seu jar)"
& $Exe @PrepArgs
if ($LASTEXITCODE -ne 0) { Fail "o horizon-prepare falhou (veja a mensagem acima)" }
& $Exe --root $Hz --check --output $Prepared
if ($LASTEXITCODE -ne 0) { Fail "a preparação ficou incompleta (veja o que faltou acima)" }

# 4. Zip
Step "Compactando em $Out"
if (Test-Path $Out) { Remove-Item -Force $Out }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($Prepared, $Out, [System.IO.Compression.CompressionLevel]::Optimal, $false)
Remove-Item -Recurse -Force $Work

$size = [math]::Round((Get-Item $Out).Length / 1MB)
Write-Host ""
Write-Host "Pronto: $Out ($size MB)" -ForegroundColor Green
Write-Host "Copie para o celular e importe em Ajustes -> Recursos do jogo -> Importar .zip."
Write-Host "Não compartilhe este arquivo: ele é feito da sua cópia do jogo."
