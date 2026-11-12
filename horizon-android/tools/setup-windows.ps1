# Instala no Windows o que o horizon-prepare precisa, pelo winget (já vem no Windows 10/11):
#   - Rust (rustup, toolchain stable MSVC)
#   - Visual Studio 2022 Build Tools com C++ (o linker que o Rust usa no Windows)
#   - Java 25 (Eclipse Temurin)
#   - Git
# Pula o que já estiver instalado. Pode pedir permissão de administrador (UAC).
# Ao final, deixa PATH e JAVA_HOME prontos nesta janela.
$ErrorActionPreference = "Stop"

function Step($msg) { Write-Host "== $msg" -ForegroundColor Cyan }
function Fail($msg) { Write-Host "ERRO: $msg" -ForegroundColor Red; exit 1 }

if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    Fail "o winget não está disponível. Instale o 'Instalador de Aplicativo' pela Microsoft Store e rode de novo."
}

function Refresh-Path {
    $machine = [Environment]::GetEnvironmentVariable("Path", "Machine")
    $user = [Environment]::GetEnvironmentVariable("Path", "User")
    $env:Path = "$machine;$user;$env:USERPROFILE\.cargo\bin"
}

function Install($id, $label, $extra = @()) {
    $listed = winget list --id $id --exact --accept-source-agreements 2>$null | Out-String
    if ($listed -match [regex]::Escape($id)) {
        Write-Host "   ${label}: já instalado"
        return
    }
    Step "Instalando $label"
    $wingetArgs = @("install", "--id", $id, "--exact", "--silent", "--accept-package-agreements", "--accept-source-agreements") + $extra
    & winget @wingetArgs
    # 0 = ok; -1978335189 = já instalado / nada a atualizar
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne -1978335189) {
        Fail "o winget não conseguiu instalar $label (código $LASTEXITCODE)"
    }
}

Install "Git.Git" "Git"
Install "Microsoft.VisualStudio.2022.BuildTools" "Visual Studio Build Tools (C++)" @(
    "--override", "--wait --passive --norestart --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"
)
Install "Rustlang.Rustup" "Rust"
Install "EclipseAdoptium.Temurin.25.JDK" "Java 25 (Temurin)"

Refresh-Path

# Rust: garante a toolchain stable (o rustup do winget às vezes vem sem nenhuma).
if (Get-Command rustup -ErrorAction SilentlyContinue) {
    $toolchains = rustup toolchain list 2>$null | Out-String
    if ($toolchains -notmatch "stable") {
        Step "Instalando o Rust stable"
        rustup default stable
    }
}
if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
    Fail "o Rust foi instalado mas o cargo não apareceu. Feche esta janela e rode de novo."
}

# Java 25: acha a pasta do JDK e usa como JAVA_HOME nesta janela.
$jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-25*" -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
if ($jdk) {
    $env:JAVA_HOME = $jdk.FullName
    $env:Path = "$($jdk.FullName)\bin;$env:Path"
    Write-Host "   JAVA_HOME = $($jdk.FullName)"
} elseif (-not $env:JAVA_HOME) {
    Fail "não achei o Java 25 em C:\Program Files\Eclipse Adoptium. Instale o Temurin 25 e rode de novo."
}

Write-Host "Tudo pronto." -ForegroundColor Green
exit 0
