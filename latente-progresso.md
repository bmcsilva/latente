# LATENTE — Progresso

Registo do que está feito, verificado e pendente. Acompanha `latente-especificacao-tecnica.md`.

Actualizado: 2026-08-03

---

## Estado geral

| Fase | Descrição | Estado |
|---|---|---|
| **F0** | Sonda de capacidades | **CONCLUÍDA E VALIDADA NO DISPOSITIVO** |
| **F1** | Captura RAW crua → DNG | **CONCLUÍDA** — 8/8 experiências, DNG aberto no darktable, balanço de brancos corrigido (falta confirmar num disparo novo) |
| **F2** | Revelador | **FECHADA**: CPU e GPU concordam a 1 em 255 no telefone; cor validada, cartão de cinza a 0,0%. 132 testes |
| **F3** | Visor WYSIWYG | **FECHADA**: visor verificado (cor a 0,7%, orientação a +0,993) e disparo a partir dele com o frame certo, sem interromper o visor. 153 testes |
| F4 | Câmara (modos, fotómetro, ajudas) | **fotómetro do RAW e modos M/S/A/P verificados no telefone**: margem converge para +0,5 EV e fica quieta; visor vivo no escuro a 7,8 fps com o disparo a 1750 ms. 182 testes. Tecto de exposição passou a ser o medido. Faltam foco, Kelvin/tinta na UI e ajudas |
| F5 | Exportação e biblioteca | não começada |
| F6 | Verificação anti-mastigação | não começada |

**O projeto é viável.** Há duas objectivas traseiras utilizáveis e a ciência da cor está completa.
Há também dois limites duros que mudam o que a aplicação pode prometer — ver «As duas más notícias».

---

## As duas más notícias

Vale a pena tê-las à frente, porque são estruturais e nenhuma quantidade de código as resolve.

### 1. Tecto de exposição de 1/10 s

`SENSOR_INFO_EXPOSURE_TIME_RANGE` termina em **100 ms** em todas as seis câmaras, com
`MAX_FRAME_DURATION` a 142,9 ms. Não há exposições longas por Camera2 neste telefone.

Ser igual em todos os módulos aponta para **política do HAL**, não limite de sensor. Se a aplicação
do fabricante conseguir 30 s no modo Pro, é por um caminho privado que não está exposto a
terceiros. **A confirmar empiricamente em F1** — pedir 1 s e ler o que o `CaptureResult` devolve.

Consequência: nada de rastos de luz, nada de céu nocturno, nada de água em seda. Fotografia à mão,
sempre.

### 2. RAW de 10 bits, não 16

`SENSOR_INFO_WHITE_LEVEL = 1023`. O formato `RAW_SENSOR` é um contentor de 16 bits, mas o sensor
entrega **10 bits úteis**: tecto teórico de ~10 stops, realisticamente 8 a 9.

É esta a razão física pela qual os *pipelines* computacionais existem. Um único frame de 10 bits
não tem alcance dinâmico para uma cena de contraste alto — vai cortar as altas luzes ou perder as
sombras, e é uma escolha tua qual das duas.

Convém ser claro sobre o que se ganha em troca: **fidelidade e controlo, não alcance dinâmico.**
Se um dia quiseres mais alcance, a via honesta é fazer tu o *bracketing* — vários disparos com
exposições que tu escolhes, revelados separadamente — e não uma fusão que alguém decidiu por ti.

---

## F0 — Sonda de capacidades

### O que faz

Percorre todas as câmaras, **incluindo as físicas escondidas dentro das lógicas** (que não aparecem
em `cameraIdList`), e dá um veredicto por câmara mais oito secções de detalhe: Streams, Sensor,
Exposição, Óptica, Processamento, Cor, Chaves declaradas, e um Resumo. Tem ainda um teste de
abertura separado, que descobre quais os módulos que uma aplicação de terceiros consegue mesmo
abrir.

Saída em `.txt` para ler e `.json` para processar, em `Downloads/Latente/`.

### Ficheiros

```
probe/
  settings.gradle.kts            AGP 8.13.2 · Kotlin 2.2.21
  build.gradle.kts
  gradle.properties
  local.properties               sdk.dir desta máquina (não versionar)
  gradlew + gradle/wrapper/      Gradle 8.14.3
  app/build.gradle.kts           compileSdk 36 · minSdk 31 · sem dependências de runtime
  app/src/main/AndroidManifest.xml
  app/src/main/kotlin/io/github/bmcsilva/latente/probe/
    CapabilityProbe.kt           leitura de características, veredicto, teste de abertura
    ProbeModel.kt                árvore de resultados + escritores JSON e texto
    ReportWriter.kt              escrita em Downloads/Latente via MediaStore
    MainActivity.kt              UI mínima em Views puros
  app/src/test/kotlin/io/github/bmcsilva/latente/probe/
    ReportFormatTest.kt          9 testes JVM aos escritores de relatório
```

### Verificação

| O que | Resultado |
|---|---|
| `:app:assembleDebug` | BUILD SUCCESSFUL, zero avisos do compilador |
| APK de depuração | 2,4 MB |
| Testes unitários JVM | 9 testes, 0 falhas |
| **Execução em samsung SM-S942B** | **relatório completo obtido, incluindo teste de abertura** |

### Como compilar e instalar

```bash
cd ~/Workspace/Projects/latente/probe
export JAVA_HOME=/home/bruno/Android/Jdk/jdk-21.0.11+10
export ANDROID_HOME=/home/bruno/Android/Sdk
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Na aplicação: **Correr sonda** → **Testar abertura** → **Guardar**. Correr o teste de abertura
antes de guardar, para entrar no mesmo relatório.

---

## Resultados: samsung SM-S942B

`m1s` · `s5e9965` · Android 16 · API 36 · lido em 2026-08-03 09:39

Os valores estão agora na §2.4 da especificação, que passa a ser a fonte de verdade. Aqui fica a
leitura.

### Objectivas

| Câmara | Equivalente | Abertura | Foco | RAW | Veredicto |
|---|---|---|---|---|---|
| id 0 (= física 5) | 23 mm | f/1,8 | 10 cm–∞, OIS | 12,5 MP | **serve** |
| id 2 | 14 mm | f/2,2 | fixo | 12,0 MP | **serve, com reservas** |
| id 6 | 66 mm | f/2,4 | 50 cm–∞ | 10,0 MP | **não serve** |
| id 1 | 23 mm frontal | f/2,2 | 20 cm–∞ | 12,0 MP | serve |
| id 3 | 30 mm frontal | f/2,2 | 20 cm–∞ | 7,0 MP | serve |

A teleobjectiva está fora por duas razões independentes: não tem `MANUAL_SENSOR` nem a chave
`SENSOR_EXPOSURE_TIME` — ou seja, não há exposição manual — e o seu id não abre a partir de uma
aplicação de terceiros.

### Abertura das câmaras

```
id 0 : ABERTA        id 1 : ABERTA        id 3 : ABERTA
id 2 : ABERTA
id 5 : recusada — CAMERA_DISCONNECTED, "No camera device with ID 5 available"
id 6 : recusada — CAMERA_DISCONNECTED, "No camera device with ID 6 available"
```

As físicas 5 e 6 só existem dentro da lógica id 0. Como o id 0 tem características idênticas às do
id 5, a principal chega-se pelo id 0 e não se perde nada. A tele do id 6 é que fica inalcançável.

### O que é bom

- **Ciência da cor completa** nas cinco câmaras: dois iluminantes (D65 e STANDARD_A),
  `colorTransform`, `calibrationTransform` e `forwardMatrix` para ambos. O DNG sai com cor
  correcta sem perfil próprio.
- **`SENSOR_NOISE_PROFILE` presente** — vai para o DNG.
- **`CONTROL_ENABLE_ZSL` declarado** em todas — o ZSL pode ser desligado explicitamente.
- **`NOISE_REDUCTION_MODE` e `EDGE_MODE` com OFF disponível** na principal e nas frontais.
- **Nível FULL** na principal e nas frontais; `READ_SENSOR_SETTINGS` em cinco das seis.
- **`TONEMAP_CURVE` presente** com 128 pontos — mais do que suficiente, e `TONEMAP_GAMMA` ausente
  não faz falta.
- **ISO base 25** na principal, o que é baixo e bom.
- Timestamps em `REALTIME`.

### O que é preciso ter em conta

- **RAW já não é virgem.** Nível de preto a zero (pedestal já subtraído) e
  `SENSOR_INFO_LENS_SHADING_APPLIED = true` (vinhetagem já corrigida), sem
  `STATISTICS_LENS_SHADING_CORRECTION_MAP` para saber o que foi feito. Continua a ser
  pré-*demosaico* e linear, mas há duas operações do HAL lá dentro. **A interface tem de dizer
  isto** — é exactamente o tipo de meia-verdade que este projeto existe para não cometer.
- **Mosaico GBRG na principal, RGGB nas outras.** O *demosaicing* tem de ler o CFA por câmara.
  Assumir RGGB dava cor trocada só na câmara mais importante.
- **ISO analógico até 640** (800 nas frontais). Acima disso são 2,3 stops de ganho digital, que
  amplificam ruído e sinal na mesma proporção. O tecto útil de ISO é 640, e a UI deve mostrá-lo.
- **Uma só saída RAW simultânea.** Sem *bracketing* por streams paralelos.
- **Sem RAW de resolução total.** `getHighResolutionOutputSizes()` vazio: 12,5 MP e ponto final.
- **`DISTORTION_CORRECTION_MODE` ausente** em todas. Não se pode desligar por essa via; o RAW não
  costuma vir distorcido, mas convém verificar.
- **`SENSOR_ROLLING_SHUTTER_SKEW` ausente.** A distorção de obturador electrónico existe; a
  medição dela não está disponível.

### Equivalência a 35 mm, para calibrar expectativas

A principal: sensor de 8,16 × 6,12 mm, factor de recorte ×4,24, **4,1 stops abaixo de full frame**.

- f/1,8 equivale a **f/7,6** em profundidade de campo e em luz recolhida;
- ISO 640 aqui recolhe tanta luz como **ISO ~11 000** numa A7 III, à mesma exposição.

Não é um defeito da aplicação nem do telefone — é área de sensor. Serve para saber o que esperar.

---

## Defeito encontrado e corrigido

**A sonda concluiu mal.** O critério de aptidão exigia `MANUAL_POST_PROCESSING` e nível `FULL`, e
por isso declarou «NÃO SERVE» à ultra-grande-angular do id 2. Está errado: essas duas capabilities
dizem respeito ao tonemap e à correcção de cor **do ISP**, que este projeto não usa — o revelador é
nosso e parte do RAW. O id 2 tem `MANUAL_SENSOR`, `RAW`, `SENSOR_EXPOSURE_TIME` e
`SENSOR_SENSITIVITY`: é utilizável.

O critério passou a separar bloqueante de desejável:

- **Bloqueante**: saída `RAW_SENSOR`, capability `RAW`, `MANUAL_SENSOR`, e as chaves
  `SENSOR_EXPOSURE_TIME` e `SENSOR_SENSITIVITY` declaradas.
- **Desejável, com reserva registada**: `READ_SENSOR_SETTINGS`, `MANUAL_POST_PROCESSING`, nível
  `FULL`, chaves `NOISE_REDUCTION_MODE` e `CONTROL_ENABLE_ZSL`.

O veredicto tem agora três estados: SERVE, SERVE COM RESERVAS, NÃO SERVE. A lógica ficou num só
sítio (`verdict()`), usada tanto pelo veredicto de cada câmara como pelo Resumo — antes estava
duplicada, que foi como as duas versões divergiram.

**Impacto no plano: o projeto tem duas objectivas traseiras, não uma.** 23 mm e 14 mm.

### Outras melhorias na sonda, na mesma passagem

- bits úteis por pixel, derivados de `SENSOR_INFO_WHITE_LEVEL`, com aviso abaixo de 12 bits;
- aviso quando o padrão de nível de preto é todo zero, para não se subtrair duas vezes;
- aviso quando o tecto de exposição fica abaixo de 1 s;
- Resumo separa traseiras de frontais, indica abertura, foco fixo, reservas, e lista as recusadas
  com o motivo.

---

## F2 — Revelador

### Porte para Kotlin: `render/ColorScience.kt`

Primeira peça do revelador portada da referência em Python, e **testável na JVM sem GPU**. Contém a
álgebra 3×3 (produto, inversa, matriz-vector), a adaptação de Bradford, a interpolação de matrizes
calibradas em 1/T, a cadeia completa câmara → XYZ D50 → D65 → sRGB ou Display P3, os ganhos de
balanço, a codificação sRGB e o *rolloff* de Reinhard.

**19 testes** fixam-na aos valores da referência, usando as matrizes reais do dispositivo:

| Teste | O que prova |
|---|---|
| `forwardMatrixMapsNeutralToD50` | somando as linhas da FM dá o branco D50 — confirma a convenção da especificação e a necessidade da adaptação |
| `interpolationAt5500MatchesThePythonReference` | a interpolação bate com o Python a 1e-8 |
| `theFullChainMatchesThePythonReference` | a cadeia completa bate a 1e-6 |
| `oneWholeConversionMatchesThePythonReference` | uma conversão ponta a ponta, do mosaico ao sRGB linear |
| `theSceneNeutralComesOutNeutral` | um pixel igual ao `AsShotNeutral` sai com R = G = B: é o cartão de cinza da §9 em forma de teste |
| `interpolationInOneOverTemperatureIsNotLinearInTemperature` | apanha o erro de interpolar em T em vez de 1/T |
| `bradfordMovesD50WhiteOntoD65White` | a adaptação faz o que diz |
| `inverseTimesOriginalIsIdentity`, `singularMatrixHasNoInverse` | a álgebra |

Uma divergência interessante e tranquilizadora: a cadeia completa concorda com o Python a **2×10⁻⁷**,
não exactamente. A referência usa a matriz de Bradford D50→D65 **publicada** e a `ColorScience`
**deriva-a** dos pontos brancos. Duas vias independentes a sete casas decimais.

E o porte permitiu tirar duplicação: a `WhiteBalance` tinha a sua própria tabela de iluminantes, o
seu produto matriz-vector e a sua interpolação. Passou a delegar na `ColorScience`, e agora as duas
não podem divergir.

### Estratégia: validar antes de portar

O pipeline foi implementado **primeiro em Python**, em `tools/develop.py`, com a matemática
idêntica à do shader. A razão é simples: o critério de aceitação da F2 (§9) é a **cor concordar**,
não a velocidade, e no GLSL não há como inspeccionar valores intermédios. Valida-se onde se pode
medir e só depois se porta.

Trabalha em resolução reduzida por *binning* — para validar cor a resolução é irrelevante, e sem
numpy o preço por pixel é alto. 510×382 em 1,8 s.

### O que se descobriu

**1. A revelação por omissão do darktable não serve de referência.** Aplica o fluxo cena-referido
com filmic ou sigmoid e `color balance rgb` — mexe na cromaticidade. Com
`--conf plugins/darkroom/workflow=none` obtém-se uma revelação neutra: só rawprepare, demosaico,
balanço, perfil de entrada, perfil de saída e gama.

Só esta correcção levou o desvio em R/G de **8,8% para 1,0%**. Uma referência injusta parece um bug
no nosso código.

**2. A adaptação de Bradford é obrigatória e está certa.** A `ForwardMatrix` entrega XYZ com ponto
branco D50 — confirma-se somando as linhas da FM1 deste ficheiro: 0,9639 · 1,0000 · 0,8242, que é
exactamente o branco D50. Sem a adaptação D50 → D65 antes do sRGB, o erro salta para 15,9% e 34,4%.

**3. `ForwardMatrix` e `ColorMatrix` são convenções diferentes, e ambas legítimas.**

| Caminho | R/G | B/G |
|---|---|---|
| `ForwardMatrix` → XYZ D50 → Bradford → sRGB | **1,0%** | 7,0% |
| `inv(ColorMatrix)` → adaptação do branco da cena → sRGB | 1,5% | **2,5%** |

O darktable usa a `ColorMatrix` invertida, ao estilo do dcraw. A especificação DNG **prefere a
`ForwardMatrix`** quando existe: mapeia directamente para o espaço de conexão, dispensa inverter uma
matriz e dispensa adivinhar a adaptação. É o que o Adobe Camera Raw faz.

Os 7% de diferença no azul **não são um erro**: são a distância entre duas convenções respeitadas,
e manifestam-se no canal pior condicionado.

**Decisão: fica a `ForwardMatrix`.** É a que a especificação do formato prefere e a melhor
condicionada. Fica registado que uma revelação em darktable vai divergir uns pontos percentuais no
azul, e que isso é esperado.

**4. O *rolloff* não explicava a diferença.** Testado: com `rolloff = 1,0`, que reduz a fórmula de
Reinhard à identidade, o azul até piora ligeiramente (7,8%). Descartado.

### Validação em chapa plana

Três chapas — superfícies uniformes a preencher o quadro — permitiram fechar o que faltava. Numa
chapa plana as 432 amostras são todas utilizáveis, sem textura nem desalinhamento a introduzir
ruído: é a comparação de cor mais limpa possível.

| Caminho | R/G | B/G |
|---|---|---|
| `ForwardMatrix` | 0,7% | 5,3% |
| `inv(ColorMatrix)` | 0,8% | **1,1%** |

**A ciência da cor está validada.** O resíduo no azul da `ForwardMatrix` medido em dois assuntos
diferentes — 7,0% na cena com objectos, 5,3% na chapa — é consistente, e é a diferença de
convenção, não erro.

### O `WhiteBalance.kt` está verificado por implementação independente

O Kotlin do telefone calculou o ponto neutro para 5500 K e escreveu-o no DNG. O Python recalculou-o
aqui do zero, pelo mesmo caminho matemático mas em código separado:

| | Ponto neutro |
|---|---|
| escrito pelo telefone (Kotlin) | 0,4873 · 1,0000 · 0,5938 |
| recalculado no PC (Python) | 0,4876 · 1,0000 · 0,5936 |
| diferença máxima | **0,07%** |

Igual nas três chapas. Duas implementações independentes a concordar a sete centésimas de por
cento é a melhor prova que se consegue sem um espectrofotómetro.

### A vinhetagem: o RAW não está corrigido, ao contrário do que o ficheiro declara

Com papel vegetal colado à lente e a lâmpada em frente, as três chapas deram **0,215 exactamente**
nas três, com a assimetria a cair de 55% para 18%. Repetibilidade dessas indica medição estável.

E depois o teste que não deixa fugir:

| Medição | Cantos / centro |
|---|---|
| mosaico cru, lido directamente do DNG | **0,215** |
| revelação neutra do darktable | **0,220** |

**A queda atravessa o pipeline inteiro sem ninguém a corrigir.** Isto contradiz duas declarações do
próprio ficheiro:

- `SENSOR_INFO_LENS_SHADING_APPLIED = true`, que diz que o HAL já corrigiu a vinhetagem;
- os quatro opcodes `GainMap` do DNG, que são identidade em toda a malha, e por isso dizem a
  qualquer revelador que não há nada a corrigir.

O perfil radial dá 0,82 vezes a lei natural cos⁴θ — mais acentuado do que a vinhetagem natural.
Mas **não consigo separar a óptica da montagem**: sobra um gradiente de iluminação de 24% entre
pares simétricos, e a magnitude é fiável enquanto a atribuição não é. A ferramenta passou a dizer
exactamente isso em vez de concluir de mais; o meu primeiro veredicto automático foi confiante
demais porque o desvio padrão global disfarçava o gradiente sistemático.

**Consequência para o produto, e é grande:** se o RAW não vem corrigido, o revelador **tem de
aplicar a correcção de shading**, e a §6.1 não tem esse passo. Fotografias com cantos a 20% do
centro não são «fidelidade», são um defeito.

**A via dos metadados está fechada.** A experiência 9 leu o
`STATISTICS_LENS_SHADING_CORRECTION_MAP`: malha de 17×13, quatro canais, e **exactamente 1,0000 em
todas as posições**. É essa exactidão que o denuncia — um mapa medido em laboratório teria
resíduos de 0,998 ou 1,003. Um 1,0000 perfeito é um *stub*: o HAL preenche a estrutura sem expor o
mapa verdadeiro.

O HAL é internamente coerente — declara shading aplicado e mapa identidade — mas não é informativo,
e a medição contradiz a declaração.

### O método que resolve: par rodado 180°

`tools/flatpair.py`. A vinhetagem está presa ao sensor; um gradiente de luz está preso à cena.
Rodando o telefone 180° sobre o eixo da objectiva:

```
chapa A:  I_A(x,y) = V(x,y) · S(x,y)
chapa B:  I_B(x,y) = V(x,y) · S(−x,−y)
√(I_A·I_B) = V(x,y) · √(S(x,y)·S(−x,−y)) ≈ V(x,y) · S₀
```

Um gradiente linear cancela-se até segunda ordem, porque `(1+gx)(1−gx) = 1−g²x²`. Não é preciso
rodar as imagens: basta multiplicá-las na mesma posição do sensor.

O que este método **não** corrige é uma queda radial da própria montagem. Um difusor colado à lente
não alimenta os ângulos extremos do campo, e essa falha é centro-simétrica como a vinhetagem — é
por isso que o papel vegetal não chega.

Corrido nas três chapas da T4, o tool detectou que estavam todas na mesma orientação (0° e 180° a
dar 0,302 contra 0,303) e recusou-se a concluir.

### RESOLVIDO: a vinhetagem da objectiva de 23 mm

Medida com **difusor encostado à lente, apontado a um ecrã branco iluminado**. Seis chapas válidas
de **duas sessões independentes**, em dias diferentes e com a montagem refeita entre elas.

| Raio | R | G | B | Ganho a aplicar |
|---|---|---|---|---|
| centro | 1,000 | 1,000 | 1,000 | 1,00× |
| 28% | 0,797 | 0,805 | 0,812 | 1,24× |
| 53% | 0,522 | 0,520 | 0,539 | 1,92× |
| 78% | 0,276 | 0,316 | 0,294 | 3,16× |
| **canto** | **0,166** | **0,193** | **0,181** | **5,18×** |

**A objectiva perde 80% da luz nos cantos, e o RAW não vem corrigido.**

Quatro razões para confiar nesta medição, ao contrário de todas as anteriores:

- **simetria de 0% a 6%** entre pares opostos, nas quatro chapas. A vinhetagem de uma lente é
  radialmente simétrica; um gradiente de iluminação não é. É este o teste que decide;
- **replicação independente**: a primeira sessão deu 0,196–0,198 no canto e razão 0,70 contra
  cos⁴θ; a segunda, no dia seguinte e com a montagem refeita, deu 0,193–0,199 e 0,69–0,71. Não é
  repetibilidade dentro da mesma montagem, é o mesmo número a sair duas vezes de montagens
  diferentes;
- **monotonia**: queda suave do centro ao canto, sem degraus;
- **física**: 0,70 vezes a lei natural cos⁴θ, ou seja há vinhetagem óptica a somar-se à
  geométrica — o esperado num campo de 74°, e inexplicável por iluminação.

O perfil está em `dados-de-teste/perfis/vinhetagem-id0.json`. É o **primeiro dado de calibração do
projeto: medido, não declarado.**

**Consequência para o pipeline:** a §6.1 não tem passo de correcção de shading e **tem de ter**.
Sem ele todas as fotografias saem com os cantos a 20%, e isso não é fidelidade, é um defeito. E como
o mapa do HAL é um *stub* de 1,0000, a correcção tem de vir de um perfil nosso, medido por modelo.

### Erro meu: a guarda rejeitava medições boas

A primeira validação exigia «pelo menos 60% dos píxeis perto do valor central». Está errada: se a
objectiva vinheta a 80%, mesmo uma chapa **perfeita** tem distribuição larga — as boas chapas da T7
davam 18% a 25% e foram rejeitadas por isso.

O critério certo é **simetria radial**, não largura da distribuição. Corrigido em
`tools/shading.py`: sem saturação, simetria entre pares opostos abaixo de 8%, e queda monótona.

### Falta medir as outras três objectivas

Tentativa nocturna com lâmpada e superfície: rejeitada em todas, com assimetrias de 18% a 97%.
A tentativa de a salvar com a média geométrica de pares deu 16% a 32% de dispersão e um canto/centro
de 0,073 — muito longe dos 0,197 validados, portanto dominado pela queda da lâmpada.

**O método é difusor + ecrã branco**, e faz-se em interior a qualquer hora. Dois minutos por
objectiva: difusor encostado à lente, apontar ao ecrã, **Objectiva ▸** para trocar, série de
exposições.

Na T8 o difusor não estava lá — via-se um ícone do ecrã na revelação do id 2, o que só é possível
sem difusor. Ecrã **com** difusor dá chapa válida; ecrã **sem** difusor mede a directividade do
LCD.

### Fontes que não servem de chapa plana, e porquê

Três tentativas, três rejeições — e cada uma ensinou uma coisa. A ferramenta passou a rejeitá-las
automaticamente, com duas guardas: mais de 2% de píxeis saturados, ou menos de 60% dos píxeis perto
do valor central.

| Fonte | Porque falhou |
|---|---|
| Superfície iluminada por lâmpada | assimetria de 18% a 55%: a luz vem de um lado. Só 23% dos píxeis perto do centro |
| Papel vegetal encostado à lente, lâmpada atrás | o difusor em contacto **não alimenta os ângulos extremos** do campo. A lâmpada é quase pontual, logo o difusor recebe luz de uma direcção só |
| Ecrã LCD branco a 10–15 cm | **um LCD não é lambertiano.** O brilho depende do ângulo de visão; a 74° de campo os cantos vêem o ecrã a ~40° fora do eixo. Medido: queda de 6× do centro à borda, contra 2,5× que a lei cos⁴θ prevê. Mede-se a directividade do monitor |

**O que serve:** difusor encostado à lente **mais** uma fonte extensa atrás — um ecrã branco
iluminado serve perfeitamente, e o céu também. A diferença em relação ao papel vegetal com lâmpada é
essencial: uma lâmpada é quase pontual e o difusor recebe luz de uma direcção só, ao passo que um
ecrã grande subtende um ângulo sólido largo e alimenta todos os ângulos do campo.

Nenhum dos dois chega sozinho: **ecrã sem difusor** mede a directividade do LCD, e **difusor sem
fonte extensa** não alimenta os ângulos extremos.

### Como medir uma chapa plana a sério

A razão cantos/centro deu 0,236, 0,243 e 0,078 — o que pareceria uma queda enorme. **Não é
vinhetagem.** Os cantos são assimétricos: esquerda 0,26–0,31 contra direita 0,17–0,19. A
vinhetagem de uma lente é radialmente simétrica; uma assimetria destas é **iluminação desigual**.
Na terceira chapa a queda para 0,078 mostra a luz quase pontual perto do centro.

Para medir vinhetagem a sério é preciso luz mesmo uniforme: uma parede iluminada de forma difusa,
fotografada de longe, ou uma caixa de luz. Fica por fazer — mas note-se que o
`SENSOR_INFO_LENS_SHADING_APPLIED` e o `GainMap` identidade já diziam que o HAL a corrige.

### Descoberta: falta um eixo de tinta ao balanço de brancos

Procurando o Kelvin que torna cada chapa neutra, o melhor ajuste dá **3450–3725 K** — mas com um
**resíduo de 7% a 11%**. Um resíduo destes significa que a luz **não está no locus de Planck**.
Iluminação LED de interior sai quase sempre fora do locus.

O `WhiteBalance` actual mapeia Kelvin para um ponto neutro **ao longo do locus**, e por isso nunca
consegue neutralizar uma luz que esteja ao lado dele. Qualquer revelador a sério oferece
**temperatura *e* tinta** (verde–magenta). É uma lacuna real do modelo, a resolver na F4 quando os
controlos forem do utilizador.

### O critério do cartão de cinza ainda não pôde correr

A §9 pede concordância "nos valores de um cartão de cinza". **Não há nenhuma amostra neutra nestas
fotografias**: escolheu-se 5500 K e a luz da cena é mais quente, portanto nem o cartão branco
renderiza neutro.

Alargando a janela de neutralidade para ±50%, as 93 amostras mais próximas do cinzento dão
`ForwardMatrix` 1,2% / 4,8% e `ColorMatrix` 1,5% / 2,2%. É bom sinal, mas não é o teste.

**Falta um disparo dedicado**: uma superfície neutra — folha branca, cartão cinzento — a preencher
o quadro, com a temperatura escolhida a bater com a luz.

### Ferramentas

| Ferramenta | O que faz |
|---|---|
| `tools/dngcheck.py` | lê um DNG sem dependências: tags, matrizes, opcodes, `GainMap`, estatísticas do mosaico |
| `tools/verify.py` | metadados + revelação independente no darktable + veredicto de balanço |
| `tools/develop.py` | o revelador de referência; `--matrix forward\|color`, `--bin`, `--ev`, `--rolloff`, `--kelvin` |
| `tools/compare.py` | compara a cromaticidade de duas revelações; é o critério de aceitação da F2 |

### A revelação na GPU, e o telefone a verificar-se a si próprio

O caminho do CPU está provado por testes na JVM. O shader não pode estar: não há como correr GLSL
sem GPU, nem como inspeccionar valores intermédios lá dentro. Restava a pergunta desconfortável de
como saber que o shader calcula o mesmo que o Kotlin.

A resposta foi **pôr os dois no telefone e fazê-los discordar**. O botão «Revelar · CPU vs GPU»
(`diag/GpuCheck.kt`) pega no DNG mais recente, revela-o pelos dois caminhos e mede a diferença
máxima e média por canal. O critério de aceitação da F2 passou a ser um botão, e não uma comparação
manual com o darktable.

Isto só funciona porque a matemática dos dois lados foi feita deliberadamente idêntica, ao ponto de
a **ordem das operações** ser a mesma. Logo qualquer diferença acima de 1 em 255 — que é o
arredondamento de oito bits — é bug de canalização e não dúvida sobre cor. O veredicto do relatório
diz exactamente isso.

#### O que se separou para poder ser testado sem GPU

A regra foi: o que é subtil não se faz onde não se pode medir.

| Onde | O que | Porquê ali |
|---|---|---|
| `GlUniforms` (CPU, testado) | interpolação de matrizes por temperatura, transposição para ordem de colunas, tabela de vinhetagem | faz-se uma vez por imagem, e é onde vivem os bugs de porte |
| `GlslSource` (GLSL) | níveis, vinhetagem, balanço, Malvar-He-Cutler, matriz, *rolloff*, codificação | faz-se uma vez por pixel |
| `GlDeveloper` (canalização) | EGL, texturas, compilação, FBO, leitura de volta | não tem aritmética nenhuma, de propósito |

#### Três armadilhas encontradas a escrever isto

**1. A ordem de interpolação da vinhetagem** — apanhada antes de morder. Guardar **ganhos** na
tabela e interpolá-los dá 4,696 onde o Kotlin dá 4,650: 1% de diferença, sistemática e crescente com
a curvatura. A tabela passou a guardar a **queda** medida, e o shader inverte só depois de
interpolar — igual ao `ShadingProfile.gain`. Se isto tivesse ficado errado, a comparação CPU/GPU
teria acusado uma discrepância de 1% que ninguém saberia atribuir.

**2. O `glUniform*` para uma localização inexistente não é erro de GL.** Passa em silêncio, e o
shader trabalha com zeros. Um nome mal escrito daria uma imagem errada sem uma única mensagem. Os
nomes ficaram numa lista só — `GlslSource.UNIFORM_NAMES` — que um teste na JVM confronta com o texto
dos shaders nos dois sentidos: nenhum nome da lista falta no shader, e nenhum uniforme do shader
falta na lista. Em execução, os que não resolverem vão para o relatório em vez de serem ignorados.

**3. As duas inversões do eixo Y cancelam-se.** O `glReadPixels` devolve as linhas de baixo para
cima, nas coordenadas do GL; o shader indexa o mosaico pelo `gl_FragCoord` sem inverter. As duas
juntas dão a ordem certa do ficheiro. Está comentado nos dois sítios porque inverter só num daria
uma imagem ao contrário — fácil de ver — mas inverter nos dois daria uma imagem certa por acaso, e
essa é a que sobrevive a uma revisão distraída.

E uma nota de indexação: os fragmentos usam `gl_FragCoord` em vez de coordenadas de textura
interpoladas. Num mosaico, o arredondamento de uma interpolação troca a paridade do CFA em píxeis
isolados — verde onde devia ser vermelho, no meio de uma imagem que no resto está certa.

#### Primeira corrida no telefone: morreu por falta de memória, e a conta explica-a

O CPU revelou os 12 Mpx em **11,2 s**. A GPU nunca chegou a correr:

```
ERRO na GPU : OutOfMemoryError: Failed to allocate a 49939219 byte allocation
              with 28848288 free bytes and 27MB until OOM,
              target footprint 268435456, growth limit 268435456
GPU         : ANGLE ((Samsung Xclipse 960) on Vulkan 1.4.304)
```

O número identifica o culpado sem margem para dúvida: 4080 × 3060 × 4 = **49 939 200**, que é a
cópia do resultado da GPU para `ByteArray`. E o tecto do monte é 256 MB.

A conta de quem estava a ocupar o quê, numa imagem de 12,48 Mpx:

| O quê | Tamanho | Porquê |
|---|---|---|
| mosaico em vírgula flutuante | 50 MB | 1 float por pixel |
| imagem revelada, linear | **150 MB** | 3 floats por pixel |
| `encode8` para comparar | 37 MB | 3 bytes por pixel |
| `encode16` para o TIFF | 75 MB | 6 bytes por pixel |
| leitura da GPU | 50 MB | RGBA de 8 bits |

Os 150 MB da imagem linear não são desperdício: **são o tamanho do dado**. O que estava errado era
tudo o resto coexistir com eles. Quatro correcções, por ordem de proveito:

1. **A GPU corre primeiro.** Antes de a imagem do CPU existir, o monte está livre. Não é uma
   optimização subtil: é a diferença entre funcionar e morrer.
2. **O `glReadPixels` devolve o `ByteBuffer` directo**, sem copiar para `ByteArray`. Um buffer
   directo vive em memória nativa, que é onde há espaço. Poupa 50 MB no monte.
3. **A comparação codifica amostra a amostra**, com `RawPipeline.encodeSample8`. Não se materializa a
   versão em oito bits do CPU. Poupa 37 MB — e ganha-se rigor de graça, porque agora compara-se
   contra a mesma função que o `encode8` usa, e não contra uma segunda cópia da codificação.
4. **O TIFF escreve-se da imagem linear**, codificando linha a linha. Poupa 75 MB. Há um teste que
   compara byte a byte os dois caminhos de escrita: uma optimização de memória que mudasse o ficheiro
   não seria optimização, seria bug.

E `android:largeHeap="true"` no manifest. É uma bandeira de que se abusa, mas um revelador de RAW é o
caso legítimo: o pico legítimo é 200 MB, contra um tecto de 256.

#### Corrigido ao mesmo tempo: o perfil de vinhetagem estava fixo no id 0

O botão escolhia sempre o perfil da objectiva principal. Numa foto da ultra-grande-angular isso
corrigiria a queda errada — **4,7× no canto contra 8,5×** — e o resultado pareceria plausível, que é
o pior tipo de erro. A objectiva passa a vir do sidecar do disparo.

Não há leitor de JSON para isto, é uma extracção dirigida de um campo; se o formato mudar, o campo
não é encontrado e **não se corrige nada**, com o relatório a dizê-lo. É a regra do projeto: sem
calibração, não se inventa.

#### Segunda corrida: CONCORDAM

```
GPU                    : ANGLE ((Samsung Xclipse 960) on Vulkan 1.4.304)
GPU ms                 : 292
CPU ms                 : 3802
GPU mais rápida        : 13.0×
diferença máxima       : 1
diferença média        : 0.0249
amostras acima de 1    : 0
percentagem acima de 1 : 0.0
pior em                : (14, 0) canal R
```

**O shader e o Kotlin calculam o mesmo.** Diferença máxima de 1 em 255 — arredondamento de oito bits
— e **nem uma única amostra** das 114 milhões acima disso. O critério de aceitação da F2 está
cumprido.

Duas notas de proveito:

- **292 ms para 12,48 Mpx** com Malvar-He-Cutler completo. Isto desbloqueia a F3 com folga enorme: um
  visor a resolução reduzida fica muito abaixo de um frame, e o princípio de que **o visor não mente**
  deixa de ter custo.
- O CPU passou de 11,2 s para 3,8 s, sem lhe tocar. Era pressão do recolector: o `largeHeap` e as
  alocações que desapareceram valeram 3× de velocidade de graça.

#### O TIFF, verificado fora do telefone

O ficheiro de 71 MB abre com perfil ICC de 2500 bytes, 4080×3060, e a imagem está **direita, sem
espelhamento e com cores plausíveis**. O `AsShotNeutral` no DNG é 0,4873 · 1,0000 · 0,5938, o mesmo
que o Kotlin tinha calculado.

Contra a referência independente em Python, sobre o mesmo DNG e com a mesma correcção de vinhetagem:

| | Mediana | p90 |
|---|---|---|
| desvio em R/G | **0,7%** | 5,0% |
| desvio em B/G | **2,3%** | 8,8% |
| quase-neutros (o cartão de cinza da §9) | **0,0%** | — |

O critério do cartão de cinza da §9, que **nunca tinha podido correr** por falta de uma amostra
neutra nas fotografias anteriores, passa agora com 7 amostras a 0,0%.

#### Descoberta: o darktable deixou de servir de oráculo para o pipeline completo

Contra o darktable o mesmo TIFF dá 4,5% / 10,5%, quando antes dava 0,7% / 5,3%. Valia a pena
investigar, e a causa isolou-se num par de corridas:

| Revelação | R/G | B/G |
|---|---|---|
| a nossa **sem** correcção de vinhetagem, contra darktable | 0,6% | 5,2% |
| a nossa **com** correcção de vinhetagem, contra darktable | 4,6% | 10,8% |

Sem a correcção, reproduz-se exactamente o 0,7% / 5,3% medido antes. **É a vinhetagem, e não é erro.**

O raciocínio: o `GainMap` deste DNG é identidade, portanto o darktable não corrige vinhetagem
nenhuma — e nós corrigimos, com um perfil medido. A nossa correcção é **por canal**, porque a queda
medida difere entre canais: no canto, 6,04× no vermelho contra 5,16× no verde. Logo a correcção
**muda a cromaticidade em direcção às bordas, de propósito**. Um revelador que não corrija a
vinhetagem tem de divergir de um que corrija.

Consequência prática: **o darktable continua válido para validar a ciência da cor, e deixou de ser
oráculo do pipeline completo.** O oráculo do pipeline completo é a referência em Python — que tem a
mesma correcção e concorda a 0,7% / 2,3%. Fica registado para não se voltar a interpretar esta
divergência como bug.

Nota menor: os 34,3% nos quase-neutros da comparação com darktable são artefacto de selecção, não
sinal. Os quase-neutros são escolhidos na primeira imagem, e a correcção de vinhetagem muda quais são
— com duas amostras só, não há nada a concluir.

#### Estado: F2 FECHADA

**132 testes, 0 falhas.** Os dois critérios cumpridos: CPU contra GPU a 1 em 255 dentro do telefone,
e cor validada contra uma implementação independente com o cartão de cinza a 0,0%.

Pendências que não bloqueiam, herdadas da F1: a orientação está fixa em `ORIENTATION_NORMAL`, o foco
em 0,5 dioptrias — visível nesta fotografia, que está desfocada — e o eixo de tinta implementado mas
sem controlo na UI.

---

## F3 — Visor WYSIWYG

### Primeiro medir, só depois construir

A F3 tem uma pergunta por responder que decide o que se constrói, e não há como a responder sem a
fazer. Neste dispositivo o RAW existe **a um tamanho só**, 12,5 Mpx — `getHighResolutionOutputSizes()`
vazio, uma saída RAW no máximo — portanto cada frame do visor são **25 MB a atravessar a fronteira
CPU → GPU**. A 30 fps são 750 MB/s só de carregamento de textura.

Ou o barramento aguenta, ou o princípio de que o visor não mente tem de ser repensado. Mede-se antes
de escrever a UI, pelo mesmo método que respondeu às nove perguntas da F1.

### O que se construiu, e porque não é andaime

| Peça | O que é |
|---|---|
| `render/Gl.kt` | `EglContext` + compilação + `bindUniforms` + textura de mosaico. Extraído do `GlDeveloper` |
| `render/GlPreview.kt` | o motor do visor: contexto, programa e textura **vivos entre frames** |
| `camera/CameraSession.startStream/nextImage` | consumo contínuo do pedido repetido |
| `diag/PreviewProbe.kt` | a medição, com veredicto |

A diferença entre o `GlPreview` e o `GlDeveloper` **não é matemática nenhuma** — é o ciclo de vida.
Criar uma textura de 25 MB trinta vezes por segundo seria insustentável, e é só isso que justifica as
duas classes. Que ambos usem os mesmos `GlslSource` e o mesmo `Gl.bindUniforms` não é conveniência: é
o que faz o visor mostrar o que o ficheiro vai ter. Se divergirem, o visor mente.

### Três decisões que valem registo

**O passo de linha não é `largura × 2`.** O HAL pode alinhar as linhas do plano RAW, e ignorar isso
daria uma imagem inclinada — com a cor a rodar de linha para linha, porque o padrão do mosaico
desalinha. Resolve-se com `GL_UNPACK_ROW_LENGTH`, que diz ao GL o passo em píxeis, em vez de copiar
linha a linha na CPU. O relatório diz qual é o passo medido.

**A fila de frames não precisa de limite próprio.** O `ImageReader` tem três lugares, e enquanto três
imagens estiverem por fechar o `acquireNextImage` recusa-se a entregar mais. É esse o travão. Sem ele,
um consumidor mais lento do que 30 fps acumularia imagens de 25 MB até não haver memória — e já se
sabe, da F2, quão pouca há.

**Os tempos medem-se com `glFinish` entre etapas.** Isso impede o encavalitamento, portanto o total
medido **é maior** do que o que se obtém em produção. Mede-se assim de propósito: nesta altura, um
número atribuível a uma etapa vale mais do que um número optimista sem culpado. O relatório separa
carregamento de desenho, e compara o tecto teórico da GPU com o ritmo efectivo — é isso que diz se o
travão é a GPU ou a câmara.

### Onde os uniformes vêm do último DNG, e porquê

A medição alimenta o shader com os uniformes tirados do **último DNG no disco**, não do
`CaptureResult`. É deliberado: a matriz de cor, o mosaico e os níveis são estáticos por câmara, e o
ponto neutro do ficheiro é o que a própria aplicação escreveu. Assim mede-se o que interessa medir —
a passagem dos dados — sem escrever primeiro a plumbing de metadados ao vivo, que é trabalho da F3
propriamente dita.

### Um teste que apanhou um defeito de desenho

O teste que fixa «o visor é metade do mosaico em cada direcção» falhou com
`EGL_NO_DISPLAY must not be null`: **construir um `GlPreview` tocava no EGL**, porque o `EglContext`
inicializa campos a partir de constantes do EGL14 — nulas numa JVM sem Android. Construir um objecto
não deve ter efeitos, e a correcção (contexto preguiçoso) é melhor desenho, não uma concessão ao teste.

E a guarda do `close()` estava errada à primeira: guardava por «há programa?», o que deixava o
contexto pendurado exactamente no caminho de erro — EGL aberto, compilação falhada, nada libertado.
Guarda-se por «arrancou?».

### A resposta: VIÁVEL, e o travão é a câmara

Quatro corridas seguidas, todas com o stream limpo — 301 avisos do reader, 301 frames entregues, zero
pedidos vazios, zero falhas:

| Corrida | fps do visor | espera pela câmara | trabalho na GPU |
|---|---|---|---|
| 1 | **27,8** | 25,1 ms | 9,4 ms |
| 2 | **26,9** | 26,8 ms | 11,8 ms |
| 3 | 19,2 | 35,2 ms | 14,2 ms |
| 4 | 18,9 | 37,1 ms | 12,7 ms |

**Em todas, o veredicto é o mesmo: a câmara, com folga.** Passa-se 25 a 37 ms bloqueado à espera do
frame e 9 a 14 ms a trabalhar. Os 23 MB por frame passam a 1,3–1,8 GB/s e o trabalho encavalita-se
inteiro dentro da espera — **optimizar a GPU não daria um frame a mais**.

A descida de 27,8 para 18,9 ao longo de corridas consecutivas, com o controlo a derivar −10 fps, é o
telefone a aquecer. Registe-se como intervalo honesto: **19 a 28 fps**, conforme o estado térmico.

E um facto novo sobre o dispositivo: **o piso de frame declarado não se cumpre.** O HAL declara
33,3 ms para o RAW de 12,5 Mpx, e entrega 36 a 52 ms. O declarado é um piso do que se pode *pedir*,
não uma promessa do que se recebe.

**A arquitectura mantém-se**: visor alimentado pelo stream RAW, com os mesmos shaders do ficheiro.

### Quatro lições de método, todas pagas

**1. Inferir o culpado de diferenças entre fases não funciona.** A primeira versão comparava a fase de
controlo com a de produção e concluiu um custo **negativo** — fazer mais era mais rápido do que fazer
nada. Os intervalos melhoravam com a *ordem* das fases, não com a carga. A correcção foi medir a
**espera dentro de cada fase**, que é comparação interna e imune à ordem, e repetir o controlo no fim
para a deriva ficar visível. O aviso de deriva disparou em três das quatro corridas — existe por bom
motivo.

**2. Engolir excepções em código de diagnóstico é pior do que não ter diagnóstico.** O
`acquireLatestImage` estava dentro de um `catch (t: Throwable) { null }`. Quando o stream parou, o
instrumento tinha escondido exactamente a informação necessária. Agora conta avisos, entregas, pedidos
vazios e guarda a última falha.

**3. O logcat desmentiu a conclusão do relatório.** O relatório dizia «a câmara morreu»; o logcat
mostrava a câmara a produzir **313 frames** até nós fecharmos a sessão. O `ERROR_CAMERA_DISABLED` era
do fecho, não da causa. Sem ir ao logcat, teria ficado a perseguir um problema de câmara que não
existia.

**4. Um visor não consome uma fila.** A primeira versão reutilizou a fila do disparo, onde o
*listener* adquire as imagens — e caiu na armadilha que a própria classe já documentava: com três
imagens por fechar, o `acquireNextImage` recusa, **a notificação perde-se e o stream não volta**. Num
visor a sério seria a imagem a congelar depois de uns segundos. Agora o *listener* só avisa e quem
consome faz `acquireLatestImage`, que traz a mais recente e descarta as velhas. É também a semântica
certa: um visor mostra o presente, não uma fila do passado.

### Dois achados sobre o dispositivo, de caminho

**~~`ERROR_CAMERA_DISABLED` (3) num Samsung é o ecrã a apagar-se.~~ Falso — ver o ponto 3 acima.** Foi a
minha primeira leitura do «erro 3» e ficou aqui escrita a contradizer, oito linhas acima, a conclusão
certa: o erro era do nosso fecho da sessão. Os códigos passaram a sair por extenso no relatório, e a
aplicação leva `FLAG_KEEP_SCREEN_ON` — que uma aplicação de câmara precisa por si, e não por isto.

Duas corridas falharam com o stream a parar por razão não identificada, antes da instrumentação
estar no sítio. Quatro corridas seguidas depois dela saíram limpas. **Não fica explicado**, fica
instrumentado: se voltar, os contadores dizem se o reader deixou de avisar, se o `acquire` devolveu
vazio, ou se lançou.

### O visor no ecrã

Feito, e por partilha de código e não por semelhança.

**Metadados ao vivo.** `GlUniforms.fromCamera` constrói os uniformes das `CameraCharacteristics` —
`SENSOR_FORWARD_MATRIX1/2`, iluminantes, mosaico, níveis — em vez de os ir buscar a um DNG no disco. O
ponto neutro é a única excepção, e deliberada: vem do **Kelvin e da tinta que o utilizador escolheu**,
pela mesma conta que produz os `COLOR_CORRECTION_GAINS` que determinam o `AsShotNeutral` do ficheiro.
Ler o `SENSOR_NEUTRAL_COLOR_POINT` do resultado seria perguntar ao HAL o que nós já decidimos.

Os dois caminhos — ficheiro e câmara — passam pela **mesma função privada** `montar`. Não é economia de
linhas: é o que garante que produzem os mesmos uniformes a partir dos mesmos números. E o
`PreviewProbe` compara-os e reporta, que é a versão para o visor da pergunta que fechou a F2.

**Apresentação separada da revelação.** A revelação desenha para uma textura, com a matemática do
ficheiro; um segundo passe trivial leva-a ao ecrã com rotação e enquadramento. Assim **mudar como se
mostra não pode mudar o que se mostra**, e o custo é um quadrilátero com textura — ao lado de um
carregamento de 23 MB, não se mede.

O enquadramento é **por dentro, com barras**. Cortar para preencher o ecrã esconderia parte do que vai
ser gravado, e um visor que esconde é da mesma família de mentira que este projeto existe para evitar.
Há um teste que exige escala ≤ 1 nos dois eixos em quatro formatos de ecrã e quatro rotações.

A geometria toda está em Kotlin, no `Present`, e não no shader: orientação e enquadramento saem errados
em silêncio. Uma imagem ao contrário vê-se; uma imagem cortada nas bordas não.

**Guarda contra um bug futuro.** O disparo consome pela fila (`collect`, para emparelhar por timestamp)
e o visor consome pelo `acquireLatestImage`. Os dois **não coexistem**: em modo de visor a fila fica
sempre vazia e o sintoma seria «resultado sem imagem», que manda procurar no sítio errado. O
`captureOne` passa a recusar com essa explicação. Unificar os dois caminhos — *listener* avisa sempre,
consumidor decide se quer o mais recente ou um timestamp — é o trabalho seguinte, e é para fazer
deliberadamente, não a improvisar.

### O visor, verificado no telefone

Funciona: imagem ao vivo a **21,9 fps**, vinhetagem corrigida, rodapé a dizer o que está a fazer.

Disse que a orientação «só se verifica a olho». Estava errado — verifica-se por correlação. Tirando uma
captura de ecrã do visor e disparando um DNG da mesma cena, revela-se o DNG na referência em Python e
compara-se contra as oito transformações possíveis:

| Transformação da referência | Correlação com o visor |
|---|---|
| **90° no sentido dos ponteiros** | **+0,9930** |
| espelho + 180° | +0,6563 |
| espelho + 270° | +0,0281 |
| as outras cinco | ≤ −0,11 |

Sem ambiguidade, e sem espelhamento. É o que o `SENSOR_ORIENTATION = 90` exige. O enquadramento
confirma-se pela medida: região de 1080×1439 no ecrã, aspecto **0,7505** contra 0,7500 esperado, com
barras acima e abaixo e nada cortado.

Os uniformes ao vivo contra os do ficheiro, medidos pelo próprio telefone:

```
matriz de cor, diferença máxima : 0.0
mosaico igual                   : true
nível de branco igual           : true
nível de preto igual            : true
balanço de brancos, desvio máx  : 0.066 %
```

### A armadilha da captura de ecrã: 7,5% de erro que era meu

Comparando a cor da captura de ecrã com a referência, deu **7,5% em R/G e 5,6% em B/G** — dez vezes
pior do que entre ficheiros. E sistemático: R/G sempre abaixo, B/G sempre acima, em todos os blocos.
Um enviesamento consistente não é ruído.

Primeiro descartei o registo: passando de comparação por pixel a médias de blocos grandes, o desvio
**não mudou**. Depois li o instrumento que já existia — a comparação de uniformes acima — e os
uniformes eram idênticos. Se a entrada é a mesma e os shaders são partilhados, a diferença não podia
estar no pipeline.

Estava na captura. O PNG do `screencap` traz um perfil ICC embutido, e as primárias são de **Display
P3**: `rXYZ` X = 0,5151, contra 0,4360 do sRGB. Eu lera números P3 como sRGB.

| Leitura | R/G | B/G |
|---|---|---|
| como sRGB, errado | 7,5% | 5,6% |
| convertido de P3, certo | **0,7%** | **1,9%** |

0,7% / 1,9% é a mesma concordância que o ficheiro tem com a referência em Python (0,7% / 2,3%). **A cor
do visor está verificada ponta a ponta.**

E prova mais do que a nossa aritmética: para converter de P3 e passar a bater, a conversão sRGB → P3
teve de acontecer **de facto** na composição. Se o sistema estivesse a mostrar os nossos números sRGB
como se fossem P3 — o erro clássico, que dá cor sobressaturada — a conversão teria afastado em vez de
aproximar. Logo o que aparece no ecrã está certo.

É a terceira vez que este projeto tropeça na mesma lição, agora num sítio novo: **uma referência
injusta parece um bug no nosso código.**

### Oportunidade que isto revela

O ecrã é Display P3 e o compositor converte. Estamos a mostrar e a gravar em sRGB, ou seja **a atirar
gamut fora** num ecrã que o tem. O `ColorScience.Output.DISPLAY_P3` já existe e está testado, e o ICC
correspondente também. É uma decisão para a F4/F5, não trabalho de agora, mas fica anotada com o
motivo.

### Um caminho só para consumir o `ImageReader`

Era o que faltava para disparar a partir do visor, e resolveu-se apagando a causa em vez de a
contornar.

Houve três versões, e as duas primeiras partiram do mesmo erro: **deixar o *listener* adquirir
imagens**. Na F1, guardar imagens sem as fechar esgota o `maxImages`; à quarta o `acquireNextImage`
recusa e **o reader nunca volta a notificar**. Depois o visor reutilizou essa fila e caiu no mesmo — a
fase 1 correu 60 frames e a fase 2 morreu ao décimo. E os dois caminhos não coexistiam: com o visor a
consumir, a fila do disparo ficava sempre vazia.

Agora o *listener* **avisa e nunca adquire**. Quem consome adquire, fecha, e escolhe o critério:

| Quem | Critério | Porquê |
|---|---|---|
| visor | o **mais recente**, saltando os atrasados | mostra o presente, não uma fila do passado; e largar frames é melhor do que acumular atraso |
| disparo | **por ordem, até ao timestamp pedido** | quer aquele frame, não um qualquer |

Fechar o que não serve é o que devolve lugares ao reader — a armadilha deixa de ser possível nos dois.

Dois detalhes que custaram a acertar. A **posse** dos frames recusados é explícita: a primeira versão
guardava um frame recusado enquanto o consumidor o fechava, o que é uso depois de fechar. E quando há
permissões mas o `acquire` devolve vazio — o reader já descartou o que elas contavam — limpam-se as
permissões, senão o laço girava a consumi-las sem nunca bloquear.

### Disparar a partir do visor

O botão **não dispara: marca**. Quem dispara é o laço de render, entre dois frames, porque a sessão
pertence a esse fio e chamá-la de fora seria pedir uma corrida.

E **não se pára o pedido repetido**, ao contrário do disparo da F1: apagaria o visor durante a captura,
e já se sabe da F1 que parar e disparar logo a seguir faz o HAL descartar o pedido. Intercala-se, e o
frame certo identifica-se pelo timestamp.

O ficheiro corresponde ao que estava no visor **por construção**: mesmo stream, mesmos uniformes,
mesmos shaders.

### Disparo a partir do visor: FUNCIONA

Corrido no telefone, quatro disparos seguidos:

```
LTNT_0001 … LTNT_0004   fase «F3 · visor»
imagem casada com o resultado pelo timestamp : True   (nos quatro)
```

E o visor **continuou a correr** entre eles — 20,6 fps antes do primeiro disparo, 18,3 fps depois do
quarto, com o rodapé a voltar à linha ao vivo. Nem congelou nem foi preciso reabrir a sessão.

O caminho unificado do `ImageReader` também se mediu na corrida das cinco fases: **305 avisos do reader,
301 frames entregues, zero pedidos vazios, zero falhas**, e 29,3 fps. Os 4 de diferença entre avisos e
entregas são os frames atrasados que o visor salta de propósito — a contabilidade mostra-o em vez de o
esconder.

### O aviso passou a ficar no ficheiro

O `matchedByTimestamp` vivia só no rodapé do visor, que desaparece ao frame seguinte. Agora vai para o
sidecar, com aviso explícito quando é falso. É o que o sidecar existe para fazer: **um frame do visor
tem os mesmos parâmetros manuais mas é de outro instante**, o resultado é utilizável e quem revelar tem
de o saber. Foi exactamente por não estar registado que não pude verificar retroactivamente os seis
disparos do T15.

### Estado: F3 FECHADA

**153 testes, 0 falhas.** APK `latente-f3.apk`.

Os dois critérios cumpridos. O visor mostra o resultado final do nosso pipeline — verificado: uniformes
idênticos aos do ficheiro, cor a 0,7% / 1,9%, orientação a +0,993 de correlação. E dispara-se dele, com
o frame certo, sem interromper o visor.

### Pendências que não bloqueiam

| O que | Onde |
|---|---|
| orientação do DNG fixa em `ORIENTATION_NORMAL` | o ficheiro sai deitado quando o telefone está em retrato |
| foco fixo em 0,5 dioptrias | visível nas fotografias de perto, que saem desfocadas |
| eixo de tinta implementado, sem controlo na UI | `Exposure.tint` existe e o `WhiteBalance` usa-o |
| exposição fixa em 1/125 s ISO 50 | é a F4: modos M/A/S/P e fotómetro |
| `ImageDescription` do DNG leva UTF-8 | os campos ASCII do TIFF são 7 bits por especificação; o «·» sai como dois bytes. Tolerado por tudo o que abriu os ficheiros, mas é desvio |

---

## F4 — Câmara

### O fotómetro mede o RAW, não o que o HAL diz

`model/Meter.kt`. Três razões para medir o mosaico do sensor e não a saída do pipeline, por ordem de
importância: **é onde o corte acontece** — depois da matriz de cor já não se sabe o que se perdeu; é
anterior ao balanço de brancos, portanto não depende do Kelvin escolhido; e as estatísticas do HAL vêm
do ISP, que esta aplicação não usa.

Mede-se o **verde**: carrega a maior parte da luminância, tem o dobro das amostras de qualquer outro
canal, e é o canal que a exposição de uma câmara sempre governou. Há um teste que põe o verde a meio e
o vermelho e o azul no corte, e exige que a leitura dê meio — se o vermelho entrasse, uma cena vermelha
mediria diferente de uma cena verde com a mesma luminância, e a exposição mudaria com a cor do assunto.

Usa-se o **percentil 99,5 e não o máximo**. Um sensor tem sempre píxeis quentes e reflexos especulares;
com o máximo, meia dúzia deles subexporia a fotografia toda. Um teste põe 0,2% das amostras no corte e
exige que o percentil as ignore **e** que o corte seja reportado — as duas coisas.

Corre na CPU sobre o `ByteBuffer` que a câmara já entregou, sub-amostrado: cerca de 195 mil amostras
num frame de 12,5 milhões, menos de 2%. Ler de volta da GPU custaria muito mais do que a medição vale.

E é **testável na JVM sem simulação nenhuma**, porque um `ByteBuffer` é Java puro: os testes correm
exactamente o mesmo código que o telefone corre.

### A linha de programa segue o que se mediu

`model/ExposureProgram.kt`, funções puras sobre primitivos pela mesma razão que o `HalClamp`: um erro
aqui dá uma fotografia mal exposta, não uma excepção.

| Passo | O quê | Porquê |
|---|---|---|
| 1 | ISO na base, tempo até 1/30 s | um stop de tempo não custa qualidade; um stop de ISO custa ruído |
| 2 | ISO analógico até 640, tempo em 1/30 s | acima de 640 o ganho é digital — **volume, não sinal** |
| 3 | tempo até 1750 ms | o tecto **real**, não os 100 ms declarados |
| 4 | ISO digital | último recurso, e a nota di-lo pelo nome |

Critério por omissão: **proteger as luzes altas**, com meio stop de margem. Num RAW a sombra
recupera-se; luz cortada não existe, não há lá informação nenhuma.

E em `M` o fotómetro **aconselha e não mexe em nada**. É o ponto de haver um modo manual.

### Um defeito de desenho que os testes apanharam

A primeira versão distribuía a correcção por **ajustes encadeados**: punha o ISO na base, tentava o
tempo, depois subia o ISO. Partindo de 100 ms a ISO 400, descer o ISO três stops e cortar o tempo ao
limite de mão livre **tirava luz quando lhe pediam para pôr** — 4 stops de défice a partir de um pedido
de meio stop.

A correcção foi trabalhar sobre a **luz total pedida** (tempo × ISO) em vez de ajustes relativos. Daí sai
uma propriedade que ficou fixada em teste: **em P o resultado depende só da luz pedida, não do ponto de
partida**. Dois pontos de partida a dois stops de distância, com a mesma leitura, dão a mesma resposta.

Consequência que parece contra-intuitiva e é correcta: de 100 ms a ISO 400 pedindo meio stop a menos, a
resposta é **44 ms a ISO 640** — mais ISO do que estava. É o que «a câmara escolhe» significa: recalcula
da linha, não empurra o que lá estava. Uma Sony faz o mesmo.

### O A e o P coincidem neste corpo

A principal tem **uma só abertura**, f/1,8. «Prioridade à abertura» com uma abertura é o P com a abertura
fixada — não é degenerescência do modo, é o que o modo significa aqui. Fica fixado em teste para ninguém
tomar a coincidência por bug, nem por licença para eliminar o modo.

### Duas salvaguardas contra o visor a piscar

O ajuste ao vivo tem uma armadilha: depois de mudar a exposição, **os frames já em voo trazem a
antiga**. Medir um desses faz corrigir duas vezes o mesmo erro e oscilar. Por isso saltam-se seis frames
depois de cada mudança — um quinto de segundo a 30 fps.

E há uma zona morta de um terço de stop: sem ela o ruído da medição faria a exposição tremer sempre, e
um visor a piscar é pior do que um visor um terço de stop ao lado.

### Corrido no telefone: converge e não oscila

| Disparos | Tempo | ISO |
|---|---|---|
| quatro seguidos, 16:30:09–30 | 33,333 ms | **205, idênticos** |
| um, 16:30:40 | **100,000 ms** | **787** |
| quatro seguidos, 16:31:05–21 | 33,333 ms | **251, idênticos** |

**Converge e fica quieto** — quatro disparos consecutivos com valores exactamente iguais, duas vezes. E
assenta em 33,333 ms, que é precisamente o limite de mão livre: o programa está no passo 2, tempo fixo e
ISO a fazer o trabalho, como desenhado. As duas salvaguardas fizeram o que era esperado delas.

### O que aquele 100 ms a ISO 787 revelou

O perfil da objectiva guardava o tecto de exposição **declarado** — 100 ms — e este projeto já tinha
medido que é mentira: o HAL honra **1750 ms**, 17,5× mais. Duas consequências, ambas más:

1. A linha de programa esgotava o tempo 17,5× mais cedo e **ia ao ganho digital com quatro stops de
   tempo ainda por gastar**. Não é afinação: é a diferença entre um ficheiro com ruído digital e um
   ficheiro limpo.
2. O sidecar repetia «tecto de exposição de 100 ms» nos **avisos de honestidade** — ou seja, declarava
   como aviso uma coisa que a aplicação já tinha desmentido por medição. O contrário do que este projeto
   promete.

Corrigido com `model/BodyCalibration.kt`, no mesmo padrão do `ShadingProfile.forDevice`: calibração por
modelo, e o valor declarado quando não há medição. **Não se inventa** — um corpo desconhecido fica com o
que o fabricante diz, mesmo sabendo que muitos dizem mal. E o limite continua a ser um limite: acima do
tecto real o HAL não corta, **fecha o dispositivo**, e é por isso que este valor tem de vir de medição.

O `LensProfile` passa a guardar os dois, `exposureMaxNs` e `exposureMaxDeclaredNs`, pela mesma razão que
o `RequestPlan` guarda o nosso corte e o do HAL. O aviso passa a dizer a verdade: «declarado 100 ms,
medido 1750 ms — usa-se o medido».

Um teste fixa a descoberta: a mesma cena escura dá ISO digital com o tecto declarado e ISO analógico com
o medido.

### Foco manual e realce de picos

O foco estava preso em 0,5 dioptrias desde a F1, e era a razão de **todas** as fotografias de perto
saírem desfocadas. Passa a ter barra no visor, e a barra é linear em **dioptrias**, não em metros:
assim o curso reparte-se pelo perto, que é onde o foco é crítico. Linear em metros daria noventa por
cento do curso ao infinito.

O realce mede o **Laplaciano** da luminância, não o gradiente. Uma aresta desfocada tem gradiente mas
segunda derivada pequena; um realce por gradiente acenderia em tudo o que tivesse contraste, focado ou
não — e um realce que acende em tudo não diz nada.

E vive no **passe de apresentação**. Não é arrumação: torna estruturalmente impossível uma ajuda de
visor contaminar o ficheiro. A revelação desenha para a textura e nem sabe que o realce existe.

### Verificado contra uma régua

Montou-se uma régua alinhada com o eixo da objectiva, com um objecto a **40 cm medidos**. Varreu-se o
foco em nove passos, de infinito a 10 cm, com a **exposição constante** — 33,3 ms, ISO 82 — porque o
fotómetro não corre durante o varrimento. Sem isso, diferenças de exposição contaminariam a medida.

| Foco | Distância | Nitidez |
|---|---|---|
| 0,00 D | infinito | 0,051 |
| 1,25 D | 80 cm | 0,070 |
| **2,50 D** | **40 cm** | **0,156** |
| 3,75 D | 27 cm | 0,063 |
| 5,0 a 10,0 D | 20 a 10 cm | 0,044 a 0,049 |

O pico está **exactamente nos 40 cm**, com 2,2× o segundo melhor e 3,5× o mínimo. E 1/2,498 = 0,400 m,
portanto não é só o pico que bate: **a escala de foco está verificada a três algarismos**.

O mapa por zonas mostra o passo 3 a dominar, com algumas zonas a picar mais perto — o gradiente de
profundidade da régua a aproximar-se da câmara. É a assinatura de um foco que funciona, não de um
número que calhou.

O realce confirma-o a olho: a 0,37 m acende na tesoura e nas marcas da régua; a 0,10 m, com tudo
desfocado, **desaparece**.

Ferramenta nova: `tools/focus.py`, que mede a variância do Laplaciano no **mosaico** e não numa
revelação — revelar em resolução reduzida destruiria as frequências altas que definem o foco, e a
medição passaria a dizer mais sobre o redimensionamento do que sobre a objectiva.

### Um defeito que só se vê a olho

Os primeiros ensaios tinham **salpicos vermelhos no primeiro plano escuro**, iguais com a cena focada e
desfocada. Era o Laplaciano a acender no ruído das sombras. Um realce que acende onde não há sinal não
está a indicar foco — está a indicar ISO.

Corrigido com um piso de luminância: onde não há sinal, não há foco a indicar. Os salpicos
desapareceram e o realce nas arestas manteve-se.

### Kelvin e tinta, num comando só

O visor tinha o Kelvin fixo em 5500 e a tinta em zero. Passam a ser controlos — e vão ao **visor e ao
ficheiro ao mesmo tempo**: ao visor pelos uniformes da revelação, ao ficheiro pelos
`COLOR_CORRECTION_GAINS`, que determinam o `SENSOR_NEUTRAL_COLOR_POINT` e por aí o `AsShotNeutral` do
DNG. Mudar um sem o outro faria o visor mostrar uma cor e o ficheiro levar outra, que é precisamente o
que este projeto existe para impedir.

Em vez de uma barra por parâmetro — que num telefone come o visor — há **um comando só, com um botão a
dizer o que ele mexe**, como o anel de uma máquina: foco → Kelvin → tinta.

E as escalas são as certas para o que controlam, não as óbvias:

| Comando | Linear em | Porquê |
|---|---|---|
| foco | **dioptrias** | o inverso da distância; o curso reparte-se pelo perto, onde o foco é crítico. Linear em metros daria 90% do curso ao infinito |
| Kelvin | **mired** | o inverso da temperatura; passos iguais em mired são passos perceptualmente iguais. Mil graus a 3000 K vêem-se, a 10000 K não |
| tinta | linear | o eixo verde–magenta já é linear no que faz |

### O ensaio que falta, e agora é possível

A F2 registou uma lacuna que nunca se pôde fechar: **não havia uma superfície neutra sob luz de
temperatura conhecida**. O melhor ajuste de Kelvin nas chapas antigas dava 3450–3725 K com um resíduo
de **7% a 11%** — sinal de luz fora do locus de Planck, que é exactamente a razão de o eixo de tinta
existir.

Montou-se agora: **folha branca sob uma lâmpada Philips de 4000 K**. Com isso o ensaio é directo, e
usa o `tools/flatfield.py`, que já existe e já está validado — procura o Kelvin que torna a superfície
neutra e cruza o resultado com o `WhiteBalance.kt`.

O procedimento: encher o quadro com a folha, pôr o comando em 4000 K com tinta zero, disparar. Se a
folha não sair neutra, o desvio decompõe-se — o que for comum a R/G e B/G é **tinta**, o que os separa
é **Kelvin**. Depois aplica-se a tinta indicada e confirma-se que anula.

### O eixo de tinta, provado contra uma folha branca

Montou-se folha branca deitada na mesa, lâmpada Philips de 4000 K a 38 cm, câmara a apontar para baixo
a 30 cm, telefone a 3,5° e 49,5° no acelerómetro. Três tentativas anteriores foram **rejeitadas** — luz
rasante, monitor aceso, faixas a discordarem 9% entre si — porque uma chapa mal iluminada dá números
falsos com ar de bons.

| Balanço | R/G | B/G |
|---|---|---|
| 3818 K, tinta 0 | −3,7% | **−25,3%** |
| 3595 K, tinta **−0,25** | **+1,7%** | **+4,1%** |
| o melhor possível **sem eixo de tinta** | +10,4% | **+26,6%** |

**A folha fica neutra, e só fica com o eixo de tinta.** Sem ele, o melhor ajuste possível deixa 27% de
erro no azul — a F2 tinha previsto 7% a 11% e a realidade é pior. É o critério do cartão de cinza da §9,
cumprido contra uma superfície física sob luz real em vez de estimada.

Nota sobre a luz: neutraliza a 3595 K, não aos 4000 K nominais da lâmpada. Não é contradição — mede-se a
luz que **chega à folha**, com a mistura do ambiente, e é essa que interessa.

### Porque é que a primeira previsão falhou: a resposta não é proporcional

A primeira correcção calculada — 3290 K, tinta −0,50 — previa resíduo de 1,2% e deu **+47,6%**. O erro
era meu, e vale a pena perceber onde.

Assumi que a razão renderizada responde **proporcionalmente** ao ponto neutro. Não responde. Isolado:

| Mudança | R | B |
|---|---|---|
| só a matriz de cor | −1,3% | +1,6% |
| só o balanço | +5,0% | **+94,4%** |

O ponto neutro mudou 37% no azul e o render mudou **94%** — dois vezes e meia mais. A `ForwardMatrix`
tem coeficientes negativos grandes na linha do azul, `−0,408` no verde, e isso amplifica. Qualquer
ferramenta que calcule uma correcção de balanço tem de passar pelo **pipeline inteiro**, não pela regra
de três. Com dois pontos medidos e interpolação em log, a segunda iteração acertou.

### Dois defeitos que este ensaio revelou

**1. A correcção de vinhetagem piora a uniformidade de cor.**

| Revelação | R/G espalhamento | B/G espalhamento |
|---|---|---|
| com vinhetagem a 100% | 7,5% | **9,7%** |
| sem correcção | 5,6% | **4,4%** |

Corrige o brilho e introduz desvio cromático, mais do dobro no azul. Um perfil por canal errado dá
exactamente isto: cor a mudar em direcção aos cantos — que é o género de artefacto computacional que
este projeto existe para não ter.

Tentou-se resolver com o par rodado 180°, que cancela gradientes de luz por construção. O
`flatpair.py` **rejeitou a medição**: 84% de dispersão entre cantos, com a borda superior a dar 0,391 a
0° e 0,032 a 180° — um factor de doze, que é sombra e não gradiente. Uma lâmpada a 38 cm sobre uma folha
de 30 cm cai por lei do inverso do quadrado **e** por cosseno, e o truque do par só cancela o que é
linear; e o próprio telemóvel tapa a luz numa das orientações.

**Fica em aberto**, e o que resolve é a montagem que já resolveu a vinhetagem da primeira vez: **ecrã
iluminado a branco com difusor colado à lente**, que dá luz uniforme por construção em vez de por
cancelamento. Foi a quarta medição rejeitada hoje, e as quatro foram rejeitadas pelas ferramentas em
vez de darem números com ar de bons.

Força do que se sabe, para não se exagerar: a comparação com e sem correcção **é** válida na parte que
importa, porque o gradiente da cena afecta as duas revelações por igual e a diferença entre elas é
atribuível à correcção. Que o espalhamento no azul duplique ao ligá-la é **indício forte**. O que a
montagem não permite é quantificar o erro do perfil ao ponto de o corrigir — para isso é preciso o
difusor.

**2. O fotómetro protege o RAW, mas não o que se vê.** O RAW saiu com máximo 787 de 1023, folgado. A
revelação saiu com **50,8% dos píxeis no corte**, porque a correcção de vinhetagem multiplica até 6× nas
bordas. O ficheiro está bom e o visor mostra queimado.

Não era o fotómetro a medir mal — era a medir a coisa certa e a faltar-lhe um passo. **Corrigido**: o
`Meter` passa a pesar cada amostra pelo ganho de vinhetagem do seu raio, pela mesma fórmula que a
revelação usa. Se as duas divergissem, o fotómetro protegeria uma imagem que não é a que se grava. Dois
testes fixam-no, incluindo o caso de força zero, onde tem de medir exactamente como se não houvesse
perfil.

### As ajudas de visor

Três, e todas fora do caminho do ficheiro por construção.

**Zebras** sobre o corte **do sensor**, não o do render. A distinção é a que decide: o corte do render
recupera-se baixando a exposição na revelação; o do sensor não existe, não há lá nada. O passe de
revelação escreve esse corte no **alfa** da textura e o passe de apresentação lê-o de lá — o alfa não
era usado, e assim a informação verdadeira chega à ajuda sem a ajuda tocar na imagem. Riscas na
diagonal e não cor chapada: sobre uma zona já branca, uma marca branca não se veria.

**Histograma** do verde do sensor, em escala logarítmica. Numa câmara RAW é esse que decide — mostra o
que o sensor captou, antes de balanço, matriz e vinhetagem. Um histograma do render diria onde a
*nossa* revelação corta, que é recuperável.

**Nível** a partir do acelerómetro, verde dentro de meio grau.

O histograma e o nível vivem numa `View` por cima, e não no shader: são linhas, coisa que o `Canvas`
faz bem. E torna evidente o que já era regra — uma ajuda de visor não tem caminho nenhum até ao
ficheiro.

Dois defeitos de posicionamento, ambos do mesmo tipo: o botão das ajudas e o histograma ficaram **por
trás da barra de título do sistema**. O botão não recebia os toques e o histograma parecia em falta em
vez de tapado. Corrigidos.

### Estado

**184 testes, 0 falhas.** APK `latente-f4.apk`. No visor: modos M/S/A/P, fotómetro do RAW, foco com
realce, Kelvin e tinta, zebras, histograma, nível e disparo — tudo verificado no telefone.

O rodapé passa a mostrar **margem até ao corte e percentagem já cortada** — a informação que decide uma
fotografia e que nenhuma aplicação de fabricante mostra. Em manual mostra também o conselho do
fotómetro.

### Verificado no telefone, com a convergência a fechar no alvo

O rodapé do visor, em cena de interior:

```
P · 1/30 s f/1.8 ISO 124 · id 0 · 23 mm f/1.8
margem +0.5 EV · cortado 0.00%
vinhetagem corrigida · rot 90° · 29.1 fps
```

A margem começou em **+0,9 EV** e assentou em **+0,5** — que é exactamente o `DEFAULT_HEADROOM_STOPS`.
Convergiu e parou: ISO estável em 124 ao longo de doze segundos, sem tremer. O disparo saiu com 33,333 ms
e ISO 124, casado pelo timestamp, igual ao que o rodapé dizia.

E a calibração está viva, com o aviso a dizer a verdade:

```
tecto de exposição: declarado 100 ms, medido 1750 ms — usa-se o medido
```

### Objectiva tapada: o tecto confirma-se, e aparece um defeito pior

Com a objectiva tapada, o rodapé deu:

```
P · 1.8 s f/1.8 ISO 3200 · margem +4.6 EV · cortado 0.00% · faltam +4.14 EV
0.6 fps · ISO digital
```

**O tecto medido está confirmado**: 1,8 s é o 1750 ms, onde antes ficava preso nos 100 ms. E reporta o
que não consegue dar — «faltam +4,14 EV» — em vez de fingir. Com a objectiva tapada nenhuma exposição
chegaria, e a resposta certa é ir ao limite e dizê-lo.

Mas **0,6 fps não é um visor**. E a taxa era o menor dos problemas: com frames de 1,8 s, os seis frames
de arrefecimento davam **onze segundos** até reagir a destapar a objectiva. Um visor congelado deixa de
ser um visor.

### O visor e o disparo passam a ter tectos diferentes

Não é uma concessão: é o que uma máquina de verdade faz. A vista ao vivo mantém-se responsiva e a
fotografia leva o tempo que precisar.

| | Tecto de tempo | Porquê |
|---|---|---|
| visor | **1/8 s** | abaixo disto a vista morre; 8 fps é o mínimo utilizável |
| disparo | **1750 ms** | uma fotografia pode levar 1,8 s |

E o visor **não fica a mentir sobre o brilho**. O que lhe falta em luz entra como **ganho na revelação**
— o `exposureEv` que os uniformes já suportavam — portanto mostra o brilho que o ficheiro vai ter, com
mais ruído. É a troca honesta: o ruído vê-se e não engana; o brilho errado engana.

O rodapé passa a mostrar as duas exposições quando diferem, e o ganho aplicado. Esconder a diferença
seria o visor a mentir por omissão, que é o que este projeto existe para não fazer.

### Confirmado no telefone

Na mesma cena escura que antes dava 0,6 fps:

```
P · disparo 1.8 s f/1.8 ISO 3200
visor 1/8 s f/1.8 ISO 3200 +4.6 EV de ganho
margem +5.2 EV · cortado 0.00% · faltam +4.69 EV
rot 90° · 7.8 fps
```

**7,8 fps contra 0,6 — treze vezes mais**, e no alvo do tecto de 1/8 s. As duas exposições aparecem
separadas, com o ganho de apresentação dito pelo nome.

E o disparo nesse estado:

| | |
|---|---|
| tempo pedido | 1750,0 ms |
| tempo aplicado pelo HAL | **1750,0 ms** |
| cortado por nós / pelo HAL | não / não |
| ISO | 3200 |
| casado pelo timestamp | sim |

Três coisas que isto prova de uma vez. O tecto medido de 1750 ms é honrado pelo HAL **pela linha de
programa**, e não só numa experiência feita à mão. O emparelhamento por timestamp funciona com uma
captura de 1,75 s intercalada num pedido repetido de 1/8 s — catorze vezes mais longa do que os frames
do visor. E o visor **sobreviveu à captura longa**: 8,0 fps logo depois, sem stall nem sessão perdida.

---

## F5 — Exportação e biblioteca

### O sidecar reconstrói a revelação

É o critério de aceitação da fase, e está cumprido. O sidecar passa a levar um bloco **Revelação** com
a receita exacta — EV, Kelvin, força da vinhetagem, rolloff, espaço de saída — e o `tools/develop.py`
lê-a com `--sidecar`.

A prova: revelou-se o mesmo DNG duas vezes, uma pelo telefone e outra em Python guiada só pela receita.

| | Desvio |
|---|---|
| R/G | **0,13%** |
| B/G | **0,40%** |
| luminância | **0,25%** |

Menos de meio por cento em tudo, entre um TIFF de 12,5 Mpx feito na GPU do telefone e uma revelação em
Python a 1/8 da resolução. O DNG é o negativo e é imutável; isto é a receita, e sem ela quem abrir o
ficheiro daqui a um ano revela-o de **alguma** maneira, não daquela.

### Um erro que valia 4,6 EV

A primeira versão gravava na receita o **ganho do visor**. Esse ganho existe só porque o visor tem o
tempo travado em 1/8 s; o ficheiro leva a exposição inteira — 1,8 s no ensaio da lâmpada. Gravá-lo
contaria a mesma luz duas vezes e daria um TIFF 4,6 EV claro de mais.

A tinta também não entra na receita, e por boa razão: vai nos ganhos de cor, portanto já está no
`AsShotNeutral` do DNG e o revelador lê-a de lá. Guardá-la nos dois sítios seria convidar as duas
cópias a divergir.

### AVIF não existe nesta plataforma sem dependência

Verificado no `android.jar` da API 36: o `Bitmap.CompressFormat` tem **JPEG, PNG, WEBP,
WEBP_LOSSLESS, WEBP_LOSSY** e mais nada. Sem AVIF e sem HEIC.

O que a especificação pedia em §5 era AVIF como entrega comprimida. As saídas possíveis:

| Caminho | Custo |
|---|---|
| **HEIF** por `MediaCodec` HEVC + `MediaMuxer` | está na plataforma desde a API 28; suporta 10 bits com Main10 |
| **AVIF** por libavif | quebra a decisão de **zero dependências de runtime** |
| ficar pelo **TIFF16** | já feito, é o formato de arquivo, e nenhum bit se perde |

**Decidido: TIFF16, e nenhum formato comprimido.** O critério dado foi «a melhor que entregue a melhor
imagem sem perdas», e é o TIFF16 — já existe, não perde um bit, e leva ICC. A compressão seria só
tamanho de ficheiro, comprado com uma dependência ou com `MediaCodec`.

São **três ficheiros por fotografia**, cada um com o seu papel: o **DNG** é o negativo e é imutável; o
**TIFF16** é a cópia revelada; o **JSON** é a receita que liga um ao outro. A especificação passou de
quatro ficheiros para três, com a razão escrita.

### A biblioteca e o ecrã de análise

`export/Library.kt` e `ui/LibraryActivity.kt`. Cada linha da lista é **uma fotografia**, não um
ficheiro: mostra os três papéis juntos — negativo, receita, cópia revelada — e diz quais existem.
Listar `.dng`, `.json` e `.tif` como entradas soltas esconderia que são a mesma coisa.

Visto no telefone: **29 fotografias**, todas com negativo e receita.

Revelar da biblioteca usa **a receita do sidecar**, não as definições actuais da aplicação. Quem revela
um negativo de há um mês quer a revelação daquele dia. E revela no **CPU**, que é o caminho que produz
16 bits — chamar a GPU para deitar o resultado fora custaria 50 MB e 300 ms num sítio onde já houve
falta de memória.

Tocar no nome abre a **análise**, ali mesmo na lista e não num ecrã à parte: o que se quer é comparar
uma fotografia com a de cima, e para isso têm de estar as duas à vista.

A análise não é o sidecar despejado — é a leitura dele, por ordem do que importa a quem abre um negativo
meses depois. Primeiro **se o frame é o que se pediu**, que é a única coisa que invalida tudo o resto.
Depois **só o que divergiu** entre pedido e aplicado, porque repetir os valores que coincidiram enche o
ecrã de linhas sem informação. Depois a receita. E por fim os avisos, sob o título que diz o que são:
«o que o HAL faz pelas nossas costas».

Usa o `org.json` da plataforma. Onde bastava uma extracção dirigida de meia dúzia de números — na
receita — usou-se essa; aqui percorre-se a árvore, e para isso um leitor a sério é mais simples do que
expressões regulares.

### Dois defeitos corrigidos ao construir isto

Estava a revelar na GPU **e** no CPU, usando só o resultado do CPU. Cinquenta megabytes e trezentos
milissegundos por nada.

E a biblioteca não segurava o ecrã aceso. Uma revelação escreve 71 MB e leva segundos; com o ecrã a
apagar-se a meio, a actividade sai de cena e o trabalho perde-se. Aconteceu durante o próprio ensaio.

### Verificado no telefone, ao bit

Ciclo completo do zero: disparo no visor, negativo e receita gravados, biblioteca a listá-los, e
revelar. O TIFF saiu em **9 segundos** e com **o nome da fotografia** — `LTNT_0001_….tif` — e não um
nome genérico.

E a prova de que a receita é lida bem: o TIFF da biblioteca contra o do caminho de referência, ambos a
resolução inteira, deu **diferença máxima 0 de 255**. Idênticos ao bit.

Houve um susto pelo meio que vale registar. Comparando o TIFF da biblioteca com uma revelação em Python
deu 5,35% no azul, quando o mesmo ensaio dias antes tinha dado 0,40%. Suspeitei que a biblioteca
estivesse a revelar sem correcção de vinhetagem — e estava errado: com correcção dá 2,7% de desvio em
luminância, sem correcção dá **86%**, portanto está lá. A causa era a **cena**: média de 21 em 1023, com
corte no máximo, ou seja muito escura e de alto contraste. Nessas condições as medianas por bloco são
ruído, e comparar resolução inteira com 1/8 exagera-o no azul, que é o canal com menos sinal. A
comparação entre os dois TIFF do telefone, ambos à mesma resolução, tirou a cena da equação.

Os sidecars antigos ficaram órfãos na pasta quando os negativos foram apagados, e a biblioteca
ignora-os por não terem negativo. É o comportamento certo: sem negativo não há fotografia.

### Dois defeitos de interface, corrigidos

A lista recarregava depois de revelar e **apagava a mensagem do resultado** — o TIFF saía e o ecrã dizia
só quantas fotografias havia. Uma acção que apaga a sua própria mensagem parece não ter acontecido.

E dizia «1 fotografias».

---

## F6 — Verificação anti-mastigação

### O certificado: cada promessa ligada à prova dela

`diag/Certificate.kt`. Esta aplicação promete uma coisa difícil de verificar de fora — que a fotografia
é do sensor e não do ISP. **Uma promessa dessas ou tem prova ou é publicidade.**

O certificado lê o que o HAL declara, dispara um frame, confirma no `CaptureResult` o que ficou mesmo
definido, e dá veredicto por promessa. Assina com modelo, build do sistema e data, porque um veredicto
sem dispositivo e sem data não vale nada: o HAL muda com actualizações, e este projeto já viu uma chave
declarada mentir por 17,5×.

Corrido no dispositivo de referência: **todas as 11 promessas verificadas**.

```
sem redução de ruído           NOISE_REDUCTION_MODE confirmado = 0
sem nitidez automática         EDGE_MODE confirmado = 0
exposição e balanço nossos     CONTROL_AE_MODE = 0, CONTROL_AWB_MODE = 0
sem fusão multi-frame          CONTROL_ENABLE_ZSL confirmado = false
o que se pede é o que se leva  pedido 8000000 ns / ISO 50 · aplicado igual
pedestal não subtraído 2×      SENSOR_BLACK_LEVEL_PATTERN [0,0,0,0]
tecto honrado                  declarado 100 ms, medido 1750 ms, 17,5×
```

A regra de escrita: **nada é afirmado sem o valor ao lado**, e onde não se conseguiu verificar diz-se
«SEM PROVA» em vez de se assumir que está bem.

### O certificado apanhou um defeito — dele próprio

À primeira corrida deu **FALHADA** em «não se aplica um mapa de vinhetagem que não se conhece», porque
o HAL entrega um mapa. Mas o produto está correcto: o revelador **nunca lê essa chave**, corrige com o
perfil medido. O que estava errado era o critério — fiz «mapa ausente» ser a condição, quando a condição
é «não o aplicamos».

Uma ferramenta de verificação que dá falsos alarmes é pior do que não haver nenhuma, porque ensina a
ignorá-la. Corrigido: a promessa passou a ser verificada pelo que diz, e a presença do mapa passou de
falha a informação.

### E a informação era boa

O mapa do HAL é **17×13 com ganhos de 1,000 a 1,000** — identidade. Ou seja: o HAL declara
`SENSOR_INFO_LENS_SHADING_APPLIED = true`, entrega um mapa que diz que não corrigiu nada, e mediu-se em
chapa que de facto não corrigiu. A declaração é falsa e o mapa confirma-o.

Fecha também uma pista que eu tinha em aberto: usar o mapa do HAL como segunda medição independente da
vinhetagem, para resolver a questão da uniformidade de cor. **Não serve** — não há informação lá.

---

## Depois das fases: dívidas pagas e a UI

### A orientação do ficheiro

Escrevia-se sempre `1`, «normal», e por isso **todas** as fotografias tiradas em retrato saíam viradas
de lado em qualquer visualizador. O mosaico vem na orientação do sensor, que é deitada, e é a etiqueta
que diz ao visualizador como a pôr de pé.

Verificado nos dois ficheiros: **DNG `Orientation [6]`** e **TIFF revelado `6`**. A etiqueta vem da
**mesma** rotação que o visor usa para desenhar — se divergissem, o visor mostrava direito e o ficheiro
saía deitado. Vai também ao sidecar, porque o TIFF é revelado mais tarde e precisa dela.

### A câmara passou a lembrar-se

Cada abertura do visor punha o Kelvin em 5500, o modo em P, a tinta em zero. Uma câmara fica onde a
deixámos. Confirmado no telefone: fechar e reabrir devolveu **3126 K** e os picos ligados.

Guarda-se o que o utilizador **escolheu** e nunca o que o fotómetro decidiu: o tempo e o ISO em
automático são resultado de medição, e reabrir com os valores da última cena seria começar com a
exposição de outra luz. Em manual já são escolha, e aí guardam-se.

### Compensação de exposição

De −3 a +3 EV, no mesmo comando — que passou a ter quatro posições: foco, Kelvin, tinta, EV.

Desloca o **alvo** do fotómetro, não o resultado. Somar ao fim daria o mesmo número em automático mas
mentiria em manual, onde o conselho mostrado tem de ser o conselho para o alvo escolhido. Quatro testes,
incluindo o que verifica que um stop de compensação é mesmo o dobro da luz no ficheiro.

### Item que se descobriu não fazer sentido — e que depois se fez pela via certa

Unificar a leitura do sidecar em `org.json` era da lista. Investigou-se e a premissa estava errada: o
`org.json` **não existe nos testes de unidade**, portanto o sítio que usa expressões regulares é
precisamente o que é testável na JVM. Unificar perderia testes. A consolidação certa seria um leitor
próprio em Kotlin puro, e isso é mais do que uma limpeza.

Feito na revisão de código que veio a seguir, e está descrito lá: `export/SidecarRead.kt`, em Kotlin
puro e com as chaves partilhadas com quem escreve. A parte que percorre a árvore para **mostrar** ficou
no `org.json`, porque essa não precisa de teste — se falhar, o utilizador vê que falhou.

### A UI, a partir do desenho no Figma

O `.fig` é um ZIP com o `canvas.fig` em **kiwi comprimido com zstd** — extraiu-se o texto todo do
desenho e reviu-se contra o que está medido.

Adoptou-se: **telemetria em campos etiquetados** em vez de uma frase corrida, porque uma linha corrida
obriga a ler tudo para achar um valor e a grelha lê-se de relance; **avisos primeiro e só quando são
verdade** — um aviso permanente deixa de ser aviso; o **disparo antes do visor**, porque é o disparo que
fica no ficheiro; e o **realce em ciano** em vez de laranja, porque o ciano quase não ocorre em cenas
naturais e num pôr do sol um realce laranja desaparece dentro da cor da cena.

Recusou-se, e é o mais importante da revisão: **temperatura do sensor**, **corte por canal R/G/B**,
**`SHADOW DEVIATION`** e **`DEVELOPMENT CURVE: LOG_SENS_V2`**. São campos que a aplicação não pode
preencher, e o último implica uma curva de revelação com nome — processamento que não fazemos. Era
exactamente a promessa sem prova que este projeto existe para não ter.

Fica para o desenho corrigir: os mockups foram gerados em **16:9** e o sensor é **4:3**, portanto a área
da imagem tem forma diferente da que a aplicação mostra sempre e o *chrome* foi disposto em volta dela.
É a correcção a pedir primeiro, porque tudo o resto se reposiciona depois dela. Mais dois erros de texto:
`KELDVIN`, e `ISO 100` num sensor cuja base é 25.

### Revisão de código: o que estava mal nas 5900 linhas

Passagem automática à procura de código morto — funções, classes, constantes de topo, imports — e
leitura à mão do laço do visor, do GL, do fotómetro, da câmara e da biblioteca. **Não havia estrutura
morta**: nenhuma classe sem uso, nenhuma constante de topo sem uso. Havia isto.

**Uma causa que eu próprio tinha desmentido continuava escrita como facto, em quatro sítios.** Que
`ERROR_CAMERA_DISABLED` num Samsung «é o ecrã a apagar-se». Estava no `CameraSession`, no
`MainActivity`, no `ENTREGA.md` e neste ficheiro — **oito linhas abaixo** da conclusão certa, que diz
que o erro vinha de *nós* fecharmos a sessão e que o logcat mostrou 313 frames produzidos com o ecrã
aceso. É a pior categoria de defeito que este projeto pode ter: não faz a aplicação funcionar mal, faz
a documentação mentir. O `FLAG_KEEP_SCREEN_ON` ficou, porque é certo por si — uma exposição de 1,8 s
não pode depender do temporizador do ecrã.

**A exposição que o ficheiro vai levar era calculada em dois sítios**, por duas cópias da mesma regra:
uma no `disparar`, outra na telemetria que a anuncia. Mexer numa deixava o visor a dizer um valor e o
ficheiro a levar outro — precisamente o que esta aplicação existe para não fazer. Agora é a
`exposicaoDoDisparo`, e as duas chamam-na.

**Escritas em disco dentro do laço de render.** Em modo M, o laço fazia dois
`SharedPreferences.edit().apply()` por frame — cerca de cinquenta por segundo, e sempre com o mesmo
valor, porque em manual a exposição não muda sozinha. Os comandos do anel gravavam a cada tique da
barra: um arrasto de ponta a ponta chegava a mil escritas. Passou a haver um `guardarEscolhas`, chamado
no fim do gesto e ao sair de cena.

**Quatro chamadas de GL por frame para não mudar nada.** O `present` repunha filtro e *wrap* na textura
de apresentação a cada frame; estado de textura é pegajoso e pertence ao arranque. Mais sete pesquisas
numa tabela de cadeias por frame, para localizações de uniformes que são fixas desde a ligação do
programa — agora em campos. Nada disto se via no contador de fps; corrige-se porque é gratuito e porque
os comentários do próprio ficheiro prometem que estas coisas se fazem «uma vez por mudança, nunca por
frame».

**Três leitores do mesmo sidecar**, dois deles com a mesma expressão regular copiada palavra por
palavra, e o ficheiro lido **três vezes** do disco numa só exportação. Consolidado em
`export/SidecarRead.kt`, com um mecanismo único de tolerância a acentos. Isso **corrigiu um defeito
verdadeiro**: o leitor da biblioteca casava as chaves da receita à letra, e num sidecar reescrito por
uma ferramenta que escapa o não-ASCII — o nosso `tools/develop.py` faz isso — a receita não era
encontrada e a revelação seguia com 0 EV e 5500 K, sem aviso. O id da câmara escapava por acaso, por
ter tido a sua própria expressão tolerante. Cinco testes novos, um deles exactamente sobre este caso.

**Os nomes dos campos que se voltam a ler passaram a constantes partilhadas** (`SidecarKeys`). Antes
estavam à letra nas duas pontas: renomear um campo compilava, passava nos testes, e o efeito era o
negativo revelar com as omissões em silêncio.

**Comentários que mentiam sobre o próprio código.** O `Meter` dizia «poucos milhares de leituras» e
«não custa nada mensurável»; são 97 920 amostras por frame, 0,78% do frame — e o `@param` do mesmo
ficheiro dizia «195 mil», errado pelo dobro. Duas estimativas contraditórias e nenhuma certa. O custo
real continua aceitável; o número é que não era. Mais: o leitor da biblioteca afirmava que «não é
preciso escapar nada» logo abaixo de duas expressões suas que fugiam aos acentos; o `Experiments` dizia
«as oito perguntas» e são nove; e o `descrever` tinha dois comentários para a mesma decisão, o segundo
a substituir o primeiro.

**Código morto:** dois restos de andaimes, `RawReader.copyBytes` e `GlPreview.readback`, e cinco
imports. Mais uma fuga de dois objectos de shader no caminho de erro do `Gl.compile`.

**O limiar do aviso de divergência passou a stops.** Era «mais de 10%», que acendia a mostrar «disparo
1/25 s · visor 1/30 s» — um terço de stop, que não interessa a ninguém. Um aviso que aparece quando não
importa gasta a atenção que faz falta quando importa. Meio stop.

**Os avisos olhavam para a exposição errada.** Encontrado no telefone, ao verificar o resto: um disparo
a 1/30 s aparecia com `EXPOSIÇÃO LONGA` porque o **visor** estava a 1/20 s. O `EXPOSIÇÃO LONGA` e o
`GANHO DIGITAL` são sobre a fotografia — se se pode segurar a máquina à mão, se o ISO do ficheiro passou
o analógico. O visor está travado no tempo de propósito e é mais ruidoso de propósito; avisar sobre ele
é avisar sobre uma coisa que não vai a ficheiro nenhum. Era a mesma família do defeito das duas cópias
da exposição, e só apareceu porque a correcção dessa pôs a exposição do disparo à mão de todos.

#### O que a revisão encontrou e não se corrigiu

**Três das quatro posições do modo não funcionam como devem.** Descoberto ao investigar porque é que
duas linhas do laço escreviam sempre o mesmo valor. Os modos repartem por quem decide o tempo e o ISO:
em **M** decide o utilizador os dois, em **S** decide o tempo, em **A** decidiria a abertura, em **P**
decide a aplicação. Ora: o anel tem quatro posições — foco, Kelvin, tinta, compensação — e **nenhuma é o
tempo ou o ISO**, portanto M e S não têm com que se operar; e a abertura destas objectivas é fixa, pelo
que **A é literalmente o mesmo código que P** (`ExposureProgram.apply`, o ramo `else`). Só P está
inteiro. Não é limpeza, são peças de UI que faltam, e vão com a fase da UX.

**O `MainActivity` são 530 linhas de consola de diagnóstico, e é ainda o ecrã de entrada.** Não é
código morto — é o banco de ensaios que validou tudo — mas com a UI nova a aplicação tem de abrir no
visor e isto passa a um ecrã escondido. É decisão de UX, não de revisão.

**O laço do fotómetro não se tocou.** Faz um `Math.sqrt` por amostra para um raio que é o mesmo em
todos os frames, e o `dy²` é recalculado no ciclo interior. É um caminho **medido e validado**, e
reestruturá-lo por 0,2 ms não vale o risco de mudar um resultado que está provado.

#### Verificado no telefone

| O que | Resultado |
|---|---|
| Visor depois das mudanças ao GL | 20–24 fps, cor e enquadramento certos |
| Persistência, com o processo **morto** sem `onPause` | volta com FOCO 0,20 m e 4251 K |
| Limiar em stops | calado com as duas exposições iguais; aparece a 0,585 stops (1/30 contra 1/20) |
| Avisos corrigidos | `EXPOSIÇÃO LONGA` desapareceu de um disparo a 1/30 s |
| Troca de objectiva | ID 2 · 14 mm f/2.2 · FOCO FIXO, com o Kelvin a atravessar a troca |
| Disparo | DNG + sidecar com a receita (4251 K, rotação 90) |
| **Revelar da biblioteca pelo `SidecarRead` novo** | `LTNT_0001….tif escrito · 4251 K · +0.00 EV` — leu a receita, não a omissão de 5500 K |

### A UI, adaptada do desenho em vez de pedida ao desenho

Sem mais rondas ao Figma, o desenho passa a ser referência e a adaptação faz-se no código. Primeiro
passo, a **estrutura em três faixas**: nome e o essencial da exposição em cima, o visor no meio com as
ajudas por cima dele, os instrumentos e os comandos em baixo.

É a estrutura do desenho e é a correcção de dois defeitos vistos no telefone: os botões tapavam a
telemetria quando aparecia um aviso, porque o bloco de texto crescia para cima contra eles. Com faixas,
nada se sobrepõe a nada. A imagem é 3:4 e o ecrã é mais alto — as barras pretas que sobravam passam a
ser onde os instrumentos vivem, que é o que uma câmara faz com esse espaço.

**A telemetria passou a campos.** Era uma cadeia com espaços a fazer de colunas, o que tem dois preços:
só alinha em fonte monoespaçada, e quem mostra tem de a voltar a partir para lhe mudar a cor. Agora o
fio de render devolve uma `Telemetria` com campos e o ecrã decide como se vê — grelha de seis, na ordem
em que se pensa uma exposição: tempo, abertura, ISO; foco, temperatura, tinta. Os avisos em âmbar por
cima, a linha do visor em ciano e só quando difere mais de meio stop.

O canal também se separou: **telemetria e mensagens deixaram de partilhar o mesmo `TextView`**. São
coisas diferentes — a telemetria é estado contínuo e substitui-se a cada segundo; uma mensagem é um
acontecimento e fica. Pelo mesmo canal, um «a disparar…» apagava a leitura toda.

Do desenho **não** se adoptou: o histograma «GREEN LOG» (o nosso é linear), o ganho digital em dB (a
aplicação fala stops e ISO), o selo «DNG VER 1.4 COMPLIANT» (não temos como o provar), o `ROLLOFF:
SOFT` (é um número, não um nome), e o `EV COMPENSATION` dentro da receita — a compensação desloca o
alvo do fotómetro antes do disparo, a receita tem o EV de revelação aplicado depois ao ficheiro, e
confundi-los põe a mesma luz duas vezes.

#### O defeito que a estrutura nova trouxe, e que o utilizador viu primeiro

«Sempre que aparece a mensagem corte do sensor a imagem redimensiona.» Estava certo, e era pior do que
parecia: a linha de avisos era `GONE` quando não havia avisos, portanto aparecer um aviso **encolhia a
faixa do visor** — e uma `SurfaceView` que muda de tamanho dispara `surfaceChanged`, que aqui reinicia a
câmara toda. A imagem saltava, e a telemetria ficava presa no primeiro relato porque o fio de render
nunca sobrevivia o segundo que separa dois relatos.

O *chrome* de uma câmara tem **altura constante**: o espaço do aviso está lá sempre, ocupado ou vazio.
`INVISIBLE` e nunca `GONE`, uma linha só em cada campo, e uma rede de segurança no `surfaceChanged` —
superfície igual e tamanho igual não reiniciam nada. Os fps subiram de 21 para 28–30 só com isto.

#### Afinações vindas de olhar para o ecrã

- **Sem barra de título.** Escrevia «Latente · visor» por cima de uma aplicação que já se identifica, e
  custava 150 px. Com isso e com o nome a sair do topo, a imagem passou a **encher a largura** — 1080
  contra 810 px, um terço maior.
- **O rodapé subiu.** Estava por baixo do disparador e repetia o que já se via: a objectiva está no
  botão que a troca, a abertura tem campo na grelha. Ficou `VINH OK · 26 FPS` no canto da primeira
  linha. Esteve um build na linha dos avisos e teve de sair: com dois avisos ao mesmo tempo a linha não
  chegava e saía «CORTE NO S…» — um aviso truncado é pior do que aviso nenhum, porque parece que está
  tudo dito.
- **Avisos curtos**: `APOIAR`, `CORTE`, `DIGITAL`, `LIMITE`, `VISOR +4.6 EV`. «APOIAR» em vez de
  «exposição longa» porque diz o que **fazer** — o facto sozinho obrigava a fazer a conversão de cabeça.
- **A linha de mensagens começa vazia.** Dizia «a abrir…» e ficava a dizê-lo depois de aberto.
- **O botão do modo nascia sempre com «P».** A câmara reabria em S e o botão dizia P.

### Os modos passaram a querer dizer alguma coisa

Era o defeito de fundo: das quatro posições do botão de modo, **uma só estava inteira**. O anel tinha
foco, Kelvin, tinta e compensação, e nenhuma delas é o tempo ou o ISO — portanto o M e o S não tinham
com que se operar, e o A, com a abertura fixa destas objectivas, era o mesmo ramo de código que o P.

**O anel passou a seis posições** — foco, Kelvin, tinta, EV, **tempo, ISO** — e as duas novas **só
aparecem a quem as decide**: em P a aplicação escolhe as duas e o anel salta-as, em S aparece o tempo,
em M aparecem as duas. Oferecer um comando que o modo ignora seria a interface a fingir controlo.
Verificado no telefone: em M o anel percorre `+0.4 EV → Tempo → ISO → Foco → 4098 K → tinta`.

Tempo e ISO em escala **logarítmica**, porque a fotografia é geométrica: entre 1/1000 e 1/500 vai um
stop, entre 1 s e 1,002 s não vai nada. Linear dava noventa por cento do curso aos tempos longos.

**O A saiu do ciclo**, que passou a `P → S → M` — a ordem da entrega de controlo. Fica na enumeração:
se algum dia houver hardware com abertura variável, volta sem se mexer em mais nada. Um `A` guardado
por uma versão anterior entra como P, senão ficava preso.

**Toque longo no botão do anel repõe o parâmetro.** No botão que o escolhe e não noutro, porque é o que
já tem o nome dele escrito. Para o foco, o Kelvin, a tinta e a compensação há um neutro evidente —
infinito, luz do dia, locus de Planck, zero. Para o tempo e o ISO não há, e o que se repõe é **o que o
fotómetro aconselha**, que em manual é a pergunta que se faz mesmo: «e se deixasse a máquina decidir
isto?». Verificado: 2490 K → 5500 K.

**Os avisos deslizam.** Em manual acendem vários ao mesmo tempo e a linha não chega. Cortar o fim
escondia justamente o que não coube; crescer para duas linhas reiniciava a câmara. Ficou um *marquee* —
e com um cuidado que não é óbvio: **só se reescreve o texto quando ele muda**, senão a actualização de
cada segundo reiniciava o deslize e a linha nunca andava.

### O visor estava a esconder o manual

«Parece que se trocar de modo não assumem», disse o utilizador dos toques longos. Não era o reset que
falhava — era o **visor a apagar o efeito dele**, e é o defeito mais sério que apareceu na UI.

Em manual o `ExposureProgram` devolve `residualStops` com o **conselho do fotómetro**, e eu estava a
usá-lo como ganho de apresentação — o mesmo mecanismo que existe para compensar a luz que o *corpo* não
consegue dar quando o visor tem o tempo travado. Só que em manual esses stops não são luz que o corpo
não deu: são luz que o utilizador **escolheu não dar**. Encurtava-se o tempo, o visor clareava a imagem
de volta, e parecia que o comando não fazia nada. O visor estava a corrigir exactamente a fotografia
que existe para mostrar tal como vai ficar.

Em M o ganho de apresentação passou a **zero**. Provado no telefone, e o ensaio isola-o: tempo a
1/10443 s e ISO 1304, com o fotómetro a pedir **+3,67 EV** —

| | brilho médio do ecrã | aviso |
|---|---|---|
| ISO 52, fotómetro a +0,02 EV | 49,0 | — |
| ISO 1304, fotómetro a −0,50 EV | 185,0 | `LIMITE DO SENSOR · CORTE NO SENSOR` |
| 1/10443 s, fotómetro a **+3,67 EV** | **16,3** | `GANHO DIGITAL · LIMITE DO SENSOR` |

O que fecha a prova é o que **não** aparece: com o fotómetro a pedir 3,67 stops, não há
`VISOR AMPLIFICADO` nenhum e a imagem está escura. Com o código anterior teria sido clareada em 3,67
stops e o aviso estaria lá.

Dois defeitos da mesma família, corrigidos ao mesmo tempo: mudar o tempo ou o ISO à mão **passa a
arrefecer o fotómetro** seis frames, como já acontecia quando era ele a mexer — sem isso, os frames
ainda em voo traziam a exposição antiga e ele «corrigia» o que o utilizador acabara de escolher (em S
via-se o ISO a saltar logo a seguir); e a gravação da escolha passou para **depois** de o laço a
aplicar, senão gravava o valor que se acabou de substituir.

### A terceira objectiva traseira: o que a impede, ao certo

Perguntado porque é que o telefone tem três traseiras e a aplicação só usa duas. O relatório do
aparelho, por extenso:

```
id 6 · 66 mm f/2.4 — falta MANUAL_SENSOR, chave SENSOR_EXPOSURE_TIME, chave SENSOR_SENSITIVITY
Só dentro de uma lógica: 5, 6
```

**Correcção de uma afirmação minha:** disse que usá-la seria «gravar uma fotografia mastigada», e está
errado. A lista de bloqueios é construída por ordem — saída RAW, capacidade RAW, MANUAL_SENSOR, chaves
— e as duas primeiras **não aparecem**: a 66 mm declara saída `RAW_SENSOR` e a capacidade RAW. O
mosaico que ela desse seria dados verdadeiros do sensor. O que se perde é **controlo**, não pureza.

Os impedimentos verdadeiros são dois, e o segundo é o difícil:

1. **Sem controlo manual da exposição.** O 3A da câmara decide o tempo e o ISO. Os modos M e S ficariam
   impossíveis nessa objectiva, e o P da aplicação também — o nosso P *escreve* `SENSOR_EXPOSURE_TIME`.
   Seria preciso um caminho de pedido novo, com `CONTROL_AE_MODE` ligado, e dizê-lo no sidecar e no
   certificado: naquela objectiva a exposição não é nossa.
2. **A id 6 não se abre.** Só existe dentro de uma câmara lógica, e um `openCamera("6")` de uma
   aplicação de terceiros falha — já observado neste aparelho e registado em `Body.kt:53-58`. Não há
   «excepção» para abrir: há uma API que não usamos, a de multi-câmara — sessão na lógica id 0 com uma
   `OutputConfiguration` presa à física `6`. Se a Samsung a permite a terceiros, não se sabe; é um
   ensaio de resposta binária.

E há uma coisa que **poderia** de facto torná-la mastigada, e que não se deve assumir num sentido nem
no outro: sem controlo manual, poder-se-ia não conseguir forçar `CONTROL_ENABLE_ZSL = false`, e nesse
caso o frame podia ser uma fusão de vários — o processamento computacional que esta aplicação recusa.

#### O ensaio, e o que ele respondeu

`diag/PhysicalCameraProbe.kt`, botão «Físicas» ou `-e auto fisicas`. Mapeia as físicas que cada lógica
declara, ensaia uma saída RAW presa a cada uma e, se o frame chegar, verifica o ZSL pelo método da
experiência 7 — comparar o `SENSOR_TIMESTAMP` com o relógio no instante do pedido.

**Passou as duas provas.** A lógica 0 declara as físicas 2, 5 e 6, e é multi-câmara lógica.

| | física 5 | física 6 |
|---|---|---|
| distância focal | 5,4 mm | **7,0 mm** (a 66 mm equivalente) |
| maior RAW | 4080×3060 | **3648×2736** |
| capacidade RAW | sim | **sim** |
| `MANUAL_SENSOR` | sim | **não** |
| aceita tempo e ISO | sim | **não** |
| `ForwardMatrix1/2` | sim | **sim** |
| vinhetagem já aplicada | sim | sim |
| nível de branco | 1023 | 1023 |
| **configuração aceite** | sim | **sim** |
| **frame entregue** | 4080×3060 | **3648×2736** |
| atraso do frame | +509 ms | **+545 ms** |
| veredicto ZSL | um frame, tirado agora | **um frame, tirado agora** |

O caminho de multi-câmara **funciona neste corpo**, ao contrário do que eu esperava. A 66 mm entrega
RAW verdadeiro, tem as matrizes de cor que a nossa revelação exige, e o frame é posterior ao pedido —
não vem de cache nem é fusão. A câmara até reporta `CONTROL_ENABLE_ZSL: false`.

De caminho, uma surpresa: a **física 5 tem controlo manual completo** e as mesmas especificações da
id 0 — 5,4 mm, 4080×3060. É quase de certeza o mesmo sensor principal exposto duas vezes; não é
objectiva nova.

#### O que custa admiti-la

Passou as provas, portanto entra. Mas não é uma bandeira — é um caminho novo em cinco sítios:

1. A `CameraSession` tem de saber abrir uma **lógica** e prender saídas a uma física.
2. O `CleanRequest` precisa de uma variante para corpos sem `MANUAL_SENSOR`: exposição automática, e
   tudo o resto que se **consegue** desligar, desligado.
3. O `LensProfile` tem de modelar «a exposição aqui não é nossa».
4. A UI: naquela objectiva o anel perde o tempo e o ISO, e o botão do modo não tem o que oferecer. O
   fotómetro continua a medir o RAW — só que passa a aconselhar sem poder agir.
5. O sidecar e o certificado têm de o dizer. Um certificado que promete controlo manual e inclui uma
   objectiva onde ele não existe é um certificado falso — e o certificado é a peça que dá valor a todo
   o resto.

### A 66 mm entrou

A promessa mudou de forma, e a mudança está escrita: era **«nada é automático»**, passou a **«nada é
automático, excepto onde o telefone não deixa — e aí está escrito»**.

**O bloqueio passou a ser só a falta de RAW.** O controlo manual deixou de recusar uma objectiva e
passou a ser uma característica que ela declara: `LensProfile.manualExposure`. Uma objectiva que dá RAW
verdadeiro mas não aceita o tempo nem o ISO continua a servir para fotografar sem mastigação, que é a
promessa central. O que ela não pode é fingir que a exposição foi nossa.

**Alcança-se pela lógica.** `LensProfile.logicalId` guarda a câmara por onde a física se abre, e a
`CameraSession` prende a saída com `setPhysicalCameraId`. A `usable()` deixou de excluir as físicas por
princípio e passou a excluir as que **duplicam** uma objectiva já na lista — a física 5 declara a mesma
focal e o mesmo RAW da id 0 porque é o mesmo sensor visto de outro lado.

**Duas características, não uma.** A `CameraSession` passou a distinguir as da câmara que **se abre**,
a que o pedido obedece, das da que **produz a imagem**. Não é formalidade: as matrizes de cor e o nível
de branco têm de vir do sensor que tirou a fotografia, e usar as da lógica revelaria o mosaico de uma
objectiva com a cor de outra — erro que passaria despercebido, porque a imagem sairia plausível.

**O pedido pede automático em vez de o desligar.** Sem `manualExposure`, o `CleanRequest` põe
`CONTROL_AE_MODE = ON` de propósito e não escreve as chaves do sensor. Tudo o resto — ruído, nitidez,
ZSL, curva de tons, balanço — continua desligado como nas outras. É a diferença entre «a câmara
escolheu a exposição», que se aceita e se regista, e «a câmara processou a imagem», que não se aceita.

**A telemetria mostra o que a câmara usou**, lido do `CaptureResult` do pedido repetido, e não o que
sugerimos. O campo do modo diz `AUTO`, o anel perde tempo, ISO e compensação, e o botão do modo recusa
com a razão.

Verificado no telefone: **DNG de 20 MB, 3648×2736, id 6**, visor a 20 fps, e o sidecar a dizer
`exposição escolhida por nós = False` e `alcançada pela câmara lógica = 0`.

#### Três mentiras que a 66 mm destapou

Todas do mesmo feitio: código escrito quando só havia objectivas com controlo manual.

1. **O sidecar inventava um pedido.** Escrevia «tempo pedido 2,16 ms» e «cortado pelo HAL: sim» com os
   números que a aplicação tinha em memória — dando a entender que pedimos e fomos cortados, quando
   não pedimos nada. O bloco passou a chamar-se **«O que a câmara usou»** e a dizer só isso.
2. **A calibração do tecto de exposição alastrava a todo o corpo.** `BodyCalibration` devolvia os
   1750 ms medidos na id 0 para qualquer objectiva do SM-S942B, e o sidecar da 66 mm anunciava
   «declarado 100 ms, medido 1750 ms» sobre uma objectiva onde nem se pode pedir o tempo. Uma medição
   vale para onde foi feita.
3. **`LIMITE DO SENSOR` acendia sem querer dizer nada.** O aviso quer dizer «o corpo não consegue dar
   mais luz», e só nos modos automáticos o `residualStops` quer dizer isso — em M e na 66 mm ele traz o
   conselho do fotómetro, que é outra coisa. Acendia sempre que a exposição estava a mais de 0,2 EV do
   alvo, inclusive em manual, onde isso é escolha e não limite.

E o botão do modo escrevia-se só no clique, portanto ficava a mentir quando o modo mudava por outra
via — trocar para a 66 mm deixava lá um «M» por baixo de um `MODO AUTO`. Passou a ser dito pela
telemetria, como tudo o resto.

#### O foco é uma capacidade à parte, e a 66 mm provou-o

«Foco na 66 mm não está a funcionar», disse o utilizador. Estava certo, e a causa é do mesmo feitio das
outras: código escrito quando controlo manual era tudo ou nada.

A 66 mm **tem motor de foco** — declara 2 dioptrias de distância mínima, ou seja 0,5 m — e mesmo assim
`aceita LENS_FOCUS_DISTANCE : false`. Nós mandávamos-lhe `CONTROL_AF_MODE = OFF`, como a todas as
outras, e o resultado era uma objectiva com motor **parada onde tivesse ficado**. O sidecar dizia-o sem
que ninguém reparasse: `foco aplicado 0.000204 dioptrias`, ou seja infinito, em todos os disparos.

São **três estados**, não dois, e o ecrã passou a distingui-los:

| objectiva | foco | o que se mostra |
|---|---|---|
| 23 mm | motor e chave: é nosso | `0.20 m` |
| 14 mm | sem motor, na hiperfocal | `FIXO` |
| 66 mm | motor, mas quem o move é a câmara | `AUTO` |

Sem foco nosso, o pedido liga o `CONTROL_AF_MODE` em vez de o desligar — pela mesma razão que liga o
`CONTROL_AE_MODE`. Verificado: o foco aplicado passou de 0,0002 para **5,64 dioptrias**, ou seja a
câmara focou os 0,18 m que tinha à frente.

Na 66 mm sobram duas pastilhas no anel — **KELVIN e TINTA**. É a leitura correcta do que ali é nosso: a
revelação. E o sidecar deixou de dizer «foco pedido» onde não se pediu nada.

### Os comandos, a partir do desenho

**O disparador é redondo.** Não é enfeite: um botão de disparo tem de se encontrar sem olhar, e com a
máquina ao olho a mão procura uma forma — um rectângulo entre rectângulos não tem forma nenhuma. É por
isso que todas as câmaras do mundo têm ali um círculo. Desenhado num `Canvas`, sem imagem: não tem
dependência, não tem resolução errada em ecrã nenhum, e o núcleo encolhe ao toque e fica vermelho
enquanto a captura corre — que num disparo de 1,8 s é informação e não animação.

**Os parâmetros são uma fila de pastilhas**, e não um botão que cicla. O botão único obrigava a quatro
toques para chegar ao quinto parâmetro e nunca mostrava os outros. A fila mostra de uma vez o que a
objectiva e o modo oferecem — e ensina a diferença entre os modos sem uma linha de texto: **em P há
quatro pastilhas, em M seis, na 66 mm duas**.

**Uma cor por parâmetro, e a barra toma a do escolhido.** Com a máquina ao olho não se lê o nome da
pastilha; vê-se a cor da barra pelo canto do olho e já se sabe o que o dedo vai mexer. As cores não são
arbitrárias: o foco leva o ciano do realce de picos, a temperatura um tom quente porque é disso que
fala, a tinta o magenta do eixo que percorre.

**A barra passou a ter `−` e `+` nas pontas**, e enche como a da luminosidade, sem manípulo. Duas
razões práticas: **precisão** — mil posições num ecrã de 1080 px, com o dedo a tapar onde pousa, fazem
de acertar num terço de stop uma questão de sorte; os botões dão o passo miúdo e repetível, e carregar
quatro vezes é uma intenção enquanto arrastar 4% do ecrã é um acidente. E o **manípulo** da `SeekBar` é
um alvo pequeno que obriga a agarrá-lo antes de o mover; aqui toca-se onde se quer.

**Três menus flutuantes**, ideia do utilizador, e o que os justifica não é o espaço:

- **Ajudas.** Eram quatro círculos com iniciais — P, Z, H, N — e uma inicial não se explica a si
  própria. O menu diz o nome e o que cada uma faz, e deixa **combiná-las**: ver as zebras do corte com
  o histograma ao lado é uso normal, e o botão que ciclava nunca deixou. O botão conta as que estão
  ligadas, senão uma ajuda acesa que não se vê no ecrã é uma ajuda esquecida.
- **Objectivas.** É a razão principal de haver menu: as **recusadas aparecem**, apagadas e com o
  motivo. Uma objectiva que o corpo tem e a aplicação não usa, se simplesmente não aparecer, parece
  defeito nosso. E cada uma diz o que ali não é nosso — `f/2.2 · foco fixo`, `f/2.4 · exposição da
  câmara`.
- **Modos.** Sem o A. Numa objectiva onde a exposição é da câmara os três aparecem apagados com a
  razão, que é mais honesto do que um botão que não responde.

De caminho, um erro de ordem: a 14 mm aparecia como «foco da câmara» quando é **foco fixo**. Sem motor
não há autofoco a trabalhar; há vidro parado na hiperfocal.

Os três botões de menu ficaram no idioma do resto: pílula, contorno fino, e um **triângulo mais pequeno
e apagado** a dizer «abre lista» — um caracter do mesmo tamanho ao lado do nome lia-se como parte do
valor, e «23 MM ⌄» dava a entender que o acento dizia alguma coisa sobre a objectiva. O das ajudas
acende em ciano com a contagem: com o menu fechado, uma ajuda acesa que não se vê é uma ajuda esquecida.

O botão do modo é uma **meia-lua** — plano do lado do disparador, redondo do outro. Um botão de pontas
redondas encostado a um círculo deixa entre os dois uma fresta que se lê como erro de alinhamento;
cortando o lado que dá para o círculo, as duas peças encaixam.

A fila passou a três células com peso igual dos lados e o disparador ao meio. Estava num `FrameLayout`
com as contas à mão e elas não fechavam — o grupo da direita começava aos 198 dp e o disparador acabava
aos 202, ou seja **sobrepunham-se**, e o «P» aparecia meio tapado. Com pesos, a folga reparte-se sozinha.

### A rotação: metade feita

Faltava o mais elementar de uma câmara — **não se podia fotografar em paisagem**. Três linhas de
`screenOrientation="portrait"` no manifesto, e a rotação lida uma vez no arranque.

Feito o que não depende do layout: a rotação passou a **estado vivo** do fio de render, lida do
**acelerómetro** e não do `display.rotation` — a janela continua travada em retrato, portanto o sistema
não roda e é o corpo que se inclina; é a mesma leitura que já alimenta o nível. Com **zona morta de 20
graus** por quadrante, senão um telefone a 45 graus alternava dezenas de vezes por segundo e a imagem
rodava no visor a cada alternância. A `rotacaoDaImagem()` é calculada a cada uso e vai ao mesmo tempo ao
ecrã e à etiqueta do ficheiro — se divergissem, o visor mostrava direito e o ficheiro saía deitado, que
é um defeito que já corrigimos uma vez.

**Por fazer, e é a parte cara:** o *chrome* está desenhado para retrato — três faixas horizontais com a
imagem 3:4 no meio. Em paisagem a imagem é 4:3 e as faixas têm de passar para os lados. Fica para
depois da biblioteca e da análise, senão a decisão de onde vai cada peça toma-se duas vezes.

### Estado

**197 testes, 0 falhas.** APK `latente-f6.apk`. O projeto está em git —
`github.com/bmcsilva/latente` — e o trabalho faz-se no clone.

Verificado no telefone: o ecrã em faixas, os modos, a 66 mm pela câmara lógica, o disparador, as
pastilhas com cor por parâmetro, a barra com `−`/`+`, os três menus, a mordida em entalhe nas quatro
direcções, o arranque com o estado guardado, e **a rotação medida posição a posição** (retrato −2° → Q0 →
`Orientation 6`; deitado para a esquerda −91° → Q90 → `Orientation 1`).

O layout de paisagem funciona e não está acabado: falta a banda por baixo do visor receber a segunda fila
de pastilhas, e os três acertos pequenos acima.


### O layout de paisagem, especificado pelo utilizador

O primeiro ensaio foi reagrupar as faixas do retrato numa coluna, e não chegou: as etiquetas quebravam,
o `vinh ok · fps` encavalitava no `cortado`, a fila dos menus ficava cortada em baixo, e as meias-luas
abraçavam o vazio depois de o disparador ir para o bordo. **Não é reflowar, é outra disposição** — foi o
utilizador a dizê-lo e a desenhá-la.

O esquema a seguir, com as peças que já existem:

- **Visor à esquerda, altura total**, em rectângulo de cantos redondos com contorno fino.
- **Coluna à direita**, e nela por esta ordem:
  1. Bloco de dois por dois: `modo` / `cortado` na primeira linha, `margem` / `vinh ok · fps` na
     segunda. O botão **`ajudas` no canto superior direito** da coluna, e não numa fila em baixo.
  2. A linha de aviso com uma **barra vertical de acento** antes do texto.
  3. A grelha em **duas colunas por três linhas** — `tempo`/`abertura`, `iso`/`foco`, `temp.`/`tinta` —
     e não três por duas: é o que dá largura para as etiquetas não quebrarem.
  4. O **disparador no bordo direito**, à altura do meio da coluna. Já está feito.
  5. A barra do parâmetro, mais estreita.
  6. As pastilhas em duas filas de três, com os botões **`M` e `23 mm` encostados à direita**,
     alinhados com as duas filas.
- **Etiquetas em minúsculas** e mais pequenas do que no retrato.

O que isto obriga, e é a razão de não ser um ajuste: o bloco do topo e a grelha são construídos uma vez
no `onCreate` e passam a ter de ser **reconstruídos por orientação**, como já acontece com as
pastilhas. As meias-luas voltam a pastilhas normais em paisagem, porque a mordida só faz sentido
encostada ao disparador.

#### A mordida como entalhe, e uma conclusão minha que estava errada

Pedidas as luas verticais — `AJUDAS` mordido na base, `MODO` no topo — e ficaram **rectas**.

Diagnostiquei e **concluí mal**: escrevi aqui que uma mordida rasa com o raio do disparador era
impossível numa aresta larga, e demonstrei-o com contas. As contas estavam certas; a premissa é que não.
Eu estava a fazer o arco atravessar a aresta **de uma ponta à outra**, e é isso que obriga o raio a ser
maior do que metade dela — numa aresta de 80 dp com o raio de 39 do disparador, o `asin` saturava, o
arco degenerava num semicírculo, saía da caixa e era cortado.

O utilizador não aceitou a conclusão — «a mordida é para ser tal e qual» — e tinha razão. A mordida não
precisa de atravessar a aresta: é um **entalhe**. Recto, arco, recto. O arco vive só onde o círculo entra
de facto no botão e o resto da aresta fica a direito.

Com isso a curva **é** a do disparador — mesmo raio — em arestas de qualquer comprimento, e a mordida
fica igual em retrato e em paisagem porque passou a ser literalmente a mesma conta. Deixou de haver caso
especial por direcção: uma forma, quatro matrizes.

Fica registado porque o erro é instrutivo: uma demonstração correcta sobre uma premissa que ninguém
questionou parece prova. A pergunta que faltava não era «quanto tem de ser o raio», era «porque é que o
arco atravessa a aresta toda».

#### Maiúsculas, e o tamanho em paisagem

As etiquetas passaram a minúsculas seguindo o esquema do utilizador, e voltaram a maiúsculas por decisão
dele depois de as ver. Não houve mudança de fonte em momento nenhum — as etiquetas são sans-serif e os
valores monoespaçados, como sempre foram; o que mudou foram as palavras.

Em paisagem os valores estão a 14 sp e as etiquetas a 8, contra 16 e 9 em retrato. É aritmética e não
gosto: cinco linhas de campos a 16 sp, mais o aviso, a barra e duas filas de pastilhas, pedem 1179 px de
uma coluna que tem 1080.

#### Os botões flanqueiam o disparador, também em paisagem

Ideia do utilizador. O que em retrato o flanqueia — ajudas de um lado, modo e objectiva do outro — passa
a flanqueá-lo em coluna, acima e abaixo. É a mesma vizinhança vista de lado, e não uma disposição nova
para decorar. De caminho tirou três botões da coluna, que era onde faltava altura.

#### O que a banda por baixo do visor tem de receber — feito, ver a secção seguinte

A imagem encostada ao topo já está feita, mas a ideia só rende se a banda que sobra **receber
controlos**. Neste momento o preto que sobra está na coluna da imagem e a falta de altura está na coluna
dos comandos — são sítios diferentes, e por isso não se compensam.

Falta: o topo do visor alinhado com a linha do `MODO`, e a **segunda fila de pastilhas por baixo do
visor**, à largura dele. A banda passa a ser uma quarta zona do ecrã, e não sobra.

E é decisão, não detalhe: a fila de pastilhas passa a viver **fora** da coluna, e a coluna deixa de ter
as seis num sítio só. Ganha-se altura e perde-se ter tudo junto. A alternativa é encolher o bloco de
cima, mas aí sai a `margem` ou o `cortado` — e esses decidem a fotografia.

Ficam por acertar, todos diagnosticados: a compensação aparece colada à tinta (`+0.00  -1.1 EV`) por
falta de largura; o `VINH OK` e o `CORTE NO SENSOR` continuam em maiúsculas porque são construídos no
fio de render e não no ecrã; e falta a barra de acento antes do aviso.

### A banda por baixo do visor, e a mordida que não curvava

Duas coisas, e a segunda é geometria pura.

#### A banda passou a ser a quarta zona do ecrã

A caixa do visor deixou de ter a altura da coluna e passou a ter a **proporção do mosaico** — uma
`AspectBox`, que se mede pela imagem e não pelo que sobra. Foi isso que faltava: as pastilhas postas a
seguir apareciam depois de um vão de preto, porque a caixa era alta e a imagem não. Agora o que vem a
seguir fica encostado ao fundo da imagem, sem contas na actividade.

Com a banda a ter a largura da imagem, as seis pastilhas cabem **numa fila**. Iam a duas filas de três
por viverem na coluna estreita, e essas duas filas eram altura roubada ao visor.

O topo da imagem alinha com a **linha do texto** `MODO`, e o desvio conta-se antes de medir: o
preenchimento da faixa mais o vão que a fonte deixa entre o topo da caixa de texto e o topo das letras
(`ascent − top`). Medir depois de as vistas estarem postas obrigava a mexer no preenchimento a
posteriori, e isso muda a altura da caixa do visor — que **reinicia a câmara**. Um segundo perdido e o
fotómetro a começar do zero, a cada rotação.

A imagem ganhou largura de dois lados: a coluna passou de 300 para 272 dp (saindo-lhe as pastilhas, o
que tem de aguentar são dois campos por linha, e «TEMPERATURA» a 8 sp mede 65 dp em células de 113) e a
faixa do disparador de 110 para 96, porque a pilha encolheu para os 76 dp do disparador.

De caminho, dois contentores fixos para as pastilhas e para a fila dos comandos. Voltar de paisagem a
retrato acrescentava a fila no fim da faixa — **por baixo da linha de estado**, que é onde não é.

#### A mordida em paisagem: não era afinação, era a aresta

Estava a ler-se recta, e a conta diz porquê: o arco cobria **56% da aresta** de 84 dp e as duas pontas
ficavam a direito. O círculo que morde tem 39 dp de raio, ou seja 78 de diâmetro, e um arco desses não
atravessa uma aresta de 84 — não há profundidade que o resolva.

Em retrato a aresta mordida é a **altura**, 44 dp, e o círculo atravessa-a inteira com 8 dp de fundo:
por isso é que ali a aresta curva toda. Foi o utilizador a escolher a saída honesta: em paisagem a
pastilha encolhe para os **76 dp do disparador** e cresce para 64 de altura. A corda passa a valer a
aresta toda, o centro do círculo fica 10,5 dp fora da pastilha, e 39 − 10,5 dá os **28 dp** que a
concavidade come — e que a pastilha sobe para encostar ao disparador. A curva é a dele, a folga é de
2 dp de ponta a ponta, e as duas peças encaixam como em retrato. Custa duas pontas a passar o círculo,
que é o preço de a curva ser mesmo a do círculo.

A alternativa era manter a pastilha em 84×44 e curvar a aresta toda com 8 dp de fundo — mas isso exige
raio 114, o triplo do disparador. Ficava bonito e deixava de acompanhar coisa nenhuma.

E havia um segundo defeito por baixo do primeiro: o preenchimento do botão era posto **na criação**, e o
lado da mordida muda com o ecrã. O mesmo «AJUDAS» é mordido à direita em retrato e na base em paisagem,
mas nasce sempre em retrato — os dois ramos verticais do `when` nunca chegavam a correr, e em paisagem o
texto sentava-se em cima da curva. Passou para o `rotularBotaoDeMenu`, que é quem já sabe o lado.

**Verificado no telefone pelo utilizador**, e aprovado. 197 testes passam.

De caminho, uma armadilha do ambiente que custou uma volta: o `adb install` sem `--user 0` instala no
utilizador que a *shell* tem por omissão, que neste telefone é o **150, a Pasta Segura**. Diz `Success`,
o `dumpsys package` responde *Unable to find package*, e no telefone não aparece ícone nenhum. Está na
ENTREGA, §5.

### O que falta na UI

1. **Confirmar a rotação** no telefone (acima).
2. **Ecrãs da biblioteca e da análise**, ainda com o aspecto antigo: lista de texto, sem a miniatura, a
   data e as etiquetas DNG/RCP/TIFF que o desenho tem.
3. ~~**Layout de paisagem**~~ — feito e aprovado no telefone. Ver «A banda por baixo do visor».
4. **Três acabamentos diagnosticados**, todos pequenos: a compensação aparece colada à tinta
   (`+0.00  -1.1 EV`) por falta de largura; o `VINH OK` e o `CORTE NO SENSOR` continuam em maiúsculas
   porque são construídos no fio de render e não no ecrã; e falta a barra de acento antes do aviso.
5. **O ícone da gaveta abre o ecrã das experiências**, e a câmara é um botão lá dentro. Foi assim desde
   a F1 e nunca foi decidido — é decisão a tomar, não defeito.

Nota de método: o modo pode não sobreviver a um `am force-stop` porque o `apply()` das preferências é
assíncrono e o processo morre antes de o disco ser escrito. É artefacto do ensaio, não do uso — ao sair
de cena a plataforma esvazia a fila.

---

## Decisões tomadas

| Decisão | Razão |
|---|---|
| Pacote `io.github.bmcsilva.latente` | tudo em nome próprio |
| AGP 8.13.2, Kotlin 2.2.21, Gradle 8.14.3, JDK 21 | versões confirmadas nos repositórios, não adivinhadas |
| `compileSdk 36`, `minSdk 31`, bytecode 17 | §4.3 da especificação |
| Zero dependências de runtime | JUnit apenas em testes |
| UI em Views puros, sem Compose | é diagnóstico, não produto |
| Nomes de constantes por reflexão sobre `CameraMetadata` | evita transcrever inteiros à mão; obriga a `isMinifyEnabled = false` |
| Teste de abertura em passagem separada | é lento, exige permissão e pode falhar |
| Veredicto separa bloqueante de desejável | ver «Defeito encontrado» |

### Correcções à especificação vindas da execução

Aplicadas; o documento está na versão 4.

1. **§3.2** — não existe chave pública `REQUEST_AVAILABLE_REQUEST_KEYS`; usam-se os métodos
   `getAvailableCaptureRequestKeys()`, `getAvailableCaptureResultKeys()` e
   `getAvailableSessionKeys()`.
2. **§3.2 e §10.4** — `SENSOR_INFO_LENS_SHADING_APPLIED` declara directamente se o HAL corrigiu
   vinhetagem no RAW.
3. **§2.4, nova** — valores medidos no dispositivo de referência e critério de aptidão corrigido.
4. **§11 reescrita** — cada risco marcado como confirmado, afastado ou por verificar.

### Apostas feitas antes de ver os dados

Ficaram escritas na versão anterior deste documento. Resultado: três em quatro.

| Aposta | Resultado |
|---|---|
| Ultra-grande-angular e tele sem RAW | **errada** — ambas têm RAW; a tele cai por não ter exposição manual, a ultra-grande-angular serve |
| RAW *binned* a ~¼ dos MP anunciados | certa |
| Tecto de exposição bem abaixo dos 30 s do modo Pro | certa, e pior do que se esperava: 1/10 s |
| `SENSOR_INFO_LENS_SHADING_APPLIED` a `true` | certa |

Não previsto: o RAW de 10 bits.

---

## F1 — Captura RAW crua

Projeto novo em `latente-app/`, separado da sonda. É a aplicação real a começar, não outra
ferramenta de diagnóstico.

### O que existe

```
latente-app/
  app/src/main/kotlin/io/github/bmcsilva/latente/
    model/Exposure.kt        o que o utilizador decide; nada automático
    model/LensProfile.kt     uma objectiva é uma câmara física; tudo lido do dispositivo
    model/Body.kt            enumera lógicas e físicas; separa as que abrem das que não abrem
    camera/HalClamp.kt       pedido vs aplicado, em funções puras e testáveis
    camera/Planner.kt        o nosso corte, antes de o HAL ver o pedido
    camera/CleanRequest.kt   o pedido limpo: tudo o que o ISP faria, desligado
    camera/CameraSession.kt  sessão RAW com API bloqueante; assenta antes de disparar
    camera/RawReader.kt      estatísticas sobre o mosaico, sem demosaicing
    export/DngWriter.kt      DngCreator → MediaStore
    export/Sidecar.kt        o que o DNG não guarda
    export/MediaStoreOut.kt  escrita em Downloads/Latente
    export/Report.kt         escritores JSON e texto (partilhados com a sonda)
    diag/Experiments.kt      as oito experiências
    ui/MainActivity.kt       três botões; andaime, substituído em F3/F4
```

### Decisões de implementação

| Decisão | Razão |
|---|---|
| API bloqueante na sessão | a F1 é verificação passo a passo; sequência explícita raciocina-se melhor que uma teia de *callbacks* |
| `TEMPLATE_MANUAL` | o único template que não injecta automatismos |
| Assentar antes de disparar | com exposição manual os primeiros frames trazem os valores anteriores |
| Parar o repetido e esvaziar a fila antes do disparo | garante que o frame recolhido é o da captura e não um do visor; depois emparelha-se pelo `SENSOR_TIMESTAMP` |
| Definir só chaves declaradas | e registar no sidecar as que ficaram de fora |
| UI em Views puros | andaime de verificação; investir em UI que vai ser deitada fora é desperdício |
| Tudo para `Downloads/Latente` | trivial de ir buscar por adb ou MTP para verificar com `exiftool` |
| Arranque automático por intent | `-e auto experiencias` permite correr a verificação sem tocar no ecrã |

### Verificação

| O que | Resultado |
|---|---|
| `:app:assembleDebug` | BUILD SUCCESSFUL, zero avisos |
| APK de depuração | 2,5 MB |
| Testes unitários JVM | **27 testes, 0 falhas** (9 escritores + 12 corte de limites + 6 balanço de brancos) |
| **Corridas no telefone** | **três, por instalação à mão** — experiências 1, 3 e 8 respondidas; 5 bugs meus encontrados e corrigidos |

Os testes de `HalClamp` usam os limites reais do SM-S942B: pedir 1 s dá 100 ms com a bandeira de
corte levantada, ISO 12800 dá 3200, foco 25 dioptrias dá 10. A lógica opera sobre primitivos de
propósito, para ser testável sem dispositivo — `android.util.Range` não existe em testes de JVM.

### Como correr

```bash
cd ~/Workspace/Projects/latente/latente-app
export JAVA_HOME=/home/bruno/Android/Jdk/jdk-21.0.11+10
export ANDROID_HOME=/home/bruno/Android/Sdk
./gradlew :app:assembleDebug

ADB=/home/bruno/Android/Sdk/platform-tools/adb
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell pm grant io.github.bmcsilva.latente android.permission.CAMERA

# as oito experiências, sem tocar no ecrã
$ADB shell am start -n io.github.bmcsilva.latente/.ui.MainActivity -e auto experiencias

# um disparo
$ADB shell am start -n io.github.bmcsilva.latente/.ui.MainActivity -e auto disparar

# trazer os ficheiros
$ADB pull /sdcard/Download/Latente ./resultados
```

### Corridas no telefone

Duas corridas feitas, por instalação à mão (o adb não se conseguiu manter ligado). Três
experiências respondidas, três bugs meus encontrados e corrigidos.

#### 1. Tecto de exposição — RESPONDIDA, e ao contrário do que se pensava

| Pedido | Aplicado |
|---|---|
| 50 ms | 50,0 ms |
| 100 ms | 100,0 ms |
| **250 ms** | **250,0 ms** |
| **500 ms** | **500,0 ms** |
| **1000 ms** | **1000,0 ms** |
| 2000 ms | sem resposta em 14 s |
| 4000 ms | sem resposta em 20 s |

**`SENSOR_INFO_EXPOSURE_TIME_RANGE` subdeclara em 10×.** Declara 100 ms e o HAL honra pelo menos
1 s, exactamente como pedido. O `MAX_FRAME_DURATION` de 142,9 ms também não é vinculativo.

**A exposição longa existe neste telefone.** Fotografia nocturna volta ao âmbito do projeto.

Consequência de desenho: **o limite descobre-se por sondagem, não por leitura.** A aplicação deve
sondar uma vez, guardar o resultado por modelo, e oferecer até ao valor provado.

Afinado em três passos entre 1 s e 2 s:

| Pedido | Aplicado |
|---|---|
| 1250 ms | 1250,0 ms |
| **1750 ms** | **1750,0 ms** |
| 2500 ms | sem resposta em 15,5 s |

**O limite real é 1,75 s — 17,5× os 100 ms declarados.** O dispositivo não morre com pedidos acima:
simplesmente não entrega frame. Confirmado em duas corridas independentes, com a lente tapada e a
15 cm do tampo da mesa.

> Correcção a um erro meu: a v6 da especificação afirmava o oposto — que o tecto era real e que
> pedir acima dele derrubava a câmara. Era falso duas vezes. Na primeira corrida os pedidos de
> 250 ms e acima falhavam por causa de uma sessão partilhada já morta; na segunda, os 2 s falharam
> porque o meu tempo de espera era 6 s para uma exposição de 2 s × 3 frames.

#### 3. RAW + preview na mesma sessão — RESPONDIDA, e é boa notícia

RAW de 12,5 MP mais YUV 1920×1080 na mesma sessão: **aceites, e a entregar frames**. Apesar de
`REQUEST_MAX_NUM_OUTPUT_RAW = 1`.

**O visor WYSIWYG é possível.** A F3 está desbloqueada.

#### 8. Ponto neutro contra ganhos de cor — RESPONDIDA, e resolve um problema

| Ganhos RGGB pedidos | Ponto neutro devolvido |
|---|---|
| 1,0 / 1,0 / 1,0 / 1,0 | 1,0 · 1,0 · 1,0 |
| 2,0 / 1,0 / 1,0 / 0,5 | 0,5 · 1,0 · 2,0 |

**`COLOR_CORRECTION_GAINS` determina `SENSOR_NEUTRAL_COLOR_POINT`, e é exactamente o seu
recíproco.** Como o `DngCreator` deriva o `AsShotNeutral` do ponto neutro, o balanço de brancos
escolhido pelo utilizador **chega ao DNG** — basta calcular os ganhos do iluminante pretendido.

E é honesto: com AWB desligado os ganhos aplicam-se às saídas processadas, não ao RAW. O mosaico
fica intocado e são só os metadados a declarar a intenção. Não é preciso escrever o DNG à mão, como
a v5 da especificação temia.

#### Três bugs encontrados e corrigidos

**1. Falha em cascata por sessão partilhada.** As experiências 2 e 4 a 8 devolveram
`IllegalStateException: CameraDevice was already closed`. A experiência 1 matava o dispositivo e as
sete seguintes usavam a mesma sessão morta. Corrigido: **uma sessão por experiência**, e na 1 uma
sessão por valor sondado. A `CameraSession` passou a registar *porque* morreu e a devolver «a câmara
morreu: …» em vez de deixar rebentar uma excepção opaca.

**2. `SENSOR_FRAME_DURATION` abaixo do mínimo do stream — o bug que estragou cinco experiências.**
As experiências 2, 4, 5, 6 e 7 devolveram «resultado sem imagem — o `ImageReader` não entregou», e a
5 alternava isso com «captura falhada, razão 0».

Diagnostiquei mal à primeira: culpei o `ImageReader` por esgotamento de `maxImages`. Corrigi isso
(bandeira `collect`, que fecha as imagens fora do disparo — melhoria legítima mas não era a causa) e
o problema manteve-se **em sessões novas, à primeira captura**, o que desmentia a teoria.

A causa real: punha-se `SENSOR_FRAME_DURATION` igual à exposição. Com exposições curtas — 8 ms na
`defaultExposure`, 1 ms na linearidade — ficava **abaixo da duração mínima do stream RAW**, que é
33,3 ms para 12,5 MP a 30 fps. O HAL descartava o pedido.

O padrão dizia tudo: **passaram só as capturas com 50 ms ou mais.** As experiências 1, 3 e 8
sobreviveram porque a 1 usava exposições longas e as outras duas nunca capturam, só assentam.

Corrigido em `HalClamp.frameDuration`: `max(exposição, mínimo do stream)`, com o mínimo lido de
`getOutputMinFrameDuration(RAW_SENSOR, tamanho)` — 33,33 ms neste telefone. E não se limita ao
`SENSOR_INFO_MAX_FRAME_DURATION` declarado, porque também esse é subdeclarado.

**Mas não era a única causa.** Com o piso corrigido, as cinco experiências continuaram a falhar da
mesma maneira.

**3. Parar o pedido repetido para disparar.** A causa que sobrava. O `captureOne` fazia
`stopRepeating()` e submetia a captura logo a seguir; o pipeline ainda estava a esvaziar e o HAL
descartava o pedido — `onCaptureFailed` razão 0, ou um resultado sem imagem nenhuma. A alternância
entre os dois sintomas na experiência 5 era o sinal.

O padrão correcto do Camera2 é **manter o repetido a correr e intercalar a captura**, identificando
depois a imagem pelo `SENSOR_TIMESTAMP`. Corrigido, mais uma salvaguarda: se não se conseguir
emparelhar, aceita-se o frame mais recente e **diz-se no relatório** que não foi emparelhado — todos
os frames da sessão correm com os mesmos parâmetros manuais, portanto servem para medir, mas a
experiência 7 (ZSL) passa a declarar-se inconclusiva nesse caso, porque aí o timestamp é a prova.

**3. Tempo de espera fixo na sonda de exposição.** 6 s de espera para 3 frames de 2 s. Foi isto que
produziu a conclusão errada acima. Corrigido: um frame por sonda e espera proporcional ao tempo
pedido.

**4. Botões inalcançáveis.** Estavam no topo e o título grande do One UI tapava-os. Causa de fundo:
desde o Android 15, com `targetSdk 35+`, o *edge-to-edge* é obrigatório e o conteúdo desenha por
baixo das barras do sistema. Corrigido: relatório em cima, controlos em baixo, e
`setOnApplyWindowInsetsListener` a aplicar os *insets*.

#### 2. Shading — RESPONDIDA: a chave é decorativa

| Cena | `SHADING_MODE` OFF | `SHADING_MODE` FAST | Diferença |
|---|---|---|---|
| tampo de mesa a 15 cm | 0,4925 | 0,4944 | **0,0019** |
| lente tapada | 0,9749 | 0,9629 | **0,0120** |

O resultado confirma nos dois casos que `SHADING_MODE` foi aplicado (0 e 1 no `CaptureResult`) e o
RAW **não muda**. A correcção de vinhetagem do HAL é sempre aplicada, tal como
`SENSOR_INFO_LENS_SHADING_APPLIED` declarava, e a chave não a desliga.

Nota de rigor: a razão cantos/centro em si não mede vinhetagem nestas condições — a cena não era um
campo verdadeiramente uniforme. O que vale é a **diferença** entre os dois modos, e essa é
desprezável.

#### 4. Nível de preto — RESPONDIDA: o pedestal está mesmo subtraído

Frame com a lente tapada:

| Medida | Valor |
|---|---|
| média | 0,405 |
| mínimo | 0 |
| máximo | 6 |
| por posição do mosaico | 0,416 · 0,415 · 0,382 · 0,407 |

Ruído de leitura em cima do zero, uniforme nas quatro posições. **Não subtrair nível de preto na
revelação.** O `SENSOR_DYNAMIC_BLACK_LEVEL` continua ausente, como a F0 previu, portanto usa-se o
padrão estático de zeros.

#### 6. Mosaico de cor — RESPONDIDA: GBRG confirmado

Cena com um objecto cinzento e um laranja:

| Posição | Canal em GBRG | Média |
|---|---|---|
| (0,0) | G | **14,0195** |
| (1,0) | B | 5,876 |
| (0,1) | R | 8,696 |
| (1,1) | G | **14,0165** |

As duas posições verdes coincidem a **quatro algarismos significativos** — é essa a prova. E com o
objecto laranja o vermelho fica acima do azul, como tem de ficar. O *demosaicing* pode confiar no
GBRG declarado.

#### 7. ZSL — RESPONDIDA: respeitado

| Corrida | Atraso do frame |
|---|---|
| tampo a 15 cm | +213,5 ms |
| lente tapada | +187,8 ms |

O frame chega sempre **depois** do pedido. É captura verdadeira, não um frame guardado em cache.
Os ~200 ms são o custo de assentar mais a captura.

#### 5. Linearidade — RESPONDIDA: o RAW é linear

| Tempo | Média | % do branco | Razão |
|---|---|---|---|
| 1 ms | 3,1 | 0,3% | — |
| 2 ms | 6,1 | 0,6% | ×2,00 |
| 4 ms | 12,5 | 1,2% | ×2,03 |
| 8 ms | 25,1 | 2,5% | ×2,01 |
| 16 ms | 50,6 | 4,9% | ×2,01 |
| 32 ms | 101,3 | 9,9% | ×2,00 |
| 64 ms | 203,0 | 19,8% | ×2,00 |
| 128 ms | 399,8 | 39,1% | ×1,97 |
| 256 ms | 625,0 | 61,1% | ×1,56 |
| 512 ms | 840,7 | 82,2% | ×1,35 |

**Sete duplicações consecutivas dentro de 1,5% do ideal**, de 0,3% a 39% do nível de branco. O
sinal cresce em proporção com o tempo: o RAW é linear e o revelador pode tratá-lo como tal.

**Uma pergunta fica em aberto.** O desvio a partir dos 39% tem duas explicações e a medição da
média não as distingue: ou são as altas luzes da cena a cortar progressivamente — normal, e o que
se espera —, ou é um **joelho aplicado no caminho do RAW**, o que deitaria abaixo a premissa do
projeto. Uma curva tão suave parece mais um ombro do que corte.

A experiência passou a medir também o **percentil 20** e a **fracção de píxeis cortados**. O p20 são
os píxeis escuros, que não saturam: se continuar a dobrar enquanto a média abranda, o desvio é da
cena e o sensor é linear. Se o p20 também abrandar longe do tecto, há joelho.

#### Ressalva: a experiência 4 desta corrida não é válida

Deu média 4,14 com as posições do mosaico em 5,46 / 2,32 / 3,33 / 5,46. Aquilo tem **estrutura** —
os dois verdes iguais, azul e vermelho distintos. É imagem, não pedestal: entrou luz. O valor bom
continua a ser o da corrida com a lente tapada (média 0,405, espalhamento nulo).

A experiência passou a detectar isto sozinha: se o espalhamento entre posições do mosaico passar de
0,25, ou o máximo passar de 64, declara **MEDIÇÃO INVÁLIDA** em vez de devolver um número errado
com ar de verdade.

#### Por responder

### O DNG, verificado

Dois disparos feitos e analisados com `tools/dngcheck.py`, um verificador de DNG **sem
dependências** escrito para o efeito — um DNG é um TIFF, basta percorrer os IFD. É também o embrião
da ferramenta de validação da §10.

**Pedido e aplicado coincidem exactamente**: 8 ms → 8 ms, ISO 50 → 50, f/1,8 → f/1,8, foco 0,500 →
0,502 dioptrias. E o `CaptureResult` confirma tudo desligado: `NOISE_REDUCTION`, `EDGE`, `SHADING`,
`COLOR_CORRECTION_ABERRATION`, `CONTROL_MODE`, `AE`, `AWB`, `AF` e `TONEMAP` todos a `0`.

O ficheiro:

| Campo | Valor |
|---|---|
| mosaico | GBRG, tal como declarado |
| nível de branco | 1023 → 10 bits |
| nível de preto | 0, 0, 0, 0 |
| compressão | nenhuma, 23,8 MB |
| `ColorMatrix1/2`, `CameraCalibration1/2`, `ForwardMatrix1/2` | todas presentes |
| iluminantes | D65 e Standard A |
| `NoiseProfile` | presente |
| dados do mosaico | mín 0 · máx 103 · média 15,7 — coerente com 10 bits |
| EXIF | 0,008 s · f/1,8 · ISO 50 · 5,4 mm |

**O `GainMap` é neutro, e isso importa.** O DNG traz quatro opcodes `GainMap` (um por canal) numa
malha de 13×17, e havia o risco sério de dupla correcção: o RAW já vem com o shading aplicado pelo
HAL, portanto um revelador que honrasse o mapa corrigiria a vinhetagem **outra vez**. Os valores
foram descodificados: **1,0000 em toda a malha, nos quatro canais.** O HAL é coerente — aplicou o
shading e declara um mapa identidade. Não há correcção a dobrar.

Há ainda um `WarpRectilinear` em `OpcodeList3`, para distorção, com a bandeira de opcional.

### Critério de aceitação da §9: CUMPRIDO

O **darktable 5.6.0** (instalado por flatpak em modo utilizador, sem root) abre o DNG e revela-o em
1,35 s. Sai uma fotografia a sério, com objectos reconhecíveis. **O ficheiro está bem escrito.**

Automatizado em `tools/verify.py`: metadados, revelação independente e medição do equilíbrio de cor,
num só comando.

```bash
python3 tools/verify.py ficheiro.dng
```

A revelação confirmou também, de forma objectiva, o problema do balanço:

| Medida | Valor |
|---|---|
| média R G B | 9,2 · 37,6 · 4,9 |
| razão R/G | 0,246 |
| razão B/G | 0,129 |
| desvio de cor | **87%** |

São as razões cruas do sensor, sem correcção nenhuma — exactamente o que `AsShotNeutral = [1,1,1]`
manda fazer. A imagem sai verde.

Duas notas sobre os disparos de teste, que não são defeitos do ficheiro: ficaram **desfocados**,
porque a F1 tem o foco fixo em 0,5 dioptrias (2 m) e o objecto estava a ~15 cm; e **escuros**,
porque 8 ms a ISO 50 em interior é pouca luz.

### Corrigido: o DNG saía sem balanço de brancos

`AsShotNeutral` vinha `[1,0, 1,0, 1,0]` — nenhum balanço. Num revelador a imagem sairia verde.

A experiência 8 já tinha dado a solução, e está agora implementada em `model/WhiteBalance.kt`:
Kelvin → cromaticidade (locus de Planck abaixo de 4000 K, locus da luz do dia acima) → XYZ →
espaço da câmara pela `ColorMatrix`, interpolada em 1/T entre os dois iluminantes de referência
como manda a especificação DNG. Daí saem os ganhos, que são o recíproco do ponto neutro.

O `CleanRequest` passa a enviá-los em `COLOR_CORRECTION_GAINS`. **Sem tocar no RAW**: com o AWB
desligado os ganhos aplicam-se às saídas processadas, e o que se ganha é o
`SENSOR_NEUTRAL_COLOR_POINT` de onde o `DngCreator` deriva o `AsShotNeutral`. O mosaico fica
intocado e são só os metadados a declarar a intenção do fotógrafo.

Seis testes novos validam a cromaticidade contra os valores tabelados de D65, D50 e tungsténio.

**Confirmado no dispositivo.** Disparo novo, com 5500 K escolhidos:

| | Antes | Depois |
|---|---|---|
| ganhos enviados | — | R 2,051 · G 1,000 · B 1,685 |
| `SENSOR_NEUTRAL_COLOR_POINT` | 1 · 1 · 1 | 0,4873 · 1 · 0,5938 |
| `AsShotNeutral` no DNG | 1 · 1 · 1 | **0,4873 · 1 · 0,5938** |

A relação recíproca confirma-se ao milésimo: 1 / 2,051 = 0,4876. E a revelação do darktable passou
de verde-fluorescente a **cores correctas** — o cartão branco lê-se branco, os objectos
cor-de-rosa lêem-se cor-de-rosa, o post-it verde-lima está verde-lima.

### Duas lições sobre medir

**O mundo-cinzento dá falsos alarmes.** A primeira versão do `verify.py` julgava o balanço pelas
médias R G B da revelação e acusou 50% de desvio numa imagem visivelmente correcta — a cena era
dominada por uma parede quente e por sombras. O veredicto passou a vir do `AsShotNeutral` do
ficheiro, que é um facto; as médias ficaram como informação.

**A orientação está por fazer.** O DNG sai sempre com `Orientation = 1`, porque a F1 fixa isso no
código. Com o telefone em retrato, o ficheiro sai deitado. Falta ler o sensor de rotação no momento
do disparo — tarefa da F4/F5, agora registada pelo `verify.py`.

Duas confirmações a fazer numa corrida futura, ambas já automatizadas:

1. **O p20 da linearidade**, para fechar a dúvida do joelho.
2. **A experiência 4 com a lente mesmo tapada**, agora que a guarda de escuridão existe.

### Correcções à especificação vindas da implementação da F1

Todas aplicadas ao documento.

5. **§5.2** — o bloco de tonemap não funciona neste dispositivo: não há
   `TONEMAP_MODE_GAMMA_VALUE` nem a chave `TONEMAP_GAMMA`. Usa-se `CONTRAST_CURVE` com curva
   identidade.
6. **§5.4** — o *line time* do sensor não é exposto pelo Camera2, portanto não se pode quantizar
   antes de pedir. Há dois cortes a registar: o nosso e o do HAL.
7. **§8.2** — **o `DngCreator` não permite definir `AsShotNeutral`.** Deriva-o de
   `SENSOR_NEUTRAL_COLOR_POINT`. O balanço de brancos do utilizador não chega ao DNG por esta via;
   fica no sidecar e é o revelador que o aplica. A experiência 8 verifica se os ganhos de cor o
   influenciam.

---

## Pendente

### Correr a F1 no telefone

Critério de aceitação em §9: o DNG abre em darktable, `exiftool` mostra os metadados esperados,
pedido e aplicado coincidem, e a imagem não está com redução de ruído.

**As oito experiências**, todas nascidas do relatório da F0. As de número 1, 3, 7 e 8 não dependem
de condições físicas e podem correr já; as outras precisam de cena preparada.

1. **Pedir 1 s de exposição** e ler o `CaptureResult`. O HAL corta a 100 ms ou aceita? É a
   pergunta mais valiosa que resta.
2. **Campo plano com `SHADING_MODE = OFF` contra `FAST`.** Se os cantos não mudarem, confirma-se
   que `LENS_SHADING_APPLIED` manda e a chave é decorativa.
3. **Combinação `RAW_SENSOR` + preview `PRIVATE` na mesma sessão.** Com uma só saída RAW, é preciso
   confirmar que a combinação é aceite — de outro modo não há visor WYSIWYG.
4. **Foto com a lente tapada.** A média deve dar ~0, confirmando que o pedestal já foi subtraído.
5. **Linearidade** (§10.1), agora sabendo que o tecto é 1023 e não 65535.
6. **Confirmar o CFA GBRG** na principal, fotografando algo saturado e verificando qual o canal que
   responde.
7. **`CONTROL_ENABLE_ZSL = false` honrado?** Comparar o timestamp do resultado com o do disparo.
8. **RAW do id 2** (ultra-grande-angular, `LIMITED`): confirmar que sai sem suavização, apesar de
   não se poder pedir `EDGE_MODE` nem `SHADING_MODE`.

### Depois

Gerar os perfis de corpo e de objectivas a partir do `.json` da sonda, agora que os números são
reais — dois perfis traseiros e dois frontais.
