# Modelo legal

> Isto não é aconselhamento jurídico. As leis variam por país, e projetos
> legais ainda podem receber notificações de remoção. Se você pretende
> distribuir algo em larga escala ou monetizar, consulte um advogado.

## O princípio

O projeto distribui **apenas código próprio e bibliotecas de código aberto**.
Todo o conteúdo protegido do jogo (código de máquina, modelos, texturas,
músicas, textos) chega ao aparelho **pela ROM do próprio usuário** e não sai
de lá.

## O que está (e o que não está) em cada lugar

| Item | Repositório | APK | Celular do usuário |
|---|:---:|:---:|:---:|
| Código deste projeto (C++/Kotlin) | ✅ | ✅ | ✅ |
| N64Recomp, sljit e outras libs (código aberto) | ✅ submódulo | ✅ | ✅ |
| Hashes SHA-1 das versões do jogo | ✅ | ✅ | ✅ |
| ROM | ❌ | ❌ | ✅ (armazenamento privado do app) |
| Tabela de símbolos (nomes/endereços/tamanhos de funções) | ❌ | ❌ por padrão | ✅ importada pelo usuário |
| Código nativo recompilado | ❌ | ❌ | ✅ só na RAM, nunca em disco |
| Assets (gráficos, sons, textos) | ❌ | ❌ | ✅ lidos direto da ROM |

Proteções técnicas que mantêm isso:

- `.gitignore` bloqueia `*.z64`, `*.n64`, `*.v64`, `baserom*` e as pastas geradas.
- `android/app/build.gradle.kts` ignora esses formatos em `assets/`, então
  uma ROM colocada lá por engano não entra no APK.
- O app não declara a permissão `INTERNET` e desliga o backup automático
  (`allowBackup="false"`), então a ROM não vai para a nuvem.
- A recompilação acontece a cada abertura e o resultado não é salvo.

## A ROM

- **Faça o dump da sua própria cópia.** Há leitores USB de cartucho de N64
  à venda para isso. No Brasil, a Lei de Software
  (Lei 9.609/1998, art. 6º, I) permite uma cópia de salvaguarda de um
  programa adquirido legitimamente. Em outros países a regra muda, e nos EUA a
  questão é mais disputada.
- **Não baixe ROMs.** Baixar uma ROM de um site é cópia não autorizada, mesmo
  que você tenha o cartucho, e este projeto não ajuda nisso.
- O app recusa ROMs que não sejam as versões conhecidas. Isso não é DRM, só
  garante que a tabela de símbolos casa com o código.

## A tabela de símbolos

É o único ponto que exige julgamento. Ela lista **nomes, endereços e tamanhos**
de funções: metadado factual, sem nenhuma instrução do jogo. O Zelda 64:
Recompiled publica arquivos equivalentes (repositório Zelda64RecompSyms). Mesmo assim,
este projeto adota a postura mais conservadora:

- a tabela **não é versionada** aqui;
- ela é **gerada por você**, a partir da decompilação comunitária
  (`n64decomp/sm64`), compilada com a sua ROM;
- o APK distribuído **não a contém**; você a importa no app.

Se você quiser um APK de uso pessoal com a tabela embutida (copiando-a para
`android/app/src/main/assets/syms/`), tudo bem, mas **não distribua esse APK**.

## Código derivado

O código nativo gerado a partir da ROM é uma tradução do código do jogo, ou
seja, é derivado dele. Por isso ele:

- é gerado só no aparelho de quem tem a ROM;
- vive só na memória durante a execução;
- nunca é gravado, compartilhado ou enviado.

Isso é o que separa este projeto de um port que distribui um executável já
recompilado.

## Marca e identidade visual

- O app se chama "M64 Mobile Port" e tem ícone próprio. Não use "Super Mario",
  "Nintendo", logos ou personagens no nome, no ícone, em capturas de loja ou
  em material de divulgação.
- Não publique em lojas de apps com referência ao jogo. Lojas tendem a remover
  esse tipo de app, mesmo legal, por denúncia de marca.
- Não aceite doações vinculadas ao jogo. Monetização é o que costuma
  transformar um projeto tolerado em alvo.

## Licenças de terceiros

- N64Recomp: MIT.
- N64ModernRuntime (a integrar): **GPL-3.0**. Quando ele for incorporado, o
  app como um todo precisará ser distribuído sob uma licença compatível com a
  GPLv3, com código-fonte disponível.
- RT64 (a integrar): MIT.

Ainda é preciso escolher a licença deste projeto. Se o plano é usar o
N64ModernRuntime, GPL-3.0 é a escolha natural.
