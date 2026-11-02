# fnf_to_catrobat — mods de Friday Night Funkin' → projetos nativos do Pocket Code

Converte um mod de FNF (pasta ou `.zip`) num arquivo `.catrobat` que o Pocket Code
(Catroid) importa e executa como um projeto comum: objetos, scripts e bricks
nativos, variáveis, listas, looks PNG e sons MP3. Nada de HTML, APK ou outra engine.

```
python3 converter/fnf_to_catrobat.py MOD.zip -o output/FNF_Port.catrobat --minimal
python3 converter/validate_catrobat.py output/FNF_Port.catrobat --full
```

Requisitos: Python 3.8+, Pillow e `ffmpeg` (OGG → MP3).

Opções úteis: `--songs a,b` (escolhe músicas), `--difficulty hard`, `--name "Meu Port"`,
`--max-frames 16` / `--max-special-frames 34` (memória de textura),
`--no-simulate` (pula a simulação), `--minimal` (gera também `FNF_Minimo.catrobat`).

## Arquivos

| arquivo | função |
|---|---|
| `fnf_to_catrobat.py` | leitura do mod (Psych/legacy, V-Slice), personagens Sparrow, stage Lua, áudio, imagens de interface e construção de todo o gameplay em bricks |
| `catrobat_core.py` | serializador do formato Catrobat 1.12 (XStream do Catroid: ordem de campos, referências XPath relativas, fórmulas com BRACKET) e empacotamento ZIP |
| `catrobat_sim.py` | simulador headless que lê o `code.xml` gerado e executa os scripts com a semântica do runtime do Catroid |
| `validate_catrobat.py` | checagens estruturais + cenários simulados (botplay, toques humanos, sem toques/game over/reinício/menu) |
| `catroid_test/FnfPortDeserializationTest.java` | teste JUnit para rodar dentro do repositório oficial do Catroid com o `XstreamSerializer` real |

## Como o jogo funciona no Pocket Code

* **Jogo** (objeto controlador): carrega o chart (texto compacto → listas via `SUBTEXT`),
  conta 5 batidas, toca o MP3 e sincroniza o relógio num único `SetVariable`
  (`baseTempo = -TIMER*1000`), controla seções da câmera, eventos e a IA do oponente.
* **Toque0..3**: quatro colunas multitoque. `quando tocado` julga a nota (janelas do
  Psych), `COLLIDES_WITH_FINGER` detecta notas longas seguradas; um laço por lane
  marca erros e julga as peças de sustain.
* **Notas / NotasLongas / Receptores**: pools de clones reutilizáveis — cada clone
  percorre as notas `k, k+P, k+2P…`, sem um script por nota.
* **Jogador / Oponente / GF**: quadros recortados dos spritesheets numa tela comum
  (offsets, escala e flipX do Psych), animações escolhidas por variáveis globais.
* **HUD**: barra de vida (51 looks), ícones, avaliação, combo, pontuação, contagem,
  painéis de resultado/game over, menu com seleção de música e opções salvas no aparelho.

## Validação

`fnf_to_catrobat.py` grava `output/conversion_report.txt` com: integridade do ZIP,
resolução de todas as referências XStream, comparação das estruturas de cada
brick/script com projetos `.catrobat` reais do Catroid, checagem de escopo de
variáveis/listas/looks/sons, e os resultados da simulação completa da música.

A simulação **não substitui** um teste no aparelho. Para conferir com o código real
do Pocket Code (desserialização XStream, identidade das referências e ida-e-volta
das fórmulas pelo parser do editor), dentro de um clone de
`https://github.com/Catrobat/Catroid` com Android SDK 35 e JDK 21:

```
mkdir -p catroid/src/test/java/org/catrobat/catroid/test/fnfport
cp <este repo>/converter/catroid_test/FnfPortDeserializationTest.java catroid/src/test/java/org/catrobat/catroid/test/fnfport/
FNF_CATROBAT=/caminho/FNF_Port.catrobat ./gradlew :catroid:testCatroidDebugUnitTest \
    --tests org.catrobat.catroid.test.fnfport.FnfPortDeserializationTest
```

## Importar no Pocket Code

Pocket Code → **Projetos** → menu ⋮ → **Importar projeto** → escolha o `.catrobat`
(ou abra o arquivo pelo gerenciador de arquivos com o Pocket Code). Depois toque no
projeto e em ▶. Detalhes e limitações em `output/conversion_report.txt`.
