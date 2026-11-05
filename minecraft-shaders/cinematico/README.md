# Cinematico: shader pack para Minecraft: Rust Edition (Horizon)

Shader pack no formato **nativo do Horizon** (`horizon-shaders.toml` + WGSL, API de shader 0.1).
Visual cinematográfico e realista, com opções para rodar do celular ao PC.

## O que ele faz

**Luz e sombra**
- Sombras reais do sol (e da lua à noite), com penumbra suave e filtro rotacionado sem serrilhado
- A luz que passa por água, vidro colorido e gelo ganha a cor deles
- Luz do céu realista: sombras azuladas de dia e um "bounce" quente do chão
- Tochas com queda de luz realista e **luz colorida**: alma azul, redstone vermelha, lava
  laranja, ametista roxa, froglights, tochas de cobre verdes, vaga-lumes
- Exposição automática: os olhos se adaptam em cavernas e à noite
- Folhas e plantas translúcidas: brilham quando você olha contra o sol

**Céu**
- Atmosfera com espalhamento Rayleigh/Mie: azul profundo no zênite, horizonte claro,
  pôr do sol laranja e rosa com zênite arroxeado, noite azul-escura
- Sol redondo com escurecimento de borda e halo (a lua, as estrelas e as nuvens são do jogo)
- Neblina atmosférica: montanhas distantes azuladas, névoa no amanhecer e no fim de tarde,
  céu cinza e pesado na chuva

**Água**
- Reflexo real do mundo, com Fresnel (mais espelho de longe, transparente de perto)
- Refração: o fundo se deforma com as ondas
- Absorção pela profundidade (rasinho = cristalino, fundo = azul escuro) na cor da água do bioma
- Ondas de verdade na geometria e brilho do sol nas ondas
- Cáusticas: a rede de luz no fundo da água

**Mundo vivo**
- Vento nas folhas, na grama, nas flores, nas plantações e nas trepadeiras, mais forte em tempestades
- Chão molhado e poças na chuva, com reflexo do céu
- Relevo gerado das texturas (ou mapas LabPBR completos se você usar um resource pack PBR)
- Brilho especular em metais, gemas, vidro, gelo e pedras polidas

**Câmera de cinema**
- Bloom em duas camadas (halo próximo + halo largo)
- Raios de sol volumétricos (god rays) recortados por árvores e montanhas
- Profundidade de campo com foco automático e bokeh (opcional)
- Desfoque de movimento da câmera (opcional)
- 5 estilos de cor: Natural, Filme quente, Teal e laranja, Frio nórdico, Noir
- Contraste em S, vibrance, vinheta, grão de filme a 24 qps, aberração cromática,
  nitidez, FXAA e barras 2.39:1

## Como instalar

1. Abra o jogo e vá em **Opções > Horizon Settings > Shader Packs**.
2. Clique em **Open Pack Folder**: abre a pasta `shaderpacks/` (fica ao lado do `options.txt`).
3. Coloque lá **a pasta `cinematico` inteira** (ou o arquivo `cinematico-1.0.0.hshader`).
4. Volte à tela e clique em **Cinematico (Horizon)**. Clicar de novo desliga.
5. **Shader Options...** no topo da tela abre as opções em páginas: Luz e sombra, Água,
   Céu e vento, Câmera, Cor e filme, Desempenho.

O jogo confere o pack antes de carregar. Se algo falhar, o erro aparece no chat com arquivo e
linha, e a imagem do jogo continua normal. Enquanto o pack está ligado o jogo recarrega os
arquivos sozinho quando você salva: dá para editar e ver a mudança na hora.

Com a ferramenta oficial do Horizon (`crates/horizon-shader-api` no código-fonte) dá para
conferir e empacotar fora do jogo:

```sh
horizon-shader check cinematico     # o mesmo teste que o jogo faz
horizon-shader pack cinematico      # gera cinematico-1.0.0.hshader
```

### No Android (horizon-android)

O renderizador é o mesmo, então o pack funciona igual. Para não pesar:

- **Qualidade: Leve (celular)**
- **Desempenho > Resolução das sombras: 1024** e **Distância das sombras: 64**
- **Água > Reflexos na água: desligado** (é o que mais pesa: desenha o mundo duas vezes)
- Se ainda estiver lento, desligue **Sombras**. O céu, a água, o bloom e as cores continuam.

## Dicas para print/vídeo cinematográfico

- `F1` esconde a interface
- **Câmera > Profundidade de campo: ligado** e mire no que quer em foco
- **Cor e filme > Barras de cinema** e **Estilo de cor: Teal e laranja**
- O melhor horário é o pôr do sol (`/time set 12000`), com os raios de sol no máximo

## Arquivos

| Arquivo | Estágio |
|---|---|
| `horizon-shaders.toml` | manifesto: estágios, opções, materiais, luzes coloridas |
| `common.wgsl` | atmosfera, cor do sol, luz do céu, exposição e curva ACES (compartilhado) |
| `sky.wgsl` | céu e sol |
| `material.wgsl` | iluminação do terreno, sombras, PBR, chuva, água, neblina |
| `vertex.wgsl` | vento nas plantas e ondas |
| `weather.wgsl` | chuva e neve |
| `post/bloom_prefilter.wgsl`, `post/bloom_wide.wgsl` | bloom |
| `post/light_shafts.wgsl` | raios de sol |
| `post/lens.wgsl` | profundidade de campo e desfoque de movimento |
| `post/color.wgsl` | composição e color grading |
| `post/final.wgsl` | FXAA, nitidez, aberração, vinheta, grão, barras |

Requer Horizon com a API de shader 0.1 (Nightly de novembro de 2026 em diante: precisa do
`[[post]] output`, `[shadow] option` e `[[particle_lights]]`).
