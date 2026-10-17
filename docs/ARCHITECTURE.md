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
│ [a integrar] librecomp + ultramodern (libultra nativa), RT64 (Vulkan) │
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

## Roadmap técnico

1. **libultra → ultramodern.** O jogo chama funções da libultra
   (`osCreateThread`, `osRecvMesg`, `osPiStartDma`, `osContGetReadData`…).
   No recomp, essas chamadas precisam ir para as implementações nativas do
   librecomp/ultramodern em vez do código MIPS original. O N64Recomp já tem as
   listas desses nomes (`src/symbol_lists.cpp`: funções reimplementadas e
   ignoradas). O trabalho é: marcar essas funções no `Context` antes de
   recompilar e resolver as chamadas para os símbolos do librecomp
   (`LiveGeneratorInputs` + `get_function`).
2. **N64ModernRuntime no Android.** Compilar `ultramodern` e `librecomp` com o
   NDK. Saída de áudio por AAudio (o ultramodern entrega amostras por
   callback). Saves (EEPROM de 4 Kbit do SM64) em `filesDir`.
3. **RT64 no Android.** O RT64 desenha via Vulkan através da camada `plume`.
   Falta a superfície Android (`VK_KHR_android_surface` a partir do
   `ANativeWindow` do `SurfaceView`) e ajustes para GPUs móveis (Adreno, Mali).
4. **Áudio do RSP.** O microcódigo de áudio (`aspMain`) também está na ROM.
   Opções: recompilá-lo no aparelho como o código principal, ou usar
   processamento de áudio em alto nível. Ele não pode vir pronto no APK, porque
   seria código da Nintendo.
5. **Boot.** O ponto de entrada do SM64 é `0x80246000`. O runtime precisa
   reproduzir o que o IPL3 faz: copiar o segmento principal da ROM para a RDRAM
   e chamar a entrada com a pilha configurada.
6. Gamepad físico, layout de toque configurável, widescreen e 60 FPS.
