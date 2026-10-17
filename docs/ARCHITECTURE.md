# Arquitetura

## Visão geral

```
┌──────────────────────── App Android (Kotlin) ────────────────────────┐
│ MainActivity     importar ROM / tabela de símbolos, recompilar        │
│ RomStore         armazenamento privado (filesDir/rom, filesDir/syms)  │
│ GameActivity     SurfaceView (vídeo) + TouchControllerView            │
└───────────────┬──────────────────────────────────────────────────────┘
                │ JNI (native/android/jni_bridge.cpp)
┌───────────────▼──────────────── libm64port.so ───────────────────────┐
│ m64::load_rom        detecta .z64/.v64/.n64, normaliza, SHA-1         │
│ m64::recompile_program                                                │
│   ├─ N64Recomp::Context::from_symbol_file(syms.toml, ROM)             │
│   ├─ N64Recomp::recompile_function_live(...)   para cada função       │
│   └─ LiveGenerator (sljit) ─► código ARM64/x86-64 executável na RAM   │
│ m64::controller(n)   estado do controle (lido pelo runtime)           │
│                                                                       │
│ m64::run_game        librecomp + ultramodern (libultra nativa)        │
│ [a integrar] RT64 (Vulkan), áudio do RSP                              │
└───────────────────────────────────────────────────────────────────────┘
```

## Recompilação no aparelho

O N64Recomp divide o binário em funções usando a tabela de símbolos e traduz
cada instrução MIPS para uma operação equivalente. O backend padrão emite C
(compilado depois por um compilador de verdade); o backend **LiveRecomp** emite
código de máquina direto, pelo [sljit](https://github.com/zherczeg/sljit).

Aqui usamos o LiveRecomp para **todas** as funções do jogo, no momento da
carga:

- **Não é dynarec.** Não há tradução sob demanda seguindo a execução nem
  cache de blocos. O conjunto de funções é fixo e conhecido antes de rodar.
  `jal` vira chamada direta; `jalr` (ponteiros de função) passa por uma
  tabela endereço→função (`AotProgram::find`).
- **Não é interpretação.** Nenhuma instrução MIPS é decodificada durante o
  jogo.
- **Não é persistido.** O sljit grava endereços absolutos no código, então o
  resultado só vale para aquele processo. O custo da tradução é pago a cada
  abertura. No teste sintético a tradução leva menos de 1 ms; para os
  milhares de funções do jogo, espera-se uma fração de segundo num celular
  atual (a medir quando o runtime estiver integrado).

A memória do N64 (RDRAM) é um `uint8_t*` com cada palavra de 32 bits na ordem
do host: um acesso de byte a `addr` vira `rdram[(addr - 0x80000000) ^ 3]`.

### Android e memória executável

O Android permite que apps aloquem páginas executáveis em tempo de execução,
e é assim que emuladores como Dolphin e PPSSPP fazem JIT. O iOS não permite
fora de casos especiais, então um port para iOS teria que usar o backend C do
N64Recomp, com o executável gerado no PC de quem tem a ROM e nunca
distribuído.

## Tabela de símbolos

Formato do N64Recomp (`[[section]]` com `rom`, `vram`, `size` e
`functions = [{ name, vram, size }]`, mais `relocs` opcionais). É gerada por
`tools/gen_syms.py`, que roda `N64Recomp --dump-context` sobre o ELF da
decompilação. Como a decompilação gera um binário idêntico byte a byte à ROM
original, os endereços batem com a ROM do usuário.

O teste `tools/tests/e2e_synthetic.sh` exercita exatamente esse caminho com
um programa MIPS próprio: compila C para um ELF MIPS (clang + lld), produz a
"ROM" com `objcopy -O binary` (como a decompilação faz), gera a tabela com
`gen_syms.py` e roda as funções recompiladas com `m64aot --call`, incluindo
uma chamada por ponteiro lido da seção de dados.

## Controle por toque

`TouchControllerView` desenha analógico, A, B, Z, botões C, L, R e Start, com
multitoque. O estado vai para `m64::InputState` como um `uint32_t` atômico
(botões no formato do controle de N64 + analógico X/Y em ±80), pronto para o
callback de controle do ultramodern.

## Runtime (libultra nativa)

O jogo chama funções da libultra (`osCreateThread`, `osRecvMesg`,
`osPiStartDma`, `osContGetReadData`…). Elas não são traduzidas: vão para as
implementações nativas do N64ModernRuntime (`librecomp` + `ultramodern`).

**Trampolins.** Antes de recompilar, o corpo de cada função substituída é
trocado, só na tradução, por `syscall; nop`. O N64Recomp traduz `syscall`
numa chamada ao `syscall_handler` seguida de retorno, e o handler despacha
pelo endereço para a função nativa (`osCreateThread_recomp` etc.). Isso
funciona sem mudar o N64Recomp e vale para chamadas diretas (`jal`) e por
ponteiro (`jalr`). Ver `AotHooks` em `native/include/m64/aot.hpp`.

**Quais funções.** A tabela nome → função nativa é gerada pelo CMake a partir
das fontes do runtime (`native/cmake/runtime_native_table.cmake`, que procura
definições `extern "C" void X_recomp(uint8_t* rdram, recomp_context* ctx)`).
As funções das listas do N64Recomp (`reimplemented_funcs`, `ignored_funcs`)
que o runtime não implementa ficam proibidas: se forem chamadas, o programa
aborta com o nome delas, em vez de tocar em hardware que não existe.

**Registro no librecomp** (`native/src/game.cpp`): as seções e funções
traduzidas viram a `SectionTableEntry` que o librecomp usa para resolver
ponteiros de função e iniciar threads. O jogo é registrado com o hash XXH3 da
ROM, o ponto de entrada (`0x80246000` no SM64) e o tipo de save (EEPROM
4 Kbit). No boot, o librecomp copia 1 MiB da ROM a partir de `0x1000` para o
ponto de entrada (o papel do IPL3) e chama a entrada.

**Escalonamento.** Cada thread do jogo é uma thread do sistema, mas só uma
roda por vez, como no N64. O laço infinito da thread ociosa (`while (1);` no
SM64) é traduzido como `pause_self`, que espera um evento externo (VI, fim de
tarefa do RSP, timer) e passa a vez para a thread de maior prioridade.

**Callbacks** que o app fornece: renderizador (por enquanto um vazio, que só
confirma as display lists), microcódigo do RSP (o de áudio ainda é mudo),
áudio, controle (lido de `m64::InputState`) e eventos de VI.

**Testes.** `tools/tests/e2e_runtime.sh` compila um "jogo" MIPS em C com a
mesma estrutura de boot do SM64 (entrada que configura a pilha, thread ociosa
que baixa a prioridade e entra em laço, thread principal, `osViSetMode`,
espera de quadros por `osViSetEvent`) e o roda com `m64run`.

**Android.** O jogo roda num processo próprio (`android:process=":game"`),
porque o runtime só pode ser iniciado uma vez por processo. Sair da tela do
jogo encerra esse processo.

## Roadmap técnico

1. **ROM real do SM64.** Rodar `m64run` com a ROM e a tabela de símbolos
   reais e corrigir o que aparecer: funções que precisem de stub (acesso a
   registradores de hardware), jump tables, o modo de ponto flutuante.
2. **RT64 no Android.** O RT64 desenha via Vulkan através da camada `plume`.
   Falta a superfície Android (`VK_KHR_android_surface` a partir do
   `ANativeWindow` do `SurfaceView`) e ajustes para GPUs móveis (Adreno, Mali).
   Entra no lugar do renderizador vazio via `RunOptions::create_renderer`.
3. **Áudio do RSP.** O microcódigo de áudio (`aspMain`) também está na ROM.
   Opções: recompilá-lo no aparelho como o código principal, ou usar
   processamento de áudio em alto nível. Ele não pode vir pronto no APK, porque
   seria código da Nintendo. Saída por AAudio.
4. Saves (EEPROM de 4 Kbit) conferidos no aparelho.
5. Gamepad físico, layout de toque configurável, widescreen e 60 FPS.
