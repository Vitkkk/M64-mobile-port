# Cinematico: shader pack para Minecraft: Rust Edition (Horizon)

Shader pack no formato **nativo do Horizon** (`horizon-shaders.toml` + WGSL, API de shader 0.1).
Visual cinematográfico e realista, com opções para rodar do celular ao PC.

## O que ele faz

**Luz e sombra**
- Sombras reais do sol (e da lua à noite), com penumbra suave e filtro rotacionado sem serrilhado
- A luz que passa por água, vidro colorido e gelo ganha a cor deles
- Luz do céu realista: sombras azuladas de dia e um "bounce" quente do chão
- Tochas com queda de luz realista e **luz colorida**: alma azul, redstone vermelha, lava
  laranja, ametista roxa, froglights, tochas de cobre verdes
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
- Ondas animadas na superfície e brilho do sol nelas
- Cáusticas: a rede de luz no fundo da água

**Superfícies**
- Chão molhado e poças na chuva, com reflexo do céu
- Brilho especular em metais, gemas, vidro, gelo e pedras polidas

**Câmera de cinema**
- Bloom gaussiano largo (dois passes, horizontal e vertical)
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
   Céu, Câmera, Cor e filme.

No **Horizon Android** dá também para importar pelo launcher: aba **Installed → Shaders →
Import**, escolhendo o `.hshader`.

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

- **Qualidade: Leve (celular)** ou **Médio**
- **Água > Reflexos na água: desligado** (é o que mais pesa: desenha o mundo duas vezes)
- **Luz e sombra > Sombras: desligado** se ainda estiver lento. O céu, a água, o bloom e as
  cores continuam.

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
| `weather.wgsl` | chuva e neve |
| `post/bloom_h.wgsl`, `post/bloom_v.wgsl` | bloom |
| `post/lens.wgsl` | profundidade de campo, desfoque de movimento, bloom e raios de sol |
| `post/color.wgsl` | balanço de branco e color grading |
| `post/final.wgsl` | FXAA, nitidez, aberração, vinheta, grão, barras |

## Compatibilidade

Feito para a API de shader 0.1 como ela estava no Horizon de 1º de novembro de 2026 (merge
`bfa86ce98`, o primeiro com `[[lights]]` e `[material] fog`). É essa a versão do motor do
**Horizon Android 0.7.7**, e o pack também funciona nas versões mais novas do Horizon.

O pack não usa o que veio depois dessa data, porque o Horizon Android 0.7.7 recusaria:
`[vertex]` (vento nas plantas e ondas na geometria), `[[particle_lights]]`, os alvos
`output`/`scale` dos passes de pós, os mapas LabPBR e as opções que desligam e dimensionam o
mapa de sombra.
