# Tempest — tempo severo para o Horizon

Mod nativo para o [Horizon](https://github.com/horizon-rust/horizon) (a reescrita
em Rust do Minecraft: Java Edition 26.3) com **supercélulas, tornados (EF0–EF5),
squall lines/derechos, furacões, nevascas, radar Doppler, sirenes, raios e dano
de vento**, feito para rodar **leve em aparelho fraco**.

> Esta pasta é independente do resto do repositório (o port do SM64). Tudo do
> mod está aqui dentro.

## De onde vem, e por que não é uma cópia

O pedido foi portar o **ProtoManly's Weather (PMWeather) 0.17.19** do NeoForge
para o Horizon. Duas coisas impedem um port literal:

1. **O Horizon não roda mods Java.** Mods nativos dele são Rust compilado junto
   com o jogo, usando os pontos de gancho do `horizon_client::mod_api`. Não há
   como reaproveitar o `.jar`: tudo tem que ser reescrito.
2. **O PMWeather é "All Rights Reserved"** (está no `neoforge.mods.toml` do
   próprio jar). Copiar o código decompilado, os shaders, as texturas ou os
   sons dele num projeto público seria redistribuir material protegido.

Então o Tempest é uma **reimplementação do zero ("clean room")**: a mesma
proposta de jogo (tempestades que nascem do ambiente, tornados com escala EF,
radar com refletividade e velocidade, dano progressivo, sirenes…), com código,
modelo físico e visual próprios. **Nenhum arquivo do jar foi copiado** — do
jar só foram olhados os nomes das funcionalidades (lista de classes e o
arquivo de idioma) para saber o que cobrir. Sons são os do próprio Minecraft.
O Tempest não é afiliado ao PMWeather nem ao seu autor.

## O que tem

| Funcionalidade do PMWeather | No Tempest |
|---|---|
| Motor termodinâmico / risco | Campos de temperatura, ponto de orvalho, CAPE, cisalhamento, SRH, LCL, SCP e STP que se deslocam com o vento (frentes, línguas de umidade), ciclo diário e estações. Categoria de risco (TSTM…HIGH) no HUD |
| Supercélulas | Núcleo, flanco dianteiro, eco em gancho, mesociclone, wall cloud girando, bigorna, granizo |
| Tornados | EF0–EF5 com vórtice de Rankine modificado (rotação ciclônica, influxo, corrente ascendente, translação), ciclo formação → maduro → "rope", oclusão, oscilação do caminho, funil e nuvem de detritos |
| Squall lines | Linha que se curva em arco com o tempo (bow echo), shelf cloud, área estratiforme, ventos em linha reta de derecho, tornados de QLCS |
| Furacões (Cyclone) | Olho calmo, parede do olho, bandas em espiral, perfil de vento tipo Holland (categorias 1–5) |
| Tempo de inverno | Nevasca com whiteout, acúmulo de neve, chuva congelante que vira gelo, granizo e sleet |
| Radar | Refletividade (escala de cores tipo NWS) e velocidade radial (verde/vermelho), marcadores de tornado (TVS), alcance ajustável |
| Anemômetro / estação (METAR) / sondagem | Painel de leitura: temperatura, orvalho, vento e direção, CAPE, SRH, cisalhamento, tipo e taxa de precipitação |
| Sirene / sensor de tornado | Sirene (som de chifre de raid) e faixa "TORNADO WARNING" quando um tornado está a < 3 km, ou aviso antecipado de rotação |
| Dano de vento / "scouring" | Por degrau da escala EF: EF0 plantas, neve e copas; EF1 vidro, portas, cercas, lã; EF2 árvores sem folhas e **descascadas** (tronco vira tronco descascado), grama vira terra, telhados arrancados; EF3 chão raspado até **terra grossa**, troncos quebram, tijolo/pedra lisa/tijolo de pedra/pedregulho quebram; EF4–EF5 paredes derrubadas e **buracos de 1 bloco**. Na versão mobile, tijolo, pedra, tábuas e troncos saem voando como blocos em queda e são carregados pelo tornado |
| Raios | Raios de verdade (do jogo) sob ecos fortes, trovões distantes com atraso pela distância e clarão no céu |
| Física em entidades | Mobs e itens puxados e erguidos pelo tornado; jogador empurrado pelo vento e erguido no núcleo |
| Chuva do jogo | A chuva/trovoada vanilla liga e desliga conforme as tempestades sobre os jogadores |

**Não portado (ainda):** blocos próprios (bloco de radar, WSR-88D, sirene,
anemômetro, balão meteorológico, blocos queimados/reforçados/"scalded"),
estações com cores de folhagem, fire whirls, sincelos, as nuvens volumétricas
por raymarching e as compatibilidades com Create/Distant Horizons/Sodium. Os
blocos precisariam de modelos e texturas originais (não dá para usar os do
jar); o radar, a sirene e a estação existem como HUD por enquanto.

## Por que é leve

* **Nada é simulado passo a passo.** Cada tempestade é um punhado de números
  definidos quando nasce; posição, intensidade, eco de radar, vento e tornado
  em qualquer instante são funções fechadas do tempo. Só se avalia o que está
  perto do jogador, quando precisa.
* **Sem tráfego de rede pesado.** As tempestades nascem de hashes de
  (semente, célula do mundo, intervalo de tempo), então servidor e clientes
  calculam as mesmas. O servidor só manda a lista (~70 bytes por tempestade) a
  cada 5 s, e o cliente anima sozinho.
* **Visual barato.** Nuvens, cortinas de chuva, funil e detritos são caixas
  translúcidas (as "world primitives" do Horizon), sem shader nem textura, com
  orçamento por qualidade: **Baixa (padrão, para celular) 180 caixas**, Média
  600, Alta 1600. Tempestades distantes viram "impostores" (mesma direção e
  tamanho aparente, mais perto) e caixas que envolvem a câmera não são
  desenhadas (evita blend de tela cheia, caro em GPU de celular). O resto é
  neblina e cor do céu.
* **Radar barato.** Uma varredura de 32×32 (Baixa) a cada 2 s; no HUD, cada
  linha vira poucos retângulos (cores iguais são unidas).
* **Dano com orçamento.** O caminho do tornado é dividido em ladrilhos de
  13×13; cada ladrilho recebe as edições de cada nível de força uma vez só, em
  lote (`damage_budget`, padrão 1200 colunas/s), filtradas por tags — o
  servidor nem precisa ler bloco nenhum.

Medido no PC de desenvolvimento (release): listar as tempestades perto de um
ponto ~0,13 ms; uma varredura de radar 48×48 de velocidade ~0,09 ms.

## Instalar

O Tempest é um *trait mod* nativo (docs do Horizon: `docs/native-mods.md`,
seção "Trait mods"). Ele precisa de um Horizon recente (que tenha os ganchos
`world_primitives`, `camera_shake`, `shader_params`, `compute_fog_color` e
`render_fog` no `mod_api`).

O arquivo pronto é `dist/tempest-0.3.0.hnmod`, fixado no commit
`d158e4473623` do Horizon (o mesmo em que foi checado) e aceito pelo
verificador oficial (`cargo horizon-mod check`). Instale-o pela tela de mods
do launcher, ou compile a pasta direto:

```sh
horizon-launcher native-build caminho/para/horizon-tempest --instance <sua instância> --trust-native-code
```

ou instale a pasta pela tela de mods do launcher. Como todo mod nativo do
Horizon, ele roda sem sandbox: leia o código antes (é pequeno).

Para o **dano, raios e sincronização**, o mod precisa estar no servidor (no
singleplayer, o próprio jogo). Num servidor sem o Tempest, o cliente ainda
mostra tempestades locais só visuais (`fallback = true`).

### No celular (ports Android do Horizon)

Os ports Android não oficiais do Horizon (ex.: "Horizon Android 0.7.x")
**não compilam mods nativos** (`.hnmod`): eles rodam só `.hmod` com o mod em
WebAssembly, numa sandbox (API `horizon:mods` 0.1). Para eles existe uma
versão própria: **`dist/tempest-mobile-0.3.1.hmod`** (pasta `mobile/`).

Instalar: **Installed → Mods → Import**, escolha o `.hmod` e ligue o mod na
lista (mods começam desligados).

Ela usa a mesma simulação, mas a API WebAssembly não deixa desenhar no mundo
nem mexer na neblina, então muda o seguinte:

| | Versão nativa (PC) | Versão mobile (`.hmod`) |
|---|---|---|
| Radar, painel de leitura, faixa de alerta | sim | sim |
| Tremor de câmera | sim | sim |
| Nuvens e funil desenhados com caixas | sim | não; o funil e os detritos são partículas (`/particle`) |
| Neblina e céu escurecendo | sim | não (a chuva/trovoada vanilla ajuda) |
| Dano, raios, chuva vanilla, neve, sons, sirene | sim | sim, por comandos (`/fill`, `/summon`, `/weather`, `/playsound`) |
| Mobs e itens puxados | sim | sim (`/tp` em espiral), e blocos arrancados voam como detritos |
| Jogador empurrado pelo vento | sim | só levitação no núcleo do tornado |
| Comandos | `/tempest` (servidor) e `/radar` (cliente) | tudo em `/tempest` |

**Os efeitos no mundo precisam de cheats**, porque são comandos rodados
como você. Ative "Permitir cheats" no mundo (ou abra em LAN com cheats).
Sem cheats, radar, painel, alertas e tremor continuam funcionando.

Comandos da versão mobile: `/tempest here [EF]` (tornado em cima de você na hora), `test` (mostra no chat se os comandos do mod funcionam), `debug`, `spawn <tipo> [EF]`, `list`, `info`,
`clear`, `radar`, `vel`, `ref`, `range <m>`, `quality <low|medium|high>`,
`set <chave> <valor>`, `settings`. Chaves: `quality`, `radar`, `velocity`,
`info`, `radar_range`, `particles`, `sounds`, `sirens`, `shake`, `damage`,
`lightning`, `vanilla_weather`, `snow`, `hurt`, `entity_push`, `storm_rate`,
`tornado_rate`, `seed`, `year_days`. Na qualidade Baixa (padrão) o mod roda
no máximo 14 comandos por tick.

`mobile/wit/horizon.wit` é a definição da API de mods do Horizon (licença MIT,
© Ludovic Marie e contribuidores do Horizon), copiada sem alteração.

O port dá a cada mod um limite de 50 milhões de instruções por tick e
desliga o mod que passa disso. O Tempest divide o trabalho entre ticks, e
`tools/build-mobile.sh` roda o componente no wasmtime com esse limite
(`mobile/tools/fuelcheck`) e falha se algum tick passar de 60%.

Gerar o arquivo: `tools/build-mobile.sh` (precisa de
`rustup target add wasm32-unknown-unknown`).

## Comandos

Servidor (`/tempest`, os que mudam o mundo pedem operador nível 2):

| Comando | |
|---|---|
| `/tempest spawn <tipo> [EF]` | Cria uma tempestade que chega até você: `cell`, `supercell`, `tornado` (supercélula com tornado, EF padrão 2), `squall`, `hurricane`, `blizzard`. Ex.: `/tempest spawn tornado 4` |
| `/tempest list` | Tempestades por perto: tipo, distância, direção, intensidade, tornado |
| `/tempest info` | Ambiente onde você está (T, Td, CAPE, cisalhamento, SRH, STP, risco, estação) |
| `/tempest clear` | Some com as tempestades atuais |
| `/tempest set <chave> <valor>` | Muda uma configuração do servidor (abaixo) |
| `/tempest reload` | Relê `server.cfg` |

Cliente (`/radar`, funciona em qualquer servidor):

| Comando | |
|---|---|
| `/radar` | Liga/desliga o radar |
| `/radar vel` / `/radar ref` | Velocidade radial / refletividade |
| `/radar range <metros>` | Alcance (500–20000) |
| `/radar quality <low\|medium\|high>` | Qualidade gráfica |
| `/radar info` | Liga/desliga o painel de leitura |
| `/radar set <chave> <valor>` | Qualquer chave do `tempest-client.cfg` |

## Configuração

`tempest-client.cfg` (pasta de configuração do jogo): `quality`, `radar`,
`info`, `velocity`, `radar_range`, `clouds`, `fog`, `sounds`, `sirens`,
`shake`, `push`, `fallback`, `seed`, `year_days`.

`<mundo>/tempest/server.cfg`: `storm_rate` (multiplica a frequência; 0 = só
por comando), `tornado_rate`, `damage`, `damage_budget` (abaixe em aparelho
fraco), `hurricane_damage`, `lightning`, `vanilla_weather`, `snow`,
`player_damage`, `entity_push`, `sync_interval`, `sync_radius`, `seed`,
`year_days`, e `natural_cells` / `natural_supercells` / `natural_squalls` /
`natural_hurricanes` / `natural_blizzards`.

As tempestades criadas por comando ficam salvas em `<mundo>/tempest/storms.bin`.

## Para shader packs

Com um shader pack do Horizon ligado, o Tempest preenche `frame.mods`:

| Slot | x | y | z | w |
|---|---|---|---|---|
| 0 | tornado mais próximo: x relativo à câmera | 0 | z relativo | raio do núcleo |
| 1 | vento máximo (m/s) | EF atual | fração da vida | distância |
| 2 | dBZ no jogador | escuridão (0–1) | vento (m/s) | clarão de raio |
| 3 | vento x | vento z | corrente ascendente | tipo de precipitação |

Um pack pode usar isso para nuvens volumétricas de verdade em PC.

## Desenvolvimento

```
horizon-tempest/
  horizon-mixin.toml      # o bundle: [mod] e [api]
  api/src/sim/            # o tempo em si: Rust puro, sem dependências, testado
  api/src/server/         # metade do servidor (dano, raios, chuva, sync, comandos)
  api/src/client/         # metade do cliente (nuvens, HUD/radar, neblina, sons)
  api/src/tests.rs        # servidor e cliente dirigidos por um jogo falso
  devstub/horizon-client/ # stub da parte do mod_api que o Tempest usa
  tools/check.sh          # cargo test / clippy contra o stub
  tools/check-horizon.sh  # cargo check / clippy contra um clone do Horizon real
  tools/pack.py           # empacota o .hnmod em dist/
  tools/build-mobile.sh   # gera o .hmod WebAssembly para os ports Android
  mobile/                 # a versão WebAssembly (cliente), mesma simulação
```

Duas checagens:

* `tools/check.sh` compila e testa o mod contra `devstub/horizon-client`, uma
  cópia mínima das assinaturas do `mod_api` que o Tempest usa, sem compilar o
  jogo. É rápido e é onde rodam os 30 testes (inclusive servidor e cliente
  dirigidos por um jogo falso). Os testes usam construtores que só existem no
  stub, então eles rodam só aí.
* `tools/check-horizon.sh <clone do horizon>` coloca o mod no workspace do
  Horizon real, como o launcher faz, e roda `cargo check` (ou `clippy`).
  **Passou sem erros nem avisos** (check e clippy) no commit
  `d158e4473623` do Horizon. Áudio e controles ficam desligados
  nessa checagem porque precisam de libasound/libudev e não mexem na API de mods.

Ainda não foi testado: o executável gerado pelo launcher rodando com o mod (o
`native-build`) e o comportamento dentro do jogo.

Licença: MIT (este mod). O resto do repositório segue a licença dele.
