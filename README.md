# M64 Mobile Port

Port nativo de **Super Mario 64** para celular (Android), feito por
**recompilação** e não por emulação, e **sem distribuir nada da Nintendo**.

O app não traz código, gráficos, sons nem textos do jogo. Você importa um dump
da **sua própria cópia** do cartucho, o app confere o hash e recompila o código
MIPS do jogo para código nativo ARM64 **dentro do celular**, na hora de abrir.

> ⚠️ Estado atual: o recompilador no aparelho e o runtime (N64ModernRuntime)
> estão integrados e testados com um "jogo" MIPS sintético que usa a API da
> libultra (threads, filas de mensagens, eventos de VI). Com a ROM de verdade
> o jogo ainda **não tem imagem nem som**: faltam o renderizador (RT64) e o
> áudio. Ver [Roadmap](#roadmap).

## Por que isso é diferente de um emulador

| | Emulador | Este port |
|---|---|---|
| CPU | interpreta ou faz dynarec instrução a instrução, seguindo a execução | cada **função** do jogo é traduzida inteira para ARM64 de uma vez, antes do jogo começar, a partir de uma tabela de funções |
| Sistema operacional do N64 (libultra) | emula o hardware que a libultra controla | a libultra é **substituída** por uma reimplementação nativa (ultramodern) |
| Gráficos | emula o RDP | renderizador moderno (RT64) recebendo as display lists |

É a mesma técnica do [Zelda 64: Recompiled](https://github.com/Zelda64Recomp/Zelda64Recomp)
(N64Recomp). A diferença é *onde* a tradução acontece: lá, no PC de quem
compila o executável; aqui, no celular de quem joga, com o backend
[LiveRecomp](https://github.com/N64Recomp/N64Recomp/tree/main/LiveRecomp) do
N64Recomp. Por isso o APK pode ser distribuído sem conter nada do jogo.

## Como fica legal

Resumo (detalhes e ressalvas em [docs/LEGAL.md](docs/LEGAL.md)):

- **Este repositório e o APK não contêm nenhum conteúdo da Nintendo.** O
  `.gitignore` bloqueia ROMs e arquivos gerados, e o Gradle se recusa a
  empacotar `.z64/.n64/.v64` no APK.
- **A ROM vem de você**, de um cartucho que você tem. O app aceita apenas
  ROMs cujo SHA-1 bate com as versões conhecidas do jogo.
- **O código recompilado só existe na memória do seu celular.** Ele é gerado
  a cada abertura e nunca é gravado em disco nem enviado a lugar nenhum. O app
  nem pede permissão de internet.
- **Nada de baixar ROM da internet.** Isso é cópia não autorizada,
  independentemente deste projeto.
- Sem usar nome, logo ou personagens da Nintendo na identidade do app.

Isto não é aconselhamento jurídico.

## Como funciona

```
 PC (uma vez)                                Celular
 ────────────                                ───────
 sua ROM ──► decompilação n64decomp/sm64     sua ROM ──► validação SHA-1 ──► armazenamento privado
             (compilada por você)                                                   │
                    │ ELF                                                           ▼
                    ▼                        tabela de símbolos ──► recompilador AOT (N64Recomp + sljit)
            tools/gen_syms.py ─────────────►  (importada no app)          │ ARM64, só na RAM
            sm64.us.syms.toml                                             ▼
            (só nomes/endereços/tamanhos)               ultramodern + RT64 + controle por toque  [roadmap]
```

A **tabela de símbolos** diz onde cada função começa e quanto mede. Ela é só
metadado, não tem instruções do jogo, e é gerada por você a partir da
decompilação comunitária. Detalhes em [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Estrutura

```
native/                núcleo C++ (o mesmo código roda no PC e no Android)
  include/m64/         rom.hpp (validação), aot.hpp (recompilador), game.hpp (runtime), input.hpp (controle)
  src/                 implementação
  android/             ponte JNI
  cli/m64aot.cpp       ferramenta de PC: valida ROM, recompila e executa funções
  cli/m64run.cpp       ferramenta de PC: roda o jogo sobre o runtime, sem tela
  tests/               testes unitários (inclui um programa MIPS sintético)
  third_party/N64ModernRuntime  submódulo (GPL-3.0), traz o N64Recomp (MIT)
android/               app Android (Kotlin, sem dependências além do SDK)
tools/gen_syms.py      gera a tabela de símbolos a partir do ELF da decompilação
tools/tests/           testes de ponta a ponta com "ROMs" sintéticas (AOT e runtime)
docs/                  modelo legal e arquitetura
```

## Compilando

Pré-requisitos: CMake ≥ 3.20, um compilador C++20, Python 3. Para o teste de
ponta a ponta: `clang`, `ld.lld` e `llvm-objcopy`. Para o app: JDK 17 e
Android SDK com NDK `27.2.12479018`.

```sh
git clone --recurse-submodules https://github.com/vitkkk/m64-mobile-port
cd m64-mobile-port

# Núcleo + testes no PC
cmake -S native -B native/build -G Ninja
cmake --build native/build
native/build/m64_tests
tools/tests/e2e_synthetic.sh
tools/tests/e2e_runtime.sh

# APK (arm64-v8a + x86_64)
cd android && ./gradlew assembleDebug
# -> android/app/build/outputs/apk/debug/app-debug.apk
```

## Usando com a sua ROM

1. **Gere a tabela de símbolos** (no PC, uma vez por versão do jogo):
   ```sh
   git clone https://github.com/n64decomp/sm64
   cp /caminho/da/sua/rom.z64 sm64/baserom.us.z64
   make -C sm64 VERSION=us            # veja o README da decompilação para dependências
   python3 tools/gen_syms.py --elf sm64/build/us/sm64.us.elf --version us
   # -> generated/sm64.us.syms.toml
   ```
2. **Confira no PC** que a sua ROM recompila inteira e roda sobre o runtime
   (sem tela; mostra quantos quadros passaram):
   ```sh
   native/build/m64aot /caminho/da/sua/rom.z64 generated/sm64.us.syms.toml
   native/build/m64run --seconds 10 /caminho/da/sua/rom.z64 generated/sm64.us.syms.toml
   ```
3. **No celular**: instale o APK, toque em *Importar minha ROM*, depois em
   *Importar tabela de símbolos* (o `sm64.us.syms.toml`), e em *Recompilar e
   jogar*. Por enquanto a tela do jogo mostra a recompilação, o contador de
   quadros do runtime e o controle por toque, ainda sem imagem.

Versões reconhecidas (SHA-1 da ROM em formato `.z64`; `.v64` e `.n64` são
convertidos automaticamente):

| Versão | SHA-1 |
|---|---|
| EUA | `9bef1128717f958171a4afac3ed78ee2bb4e86ce` |
| Japão | `8a20a5c83d6ceb0f0506cfc9fa20d8f438cafe51` |
| Europa | `4ac5721683d0e0b6bbb561b58a71740845dceea9` |
| Shindou | `3f319ae697533a255a1003d09202379d78d5a2e0` |
| China (iQue) | `2e1db2780985a1f068077dc0444b685f39cd90ec` |

## Roadmap

- [x] Importação de ROM (`.z64/.v64/.n64`), normalização e validação por SHA-1
- [x] Recompilador AOT no aparelho (N64Recomp/LiveRecomp), testado em x86-64 e ARM64
- [x] Geração da tabela de símbolos a partir do ELF da decompilação
- [x] Teste de ponta a ponta com programa MIPS sintético (ELF → símbolos → AOT → execução)
- [x] App Android: importação via seletor de arquivos, armazenamento privado, controle multitoque
- [x] Ligar as funções da libultra do jogo às do **ultramodern/librecomp** (threads, filas de mensagens, timers, PI DMA, VI, controle)
- [x] Teste de ponta a ponta do runtime com um "jogo" sintético (boot, threads, mensagens, VI)
- [x] **N64ModernRuntime** compilando no Android, com o jogo num processo próprio
- [ ] Testar com a ROM real do SM64 (patches que o jogo precisar)
- [ ] Áudio via AAudio e saves EEPROM verificados no aparelho
- [ ] **RT64** no Android (Vulkan) desenhando no `SurfaceView`
- [ ] Microcódigo de áudio do RSP recompilado a partir da ROM do usuário
- [ ] Suporte a gamepad Bluetooth, layout de toque configurável
- [ ] Extras: widescreen, 60 FPS, mods

Detalhes de cada etapa em [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#roadmap-técnico).

## Créditos

- [N64Recomp](https://github.com/N64Recomp/N64Recomp) e
  [N64ModernRuntime](https://github.com/N64Recomp/N64ModernRuntime), de Wiseguy e colaboradores
- [n64decomp/sm64](https://github.com/n64decomp/sm64), a decompilação comunitária usada para gerar a tabela de símbolos
- [sljit](https://github.com/zherczeg/sljit), o gerador de código nativo usado pelo LiveRecomp

## Licença

GPL-3.0 (ver [LICENSE](LICENSE)), a mesma do N64ModernRuntime.

Super Mario 64 é marca da Nintendo. Este projeto não tem nenhuma afiliação com
a Nintendo nem é endossado por ela.
