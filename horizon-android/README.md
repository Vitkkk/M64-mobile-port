# Zenith Launcher: Minecraft Java no celular, com o Horizon (Rust)

Um launcher para Android no estilo do PojavLauncher, só que em vez de rodar o
Minecraft Java numa JVM ele roda o **[Horizon](https://github.com/horizon-rust/horizon)**,
a reescrita open source em Rust do Minecraft: Java Edition 26.3 (o "Minecraft Rust
Edition"). Não tem JVM, não tem tradução de OpenGL: é código nativo ARM64
desenhando com Vulkan (ou OpenGL ES) pelo wgpu.

> ⚠️ **Estado:** o launcher (interface, contas, skins, instâncias, mods,
> controles, ajustes) compila e roda. O motor do Horizon tem um patch pequeno
> para Android e uma crate de entrada própria. Ele compila no CI
> (`.github/workflows/horizon-android.yml`), mas **ainda não foi testado num
> celular**. Ver [Limitações](#limitações-e-roadmap).

## Telas

Renderizadas pelo Robolectric a partir do código real (`./gradlew testDebugUnitTest -Pzenith.screenshots`):

| Início | Conta e skin | Controles | Gráficos |
|---|---|---|---|
| ![](docs/screens/01-inicio.png) | ![](docs/screens/04-conta.png) | ![](docs/screens/05-controles.png) | ![](docs/screens/07-graficos.png) |

![Editor de controles](docs/screens/08-editor-controles.png)

## O que tem

| Área | O que faz |
|---|---|
| **Início** | Botão Jogar, estado de prontidão (motor, recursos, conta, posse do jogo), continuar mundos recentes, servidor favorito |
| **Instâncias** | Várias pastas de jogo (mundos, opções, packs, mods, prints), com nome, ícone e cor. Também: mundo ou servidor para abrir direto, layout de controles por instância e argumentos extras |
| **Mods** | Instalados (ligar/desligar/apagar) e Explorar: o índice oficial de mods do Horizon, com busca, filtro por tipo, conferência de tamanho e SHA-256 e lista de revogados. Importa `.hmod`, resource packs `.zip` e shaders `.hshader` |
| **Conta** | Login Microsoft por código (Xbox Live → XSTS → Minecraft), conferência de posse do Java Edition, nomes offline, tokens cifrados no Android Keystore |
| **Skin** | Prévia de frente e costas (camadas externas, braço fino ou clássico), escolher PNG 64x64, capa, enviar a skin para a conta Microsoft |
| **Controles** | Joystick analógico (fixo ou flutuante), arrastar para olhar, gestos (tocar = usar, segurar = quebrar), hotbar por toque, correr automático. **Editor de layout** (arrastar, tamanho, rótulo, ação, quando aparece), vários layouts, exportar e importar em JSON. Controle Bluetooth/USB |
| **Ajustes** | API gráfica (Auto, Vulkan ou OpenGL ES), escala de resolução, presets para celular, FPS, distâncias, LOD, qualidade, interface, idioma, som por categoria, recursos do jogo, Client ID do Azure, tema e cor, logs e crashes |
| **API** | Console de comandos dentro do jogo (a API de controle do Horizon), botões que rodam comandos, deep links `zenith://play`, arquivo de lançamento documentado |

## Como funciona

```
 Launcher (Kotlin, processo principal)          Jogo (processo :game)
 ─────────────────────────────────────          ─────────────────────
 Compose UI ── instâncias, contas, mods          HorizonGameActivity (GameActivity)
     │                                              │ carrega libhorizon_android.so
     ├─ escreve files/launch/launch.json ─────────► android_main (Rust, native/)
     │   (argumentos da Launch API do Horizon,      │ stdout/stderr → logcat
     │    ambiente: skin offline, etc.)             │ embed::set_args / set_android_app
     ├─ escreve a sessão (0600, lida e apagada)     ▼
     └─ startActivity ─────────────────────────►  horizon_client::entry::main()
                                                    winit (android-activity) + wgpu (Vulkan/GLES)
 Overlay de toque (View) ── socket Unix ──────►  --control-socket: a API do horizon-ctl
   joystick → "pad stick left x y"                 (key, mouse, pad, mouse-move, select, type,
   olhar   → "mouse-move dx dy"                     state, save, quit, give, tp, …)
   botões  → "key space down", "mouse left up"…
 Menus do jogo ◄── toque passa direto ── winit Touch → mouse (patch embed::touch_as_mouse)
```

- **Por que o socket de controle?** O Horizon já tem uma API de automação
  completa (`horizon-ctl`, docs/agent-control.md no Horizon) com teclado, mouse,
  pad virtual e estado do jogo. Usar essa API deixa o patch no Horizon pequeno
  (~140 linhas) e dá ao launcher um console e botões programáveis sem custo extra.
- **Processo separado:** o jogo roda em `:game`. Sair do jogo encerra o processo
  e libera toda a memória; um crash não derruba o launcher, que mostra o log.
- **Patch no Horizon** (`patches/horizon-android.patch`):
  - `embed.rs`: argumentos vindos do launcher, o `AndroidApp` do winit, toque → mouse;
  - os três `EventLoop::new()` passam por `embed::event_loop()`;
  - `arboard` (área de transferência) fica fora do Android, que não tem backend para ele;
  - a crate `horizon-android` entra no workspace.

## Legal: o que o app traz e o que não traz

O modelo é o do próprio Horizon:

- **Nem o APK nem este repositório têm código, texturas, sons ou dados do
  Minecraft.** O Horizon é uma reimplementação (MIT); o CI confere que o APK não
  leva `.jar`, `.ogg`, regiões nem recursos preparados.
- **Os recursos vêm da sua cópia.** Você roda o `horizon-prepare` no PC com o
  client e o server 26.3 que você tem, e importa o zip no celular. O app não baixa
  nada disso.
- **O jogo só abre com uma conta dona do Java Edition**, como o launcher do
  Horizon pede a launchers de terceiros (docs/api/launch.md e
  docs/microsoft-account.md no Horizon). Nomes offline jogam atrás de uma conta
  dona. Há uma chave de desenvolvimento, desligada por padrão, para testar sem
  login.
- Não usa nome, logo nem arte do Minecraft na identidade do app.
- Não é um produto oficial do Minecraft, nem aprovado ou associado à Mojang ou à Microsoft.

## Compilando

Pré-requisitos: JDK 17, Android SDK (plataforma 35) com NDK `27.2.12479018`, Rust
estável com o alvo `aarch64-linux-android` e `cargo-ndk`.

```sh
git clone https://github.com/vitkkk/m64-mobile-port && cd m64-mobile-port

# 1. O motor (Rust): baixa o Horizon fixado, aplica o patch, compila a .so
rustup target add aarch64-linux-android      # + x86_64-linux-android para o emulador
cargo install cargo-ndk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
horizon-android/tools/build-native.sh                    # release, arm64-v8a
# horizon-android/tools/build-native.sh --abi arm64-v8a,x86_64 --profile fast

# 2. O APK
cd horizon-android
./gradlew assembleDebug -Pzenith.abis=arm64-v8a  # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest                      # testes do launcher

# Login Microsoft: um Client ID do Azure seu, aprovado pela Mojang
./gradlew assembleRelease -Pzenith.msaClientId=<GUID>
```

Sem o passo 1 o APK abre normalmente (dá para explorar e configurar tudo), mas o
botão Jogar avisa que falta o motor. O CI publica o APK completo como artefato
(`zenith-launcher-arm64`).

## Usando

1. **Prepare os recursos no PC** (uma vez por versão), a partir da sua cópia:
   ```sh
   horizon-android/tools/prepare-resources.sh --server-jar ~/Downloads/server.jar
   # -> horizon-resources.zip (centenas de MB; é seu, não compartilhe)
   ```
   No **Windows**, sem WSL: dois cliques em `horizon-android\tools\prepare-resources.bat`
   (ele pede o `server.jar`), ou no CMD:
   ```bat
   horizon-android\tools\prepare-resources.bat C:\Users\voce\Downloads\server.jar
   ```
2. No celular: **Ajustes → Recursos do jogo → Importar .zip**.
3. **Conta → Entrar com Microsoft** (ou, para desenvolvimento, desligue a verificação
   em Ajustes → Conta Microsoft e use um nome offline).
4. **Início → JOGAR.**

Dicas de desempenho: o preset **Equilibrado** (8 chunks, 60 FPS, resolução 85%) é
o padrão das instâncias novas. Em aparelhos com Vulkan problemático use
**OpenGL ES** em Ajustes → Gráficos → API gráfica.

## A API

| Superfície | Como |
|---|---|
| Console no jogo | Ajustes → Avançado e API → Console; depois ☰ no jogo. Qualquer comando do `horizon-ctl`: `state`, `give diamond 3`, `time 6000`, `options-set renderDistance 6`, `screenshot /sdcard/...`, `bench 10`… |
| Botões programáveis | No editor de controles, tipo "Comando da API": um comando ao apertar, outro ao soltar |
| Deep links | `zenith://play?instance=ID&world=PASTA` · `zenith://play?server=host:25565` (pede confirmação, a não ser que você confie) |
| Arquivo de lançamento | `files/launch/launch.json` (`version`, `game_dir`, `args`, `env`), lido pelo `android_main`; `files/launch/exit.json` ao terminar |
| Layouts | JSON exportável (`version`, `joystick`, `buttons[]` com `x`/`y` 0..1, `size` em dp, `action` `{type: KEY|MOUSE|PAD|COMMAND|SPECIAL, value, release}`) |

## Estrutura

```
horizon-android/
  app/                       o launcher (Kotlin, Jetpack Compose) e a tela do jogo
    src/main/java/io/github/zenith/
      auth/                  login Microsoft (código de dispositivo)
      data/                  pastas, instâncias, contas, opções, recursos, mods, skins
      controls/              layouts, overlay de toque, editor
      game/                  GameActivity, socket de controle, lançamento
      ui/                    telas
  native/                    crate horizon-android: android_main, logcat, launch.json
  patches/horizon-android.patch
  third_party/horizon        submódulo (horizon-rust/horizon, commit fixado)
  tools/build-native.sh      Horizon → libhorizon_android.so
  tools/prepare-resources.sh recursos da sua cópia → zip para importar
```

## Limitações e roadmap

- [x] Launcher completo: instâncias, contas Microsoft e offline, skins, mods, controles editáveis, ajustes, logs, API
- [x] Crate de entrada Android + patch do Horizon (winit/GameActivity, argumentos, toque nos menus)
- [x] CI que compila o motor para arm64 e empacota o APK
- [ ] **Testar num celular de verdade** (Adreno, Mali, Xclipse) e corrigir o que aparecer
- [ ] Voltar ao jogo depois de sair do app (recriar a superfície do wgpu em `resumed`); hoje o mundo é salvo ao sair
- [ ] Preparar os recursos no próprio celular (precisa de um Java 25 para Android para os exportadores do Horizon)
- [ ] Área de transferência do Android no jogo (hoje copiar e colar funcionam só dentro do jogo)
- [ ] Mods nativos (`.hnmod`): compilar motores com eles no CI e importar no app
- [ ] Teclado na tela integrado ao GameTextInput do GameActivity
- [ ] x86_64 (emulador) no CI

## Créditos

- [Horizon](https://github.com/horizon-rust/horizon), de Ludovic Marie e colaboradores (MIT)
- [winit](https://github.com/rust-windowing/winit), [wgpu](https://github.com/gfx-rs/wgpu), [android-activity](https://github.com/rust-mobile/android-activity), [GameActivity](https://developer.android.com/games/agdk/game-activity)
- Inspirado por PojavLauncher, MojoLauncher e afins

Licença do Zenith: GPL-3.0, como o resto do repositório. O Horizon mantém a dele (MIT).
