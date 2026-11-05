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
| Dano de vento / "scouring" | Folhas, vidro, portas, telhados, troncos, grama raspada até terra/terra grossa, conforme a escala EF |
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
* **Dano com orçamento.** Edições de bloco em lote duas vezes por segundo, por
  colunas (`damage_budget`, padrão 120 colunas/s), filtradas por tags — o
  servidor nem precisa ler bloco nenhum.

Medido no PC de desenvolvimento (release): listar as tempestades perto de um
ponto ~0,13 ms; uma varredura de radar 48×48 de velocidade ~0,09 ms.

## Instalar

O Tempest é um *trait mod* nativo (docs do Horizon: `docs/native-mods.md`,
seção "Trait mods"). Ele precisa de um Horizon recente (que tenha os ganchos
`world_primitives`, `camera_shake`, `shader_params`, `compute_fog_color` e
`render_fog` no `mod_api`).

```sh
horizon-launcher native-build caminho/para/horizon-tempest --instance <sua instância> --trust-native-code
```

ou instale a pasta pela tela de mods do launcher. Como todo mod nativo do
Horizon, ele roda sem sandbox: leia o código antes (é pequeno).

Para o **dano, raios e sincronização**, o mod precisa estar no servidor (no
singleplayer, o próprio jogo). Num servidor sem o Tempest, o cliente ainda
mostra tempestades locais só visuais (`fallback = true`).

### Sobre celular

Até hoje o Horizon **não tem build oficial para Android** (o roadmap dele trata
isso como objetivo de longo prazo). O Tempest já vem ajustado para hardware
fraco e não usa nada específico de desktop, então deve funcionar quando houver
um build mobile do Horizon — mas isso não pôde ser testado.

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
```

`tools/check.sh` compila e testa o mod contra `devstub/horizon-client`, uma
cópia mínima das assinaturas do `mod_api` (tiradas do código-fonte do Horizon:
`native_hooks.rs`, `native-hooks.json` e as regras do gerador
`horizon-mod-api-gen`), sem precisar compilar o jogo inteiro. **O build final
contra o Horizon real é feito pelo launcher e ainda não foi testado aqui**; se
alguma assinatura tiver mudado no Horizon, o erro vai apontar para
`api/src/lib.rs`, `server/mod.rs` ou `client/`.

Licença: MIT (este mod). O resto do repositório segue a licença dele.
