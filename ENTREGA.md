# LATENTE — Documento de passagem

Para continuar o projeto noutro computador. Escrito para ser lido por quem chega sem contexto
nenhum, incluindo um assistente.

Gerado: 2026-08-03 · actualizado: 2026-08-14, com o negativo em arquivo comprimido e a biblioteca com miniaturas

---

## 1. O que é isto, em cinco linhas

Aplicação Android de fotografia que captura RAW **sem processamento computacional nenhum**: sem
*beautify*, sem nitidez automática, sem redução de ruído, sem tone mapping local, sem fusão
multi-frame, sem o JPEG do sistema. O visor mostra o resultado final, produzido pelo **nosso**
pipeline e não pelo ISP do fabricante — o equivalente ao *Setting Effect ON* de uma Sony. Ergonomia
de câmara a sério: modos M/A/S/P, fotómetro, ajudas de visor. O telefone é tratado como um corpo com
objectivas fixas.

O princípio que manda em tudo: **nunca prometer mais do que o telefone entrega, e dizer sempre o que
o HAL fez pelas nossas costas.**

## 2. Ler primeiro, por esta ordem

| Documento | O que tem |
|---|---|
| `latente-especificacao-tecnica.md` | A especificação, versão 16. §2.4 tem os valores **medidos** do dispositivo de referência; §5.2 o pedido limpo; §6 o pipeline; §9 as fases e critérios de aceitação |
| `latente-progresso.md` | O que está feito, o que foi medido, os bugs encontrados e as lições. É o registo vivo |
| este ficheiro | ambiente, comandos, e o próximo passo |

**Não voltar a derivar o que já está medido.** A §2.4 e o progresso existem para isso.

## 3. Estado

| Fase | Estado |
|---|---|
| **F0** — Sonda de capacidades | **concluída e validada no telefone** |
| **F1** — Captura RAW → DNG | **concluída**: 9 experiências respondidas, DNG aberto no darktable, pedido = aplicado, balanço de brancos correcto no ficheiro |
| **F2** — Revelador | **concluída e validada no telefone**: CPU e GPU concordam a **1 em 255**, sem uma única amostra acima disso; cor a 0,7% / 2,3% de uma implementação independente e cartão de cinza a **0,0%**. GPU 13× mais rápida. **132 testes** |
| **F3** — Visor WYSIWYG | **concluída e validada no telefone**: uniformes ao vivo idênticos aos do ficheiro, cor a **0,7% / 1,9%**, orientação confirmada por correlação (+0,993), enquadramento a 0,7505 contra 0,7500. Disparo a partir do visor com o frame **casado pelo timestamp** nos quatro ensaios, sem interromper o visor. 19–29 fps, limitado pela câmara. **180 testes** |
| **F4** — Câmara | **fotómetro do RAW e linha de programa M/S/A/P, verificados no telefone**: a margem converge de +0,9 para **+0,5 EV** — o alvo exacto — e fica estável; quatro disparos consecutivos com valores idênticos. **182 testes.** Foco manual com realce de picos, verificado contra uma régua: o pico de nitidez cai nos 40 cm medidos e 1/2,498 D = 0,400 m, ou seja a escala bate a três algarismos. Visor e disparo têm tectos de tempo diferentes — 1/8 s e 1750 ms — com o que falta ao visor compensado por ganho de apresentação. **Confirmado**: em cena escura, 7,8 fps contra 0,6 antes, e o disparo leva 1750 ms honrados pelo HAL, casado pelo timestamp, com o visor a 8,0 fps logo depois. Kelvin e tinta em comando próprio, **provados contra folha branca sob lâmpada de 4000 K**: neutro a 1,7% / 4,1%, quando sem o eixo de tinta o melhor possível deixa 10% / 27%. O fotómetro passou a contar com o ganho de vinhetagem, que era o que fazia o RAW sair bom e o visor queimado. Fica aberto: a correcção de vinhetagem **piora** a uniformidade de cor (9,7% contra 4,4% no azul), e resolver isso precisa de um campo plano a sério — ecrã branco com difusor, não lâmpada. Ajudas feitas: zebras sobre o corte **do sensor** (via alfa da textura), histograma do verde do sensor, nível pelo acelerómetro |
| **Arquivo** — o negativo comprimido | Cada fotografia é um `LTNT_….zip` com o `.dng` e a receita lá dentro. **7,0 MB em vez de 24** no dispositivo de referência, e **sem perdas**: o mesmo `md5` depois de descomprimir, e o `.dng` de dentro passa o `dngcheck.py` inteiro. Deflate **nível 4**, medido em quatro negativos — nunca pior do que o 6 e um quarto do tempo. O `dngcheck.py` e o `develop.py` aceitam o `.zip` directamente; o darktable não, e aí descomprime-se primeiro. A cópia revelada passou a ser uma escolha ao revelar: **TIFF 16 bits** (~71 MB, arquivo) ou **JPEG** (~3 MB, para ver e partilhar) |
| F5 — Exportação e biblioteca | **o sidecar reconstrói a revelação**: bloco `Revelação` com a receita, `develop.py --sidecar` a lê-la, e as duas revelações concordam a **0,13% / 0,40%**. Biblioteca e ecrã de análise feitos — a lista mostra uma linha por **fotografia** com os três papéis, e tocar no nome mostra o que o HAL fez pelas costas. **Verificado no telefone ao bit**: revelar da biblioteca produz um TIFF com o nome da fotografia, em 9 s, **idêntico** (diferença máxima 0 de 255) ao do caminho de referência. **Decidido: TIFF16 e nenhum formato comprimido** — AVIF não existe na plataforma e exigiria libavif, contra a decisão de zero dependências. São três ficheiros por fotografia: negativo, receita, cópia revelada |
| **F6** — Verificação anti-mastigação | **certificado feito e corrido**: 11 promessas, cada uma com o valor que a prova, assinado com modelo, build e data. **Todas verificadas** no dispositivo de referência. Botão «Certificado», ou `-e auto certificado` |
| **UI** — visor em retrato e paisagem | **as duas aprovadas no telefone**. Retrato: disparador redondo ao centro, dois botões a encaixar nele pela **negativa do círculo**, pastilhas dos parâmetros, grelha de seis campos. Paisagem: imagem à esquerda com a proporção do mosaico e o topo alinhado com a linha do `MODO`, as seis pastilhas na **banda por baixo dela**, coluna de instrumentos de 272 dp à direita, e o disparador no bordo com as duas pastilhas mordidas por cima e por baixo. A grelha leva **sete campos** — tempo/abertura/iso/**ev** e foco/temperatura/tinta —, a linha de aviso tem barra de acento, e o botão `IR` leva aos negativos e às experiências. Falta a biblioteca e o ecrã de análise, que continuam com o aspecto antigo |

## 4. Ambiente

### O que é preciso instalar

| Ferramenta | Versão usada | Notas |
|---|---|---|
| JDK | 21 (Temurin) | 17 basta; o bytecode alvo é 17 |
| Android SDK | platform **36**, build-tools **36.0.0** | |
| Gradle | 8.14.3 | vem pelo *wrapper*, não é preciso instalar |
| AGP | 8.13.2 | fixado nos `build.gradle.kts` da raiz |
| Kotlin | 2.2.21 | idem |
| Python 3 | 3.11+ | para as ferramentas em `tools/` |
| Pillow (PIL) | 10.2 | só para `verify.py` e `compare.py` |
| darktable | 5.6.0 | opcional, para a verificação independente |

**Não há numpy nesta máquina**, e por isso as ferramentas são Python puro + PIL. Se o novo
computador tiver numpy, `tools/develop.py` pode ser acelerado muito — mas não é necessário.

### Configuração obrigatória no computador novo

Cada projeto Gradle precisa de um `local.properties` **próprio da máquina** (não vai no pacote):

```bash
echo "sdk.dir=/caminho/para/Android/Sdk" > probe/local.properties
echo "sdk.dir=/caminho/para/Android/Sdk" > latente-app/local.properties
```

E o `JAVA_HOME` a apontar para o JDK:

```bash
export JAVA_HOME=/caminho/para/jdk-21
export ANDROID_HOME=/caminho/para/Android/Sdk
```

### darktable, sem root

O `sudo` pedia palavra-passe nesta máquina, e por isso instalou-se em modo utilizador:

```bash
flatpak remote-add --user --if-not-exists flathub https://flathub.org/repo/flathub.flatpakrepo
flatpak install --user -y flathub org.darktable.Darktable
```

## 5. Compilar e correr

### A aplicação (F1 em diante)

```bash
cd latente-app
./gradlew :app:assembleDebug :app:testDebugUnitTest
# APK em app/build/outputs/apk/debug/app-debug.apk
```

**200 testes de unidade** devem passar. Se algum falhar é regressão: todos fixam comportamento
verificado, e vários fixam-no contra a referência em Python que foi validada contra o darktable.

### A sonda (F0, ferramenta de diagnóstico)

```bash
cd probe
./gradlew :app:assembleDebug :app:testDebugUnitTest   # 9 testes
```

### Instalar no telefone

```bash
ADB=$ANDROID_HOME/platform-tools/adb
$ADB install -r --user 0 app/build/outputs/apk/debug/app-debug.apk
$ADB shell pm grant io.github.bmcsilva.latente android.permission.CAMERA
$ADB shell am start -n io.github.bmcsilva.latente/.ui.MainActivity -e auto experiencias
$ADB pull /sdcard/Download/Latente ./resultados
```

**O `--user 0` não é enfeite.** Sem ele o `adb install` instala no utilizador que a *shell* tiver por
omissão, e neste telefone isso é o **150, a Pasta Segura** da Samsung — o `install` diz `Success`, o
`dumpsys package` responde *Unable to find package*, e no telefone não aparece ícone nenhum. Custou uma
volta inteira a perceber. Para confirmar onde ficou:

```bash
$ADB shell pm path io.github.bmcsilva.latente
$ADB shell pm list users
```

**O ícone da gaveta abre o visor.** As experiências e a biblioteca estão no botão **`IR`** do visor. Por
adb, cada ecrã directamente:

```bash
$ADB shell am start -n io.github.bmcsilva.latente/.ui.ViewfinderActivity   # a câmara
$ADB shell am start -n io.github.bmcsilva.latente/.ui.MainActivity         # as experiências
$ADB shell am start -n io.github.bmcsilva.latente/.ui.LibraryActivity      # os negativos
```

Os valores aceites no `-e auto`: `experiencias`, `disparar`, `revelar`, `visor`. Os dois últimos
correm a comparação CPU/GPU da F2 e a medição de viabilidade do visor da F3.

**Sobre o adb:** durante dois dias a ligação caiu constantemente, e o suspeito documentado aqui era o
**Bloqueador automático** da Samsung. **Estava errado: era o cabo.** Fica registado porque a suspeita
custou tempo a quem a leu — incluindo a mim, que pedi para desligar uma protecção que não tinha culpa
nenhuma.

Enquanto durou, o trabalho fez-se **instalando o APK à mão** e partilhando os ficheiros de
`Downloads/Latente/`, e isso funciona bem sem depender do adb. Com cabo bom, o ciclo de instalar,
correr, tirar captura de ecrã e ler o sidecar faz-se todo por adb, que é muito mais rápido.

Os ids dos pacotes: `io.github.bmcsilva.latente` e `io.github.bmcsilva.latente.probe`.

## 6. As ferramentas de verificação

Todas em `tools/`, todas em Python puro (PIL só onde indicado).

| Ferramenta | O que faz |
|---|---|
| `dngcheck.py f.dng` | lê um DNG **sem dependências**: tags, matrizes de cor, opcodes, valores dos `GainMap`, estatísticas do mosaico. Um DNG é um TIFF |
| `verify.py f.dng` | metadados + revelação independente no darktable + veredicto do balanço de brancos. Precisa de PIL e darktable |
| `develop.py f.dng` | **o revelador de referência**. Implementa a §6.1. `--matrix forward\|color`, `--bin`, `--ev`, `--rolloff`, `--kelvin`, `--no-bradford` |
| `compare.py nosso.png dt.jpg` | compara a cromaticidade de duas revelações. É o critério de aceitação da F2 |
| `flatfield.py chapa.dng` | vinhetagem, verificação cruzada do balanço, temperatura da luz. Rejeita as próprias medições quando a assimetria as invalida |
| `flatpair.py a.dng b.dng` | vinhetagem a partir de um par rodado 180°, cancelando o gradiente de luz |
| `focus.py --mapa *.dng` | nitidez pela variância do Laplaciano, medida **no mosaico**; com `--mapa`, onde cada zona fica mais nítida |

### O comando do darktable que importa

A revelação **por omissão** do darktable aplica filmic/sigmoid e `color balance rgb`, e por isso
**não serve de referência de cor**. Uma referência neutra exige:

```bash
flatpak run --filesystem=$PWD --command=darktable-cli org.darktable.Darktable \
  entrada.dng saida.jpg --width 1200 --apply-custom-presets false \
  --core --configdir ./out/dtconf --library :memory: \
  --conf plugins/darkroom/workflow=none
```

Sem o `workflow=none` o desvio em R/G aparece a 8,8%; com ele, a 1,0%. **Uma referência injusta
parece um bug no nosso código.**

Duas notas práticas do flatpak: o `--configdir` e o destino **não podem estar em `/tmp`**, que o
flatpak isola mesmo com `filesystems=host`; e sem `--configdir` próprio a exportação falha com
`can't acquire database lock` se houver outro darktable a correr.

### E o limite do darktable como oráculo

**O darktable valida a ciência da cor. Não valida o pipeline completo.** Medido no mesmo TIFF do
telefone:

| Revelação | R/G | B/G |
|---|---|---|
| a nossa **sem** correcção de vinhetagem, contra darktable | 0,6% | 5,2% |
| a nossa **com** correcção de vinhetagem, contra darktable | 4,6% | 10,8% |
| a nossa **com** correcção, contra a referência em Python | **0,7%** | **2,3%** |

A razão não é bug: o `GainMap` destes DNG é identidade, portanto o darktable **não corrige vinhetagem
nenhuma**, e nós corrigimos com um perfil medido. A correcção é **por canal** — no canto, 6,04× no
vermelho contra 5,16× no verde — e por isso muda a cromaticidade em direcção às bordas, de propósito.

**Não interpretar esta divergência como erro.** O oráculo do pipeline completo é o `tools/develop.py`
com o mesmo perfil de vinhetagem.

## 7. Verdades medidas sobre o dispositivo de referência

**samsung SM-S942B** (`m1s`, `s5e9965`, Android 16, API 36). Não voltar a derivar isto.

### Objectivas

| Câmara | Equivalente | Abertura | Foco | RAW | Veredicto |
|---|---|---|---|---|---|
| id 0 (= física 5) | 23 mm | f/1,8 | 10 cm–∞, OIS | 12,5 MP | **serve** |
| id 2 | 14 mm | f/2,2 | fixo | 12,0 MP | **serve, com reservas** |
| id 6 | 66 mm | f/2,4 | 50 cm–∞ | 10,0 MP | **não serve**: sem `MANUAL_SENSOR`, e não abre |
| id 1 / id 3 | 23 / 30 mm frontais | f/2,2 | 20 cm–∞ | 12,0 / 7,0 MP | servem |

Os ids **5 e 6 não abrem directamente** (`CAMERA_DISCONNECTED`); só existem dentro da lógica id 0.

**Critério de aptidão:** bloqueante é só saída `RAW_SENSOR`, capability `RAW`, `MANUAL_SENSOR`, e as
chaves `SENSOR_EXPOSURE_TIME` e `SENSOR_SENSITIVITY`. `MANUAL_POST_PROCESSING` e o nível `FULL`
**não** são bloqueantes: dizem respeito ao ISP, que não usamos.

### Sensor e limites

| O que | Valor | Consequência |
|---|---|---|
| Nível de branco | 1023 → **10 bits úteis** | o contentor tem 16; não presumir precisão que não existe |
| Nível de preto | 0, 0, 0, 0 | já subtraído; **medido no escuro: média 0,405, máx 6**. Não subtrair na revelação |
| Mosaico | **GBRG** na principal, RGGB nas outras | confirmado medindo os dois verdes: iguais a 4 algarismos. Ler o CFA por câmara |
| Exposição declarada | 100 ms | **mentira: o HAL honra 1750 ms**, 17,5×. Está em `BodyCalibration`, e é o valor medido que a aplicação usa — usar o declarado empurrava a linha de programa para o ganho digital com quatro stops de tempo por gastar. Acima do tecto real o HAL **fecha o dispositivo**, portanto isto tem de vir sempre de medição |
| Duração mín. de frame | **33,33 ms** | piso obrigatório do `SENSOR_FRAME_DURATION`; pedir menos faz o HAL descartar a captura |
| Ritmo real do stream RAW | **36–52 ms por frame**, ou seja 19–28 fps | o piso declarado é um limite do que se pode **pedir**, não uma promessa do que se recebe. Medido em 4 corridas; varia com o estado térmico |
| Carregamento RAW → textura GL | 9–19 ms para 23 MB, **1,3–1,8 GB/s** | cabe inteiro dentro da espera pela câmara; não é o travão do visor |
| ISO | 25–3200, analógico até 640 | acima de 640 é volume, não sinal |
| Saídas RAW simultâneas | 1 | mas **RAW + preview YUV 1080p é aceite e funciona** |
| RAW de resolução total | não existe | `getHighResolutionOutputSizes()` vazio |
| Linearidade | **linear a ±1,5% em 7 duplicações**, de 0,3% a 39% do branco | o revelador pode tratá-lo como linear |
| ZSL | honrado; frame chega ~190 ms **depois** do pedido | captura verdadeira |
| `SHADING_MODE` | **decorativa** — OFF e FAST dão o mesmo RAW | |
| Ciência da cor | completa: dois iluminantes (D65, Standard A), `ColorMatrix1/2`, `ForwardMatrix1/2` | |
| Chaves ausentes | `DISTORTION_CORRECTION_MODE`, `TONEMAP_GAMMA`, `SENSOR_DYNAMIC_BLACK_LEVEL`, `SENSOR_DYNAMIC_WHITE_LEVEL` | usar níveis estáticos; `TONEMAP_CURVE` existe e basta |

### Regras de captura aprendidas a falhar

1. **`SENSOR_FRAME_DURATION` nunca abaixo de `getOutputMinFrameDuration()`.** Custou duas corridas:
   o HAL descarta a captura, umas vezes com `onCaptureFailed` razão 0, outras devolvendo resultado
   sem imagem.
2. **Não parar o pedido repetido para disparar.** Parar e submeter logo a seguir faz o HAL descartar
   o pedido. Manter o repetido e **intercalar** a captura, identificando a imagem pelo
   `SENSOR_TIMESTAMP`.
3. **Assentar é esperar pelos valores, não contar frames.** Contar frames quaisquer deixa passar
   frames velhos e a captura vai a meio da transição.
4. **Mudar parâmetros a meio de uma sessão é frágil.** Ao varrer uma gama, uma sessão por valor.
5. **Definir só chaves declaradas** em `getAvailableCaptureRequestKeys()`, e registar as que ficaram
   de fora.
6. **Não há `TONEMAP_MODE_GAMMA_VALUE`** neste HAL: usar `CONTRAST_CURVE` com curva identidade.
7. **Cortar os valores antes de enviar não é cortesia, é sobrevivência**: acima do limite real o
   frame não chega.
8. **Um visor não consome uma fila.** No disparo o *listener* do `ImageReader` adquire a imagem e
   guarda-a para se emparelhar por timestamp. Num visor isso mata o stream: com três imagens por
   fechar, o `acquireNextImage` recusa, **a notificação perde-se e não volta** — imagem congelada. No
   visor, o *listener* só avisa e quem consome faz `acquireLatestImage`, que traz a mais recente e
   descarta as velhas. É também a semântica certa: mostrar o presente, não uma fila do passado.
9. **Um código de erro não é uma causa.** Atribuí o `ERROR_CAMERA_DISABLED` (3) ao ecrã a apagar-se e
   escrevi-o como facto em dois sítios do código. **Estava errado**: o logcat mostrou 313 frames
   produzidos com o ecrã aceso, e o erro veio de **nós fecharmos a sessão** — era consequência, não
   causa. A aplicação leva `FLAG_KEEP_SCREEN_ON` de qualquer maneira, porque uma exposição de 1,8 s não
   pode depender do temporizador do ecrã. Fica registado porque a explicação errada sobreviveu a duas
   revisões antes de alguém reparar que ela contradizia a nota oito linhas acima.

### Cor, validado

| O que | Resultado |
|---|---|
| `ForwardMatrix` → XYZ D50 → Bradford → sRGB, contra darktable neutro | R/G **0,7%** · B/G 5,3% |
| `inv(ColorMatrix)` → adaptação do branco da cena → sRGB | R/G 0,8% · B/G **1,1%** |
| `WhiteBalance.kt` (Kotlin, telefone) contra Python independente | **0,07%** |

O darktable usa a `ColorMatrix` invertida; a especificação DNG **prefere a `ForwardMatrix`**, que é
o que o Adobe usa e o que o projeto adoptou. Os ~5% de diferença no azul são a distância entre duas
convenções respeitadas, medida duas vezes em assuntos diferentes. **Não é erro.**

A adaptação de Bradford D50 → D65 é **obrigatória**: sem ela o erro salta para 15,9% e 34,4%. A
`ForwardMatrix` mapeia o neutro para o branco D50 — confirma-se somando as linhas: 0,9639 · 1,0000 ·
0,8242.

### O balanço de brancos chega ao DNG

O `DngCreator` **não** permite definir o `AsShotNeutral` — deriva-o de
`SENSOR_NEUTRAL_COLOR_POINT`. Mas `COLOR_CORRECTION_GAINS` **determina** esse ponto neutro, e é
exactamente o seu recíproco (medido: ganhos 2,0/1,0/1,0/0,5 → ponto neutro 0,5 · 1,0 · 2,0).

Portanto: calculam-se os ganhos do iluminante escolhido e enviam-se com
`COLOR_CORRECTION_MODE_TRANSFORM_MATRIX`. Com o AWB desligado os ganhos aplicam-se às saídas
processadas, **não ao mosaico** — o negativo fica intocado e são só os metadados a declarar a
intenção. É honesto e funciona.

## 8. Em aberto

### A vinhetagem — RESOLVIDA para a objectiva principal

Medida com **difusor encostado à lente, apontado a um ecrã branco** (`dados-de-teste/chapa-difusor-id0.dng`):
**a objectiva de 23 mm perde 80% da luz nos cantos**, e o RAW não vem corrigido apesar de o HAL
declarar que sim. Perfil radial por canal em `dados-de-teste/perfis/vinhetagem-id0.json`.

Validada por simetria radial de 0–6% entre pares opostos, repetibilidade de 1% em quatro chapas,
queda monótona, e um perfil 0,70× a lei natural cos⁴θ.

**O pipeline tem de aplicar esta correcção**, e a §6.1 não tem esse passo. O mapa do HAL é um *stub*
de 1,0000, portanto a correcção vem de um perfil nosso, medido por modelo de telefone.

**Falta medir as outras três objectivas.** Dois minutos por objectiva, e faz-se em interior:
difusor encostado à lente, apontar a um ecrã branco, `Objectiva ▸` para trocar, série de exposições.

### Como se mede, e as fontes que não servem

`python3 tools/shading.py chapa.dng --json perfil.json` valida e extrai o perfil.

**Valida-se por simetria radial, não por largura da distribuição.** Se a objectiva vinheta a 80%,
mesmo uma chapa perfeita tem distribuição larga — a primeira versão desta ferramenta rejeitava
medições boas por isso.

| Fonte | Serve? |
|---|---|
| **Difusor encostado à lente, apontado a céu de dia** | **sim** — o céu ilumina o difusor de todo o hemisfério, e só assim ele emite como lambertiano |
| Difusor com lâmpada atrás | não: a lâmpada é quase pontual, o difusor recebe luz de uma direcção |
| Ecrã LCD, mesmo a preencher o quadro | não: o brilho de um LCD depende do ângulo de visão. Medido, queda de 6× contra 2,5× que a física prevê |
| Superfície iluminada por lâmpada | não: assimetria de 18% a 97% |

### Falta um eixo de tinta ao balanço de brancos

Medido em doze chapas sob LED de interior: R e B a **0,90** do previsto pelo corpo negro, ou seja a
luz tem ~10% de verde a mais. O Kelvin sozinho move o ponto neutro **ao longo** do locus de Planck
e nunca neutraliza uma luz que esteja ao lado dele.

O eixo já está implementado (`Exposure.tint`, de −1 magenta a +1 verde, meio stop por unidade), mas
**ainda não é exposto na interface** — a F1 tem os parâmetros fixos no código. A tinta necessária
para aquela luz é ≈ **−0,30**.

### Pendências menores da F1, todas registadas

| O que | Onde |
|---|---|
| **Orientação fixa** em `ORIENTATION_NORMAL` | `export/DngWriter.kt`. Com o telefone em retrato o ficheiro sai deitado. Falta ler o sensor de rotação |
| **Foco fixo** em 0,5 dioptrias (2 m) | `model/LensProfile.defaultExposure()`. Desfoca o que está perto |
| **Exposição por omissão** de 8 ms a ISO 50 | idem. Curta para interiores |

Nenhuma é bug: a F1 tem os parâmetros no código por desenho. Passam a controlos na F4.

## 9. O próximo passo

**As seis fases estão feitas e validadas no telefone.** O que resta não são fases — é uso, desenho, e
duas medições.

**Desenho.** O **visor** está feito nas duas orientações e aprovado no telefone — ver §3 e, no progresso,
«A banda por baixo do visor». O que resta do desenho são os **outros dois ecrãs**: a biblioteca e a
análise, ainda com o aspecto antigo (lista de texto, sem miniatura, sem data, sem as etiquetas
DNG/RCP/TIFF). Há um protótipo em Figma (`Latente.fig`, e o brief em `latente-brief-ui.md`, versão curta
em `latente-prompt-figma.txt`); a correcção a pedir primeiro é que os mockups foram feitos em **16:9**
quando o sensor é **4:3**.

**Decidido e feito**: o ícone abre o visor, e o visor ganhou o botão `IR` — um menu com «NEGATIVOS» e
«EXPERIÊNCIAS». Antes disto o ícone abria o ecrã das experiências, e o visor não tinha saída nenhuma.

**Medição que falta.** A correcção de vinhetagem parece degradar a uniformidade de cor — indício forte,
não provado. Precisa de campo plano a sério: **difusor colado à lente contra um ecrã branco**, que é o
método que já resolveu a vinhetagem uma vez. Lâmpada não serve, tentou-se e as medições foram rejeitadas.

**Nunca medido, e agora com instrumento.** Uso prolongado: temperatura e bateria depois de vinte minutos
de visor. Numa medição de trinta segundos os relógios derivaram 15%; meia hora é outra coisa. O registo
existe — botão «Uso prolongado» nas experiências, ou:

```bash
$ADB shell am start -n io.github.bmcsilva.latente/.ui.ViewfinderActivity -e registar uso
```

Deixa-se o visor a correr, sai um `.txt` de colunas em `Downloads/Latente` — minuto, bateria, gasto,
temperatura, estado térmico e fps, de dez em dez segundos. **A temperatura é a da bateria**, que é o
único termómetro que a plataforma dá a uma aplicação sem privilégios; o cabeçalho do ficheiro di-lo. Se
o telefone se desligar por calor, o que se mediu até aí é publicado na sessão seguinte. Falta correr.

**Ainda em aberto no código.** O stream que parou duas vezes sem explicação, agora instrumentado: se
voltar, os contadores dizem se o reader deixou de avisar, se o `acquire` veio vazio, ou se lançou.
(A troca de objectiva no visor **já está feita e verificada** — id 2, 14 mm f/2,2, foco fixo, com o
Kelvin a atravessar a troca. Esta linha dizia o contrário e estava velha.)

### Como era antes desta secção

**A F4: a câmara.** As F0 a F3 estão concluídas e validadas no telefone. O que existe é um visor que
mostra o resultado final do nosso pipeline e um obturador que grava o frame certo; o que falta é
**ergonomia de câmara**, e é aí que o projeto passa de prova a produto.

Por ordem de valor para quem usa:

1. **Exposição a sério.** Hoje está fixa em 1/125 s ISO 50 no código. Modos M/A/S/P, roda de
   compensação, e um **fotómetro construído do histograma do RAW** — não do que o HAL diz, que é do ISP
   que não usamos. Havendo visor, medir passa a ser fácil: os dados já estão na GPU.
2. **Foco.** Está preso em 0,5 dioptrias, e vê-se: as fotografias de perto saem desfocadas. Precisa de
   controlo manual com *peaking*, que é outro passe sobre a textura que já existe.
3. **Kelvin e tinta na UI.** O `Exposure.tint` e o `WhiteBalance` já os suportam, e a F2 mostrou que sem
   o eixo de tinta há luzes LED que **não têm correcção possível** — resíduo de 7% a 11%. O visor mostra
   o efeito ao vivo, portanto ajustar deixa de ser adivinhar.
4. **Objectivas.** Trocar entre a id 0 e a id 2 no visor, com as equivalências em 35 mm.
5. **Ajudas:** zebras, histograma ao vivo, nível, ampliação.

E duas dívidas pequenas a limpar quando se mexer no que lhes toca: a orientação do DNG está fixa em
`ORIENTATION_NORMAL`, portanto o ficheiro sai deitado; e o `ImageDescription` leva UTF-8 num campo que a
especificação do TIFF define como 7 bits.

### O que a F3 deixou provado, e não se deve desmontar

O visor e o ficheiro partilham `GlUniforms`, `GlslSource` e `Gl.bindUniforms`. **Não é economia de
linhas: é a promessa do produto.** Se divergirem, o visor mente. A verificação está em
`PreviewProbe`, que compara os uniformes ao vivo com os do último DNG e dá veredicto — corre-se com
`-e auto visor`.

Resultado de referência no dispositivo de referência:

```
matriz de cor, diferença máxima : 0.0     · mosaico e níveis idênticos
balanço de brancos, desvio máx  : 0.066 %
305 avisos do reader · 301 entregues · 0 vazios · 0 falhas · 29,3 fps
```

### O consumo do `ImageReader`: um caminho só, e porquê

Três versões, e as duas primeiras partiram do mesmo erro — deixar o *listener* adquirir imagens. Isso
esgota o `maxImages`, e a partir daí **o reader nunca volta a notificar**. Além disso os dois modos não
coexistiam: com o visor a consumir, a fila do disparo ficava sempre vazia e o sintoma era «resultado sem
imagem», que manda procurar no sítio errado.

Regra actual, e não a desmontar: **o *listener* avisa e nunca adquire.** Quem consome adquire, fecha o
que não serve — é isso que devolve lugares ao reader — e escolhe o critério: o **mais recente** para o
visor, ou **por ordem até ao timestamp pedido** para o disparo.

Dois detalhes que custaram a acertar: a posse dos frames recusados é explícita (a primeira versão
guardava um frame que o consumidor fechava a seguir), e quando há permissões mas o `acquire` vem vazio
limpam-se as permissões, senão o laço gira sem nunca bloquear.

### Como verificar o visor sem olhar para o ecrã

Serve para qualquer alteração ao passe de apresentação, e é repetível:

```bash
ADB=$ANDROID_HOME/platform-tools/adb
$ADB shell am start -n io.github.bmcsilva.latente/.ui.ViewfinderActivity
$ADB exec-out screencap -p > visor.png
$ADB shell am start -n io.github.bmcsilva.latente/.ui.MainActivity -e auto disparar
# sem mexer o telefone; depois trazer o DNG e revelá-lo
python3 tools/develop.py --bin 8 --rolloff 1.0 \
  --shading dados-de-teste/perfis/vinhetagem-id0.json --out ref.png cena.dng
```

**Orientação:** correlacionar a região da imagem na captura contra as oito transformações da referência
(quatro rotações × espelho). A certa deu +0,993 e a seguinte +0,66 — não há ambiguidade.

**Cor: a captura de ecrã é Display P3, não sRGB.** O PNG do `screencap` traz o perfil embutido, com
`rXYZ` X = 0,5151 contra 0,4360 do sRGB. Ler os números como sRGB dá **7,5% de erro sistemático** que
não existe; convertendo de P3 dá 0,7%. Foi a terceira vez neste projeto que uma referência injusta
pareceu um bug nosso.

Matriz P3 linear → sRGB linear:

```
+1.22475  -0.22490   0.00000
-0.04206  +1.04208   0.00000
-0.01964  -0.07865  +1.09854
```

### Oportunidade anotada: o ecrã é P3 e nós usamos sRGB

Que a conversão sRGB → P3 aconteça na composição prova que a cor no ecrã está certa — mas também que
estamos a **atirar gamut fora** num ecrã que o tem. O `ColorScience.Output.DISPLAY_P3` já existe e está
testado, e o ICC correspondente também. Decisão para a F4/F5.

### Quatro lições de método da medição do visor, que custaram três corridas

Valem para qualquer medição futura neste projeto:

1. **Não inferir o culpado de diferenças entre corridas.** Comparar uma fase de controlo com uma de
   produção deu um custo **negativo** — fazer mais era mais rápido do que fazer nada — porque os
   intervalos melhoravam com a *ordem* das fases e não com a carga. Medir a **espera dentro da mesma
   fase** é comparação interna e imune à ordem. E repetir o controlo no fim torna a deriva visível: ela
   existe, e chega a 15%.
2. **Nunca engolir excepções em código de diagnóstico.** Um `catch (t: Throwable) { null }` escondeu
   exactamente a informação de que se precisava quando o stream parou.
3. **Confirmar no logcat antes de acreditar no próprio relatório.** O relatório dizia «a câmara
   morreu»; o logcat mostrava a câmara a produzir 313 frames até nós fecharmos a sessão. O erro era do
   fecho, não da causa.
4. **Um relatório tem de distinguir «não é viável» de «a medição estragou-se».** Um veredicto errado
   sobre o dispositivo manda o trabalho na direcção errada.

### O que a F2 deixou provado, e não se deve desmontar

O botão «Revelar · CPU vs GPU» é o critério de aceitação da F2 em forma executável, e **deve continuar
a passar** depois de qualquer mexida no shader. Corre-o com `-e auto revelar`, ou pelo botão.

Resultado de referência, no dispositivo de referência:

```
GPU 292 ms · CPU 3802 ms · 13,0×
diferença máxima 1 · amostras acima de 1: 0
```

Como ler um resultado que não seja esse:

| Diferença máxima | Significado |
|---|---|
| 0 ou 1 em 255 | concordam; é arredondamento de oito bits |
| pequena mas só nas bordas | reflexão do mosaico nas margens; verificar `2 * uTamanho.x - p.x - 2` |
| grande e espalhada | bug de canalização, não de cor: upload de textura, uniforme que não resolveu, ou ordem de linhas |
| a imagem ao contrário | as duas inversões do eixo Y deixaram de se cancelar |

Suspeitos por ordem: os uniformes que o relatório diz não terem resolvido; o formato da textura
(`GL_R16UI` com `GL_NEAREST` — filtro linear numa textura de inteiros é erro); e a ordem de colunas da
matriz de cor.

**Cuidado com a memória.** Uma imagem de 12,48 Mpx em vírgula flutuante são 150 MB, e o monte tem
256 MB por omissão — a primeira corrida morreu com `OutOfMemoryError` exactamente nos 49 939 200
bytes da leitura da GPU. Quatro decisões dependem disto e não se devem desfazer sem pensar: a GPU
revela **antes** do CPU; o `glReadPixels` devolve um `ByteBuffer` **directo**, que vive em memória
nativa; a comparação codifica amostra a amostra em vez de materializar a imagem em oito bits; e o TIFF
escreve-se da imagem linear, linha a linha. Mais `android:largeHeap="true"`.

Corolário para a F3: **o visor não pode passar por vírgula flutuante na CPU.** Não é questão de
velocidade, é que não há memória para isso.

## 10. Mapa de ficheiros

```
latente/
  ENTREGA.md                          este documento
  latente-especificacao-tecnica.md    a especificação, v16
  latente-progresso.md                o registo vivo
  latente-f1.apk                      APK da F1
  latente-f2.apk                      APK da F2, com o botão «Revelar · CPU vs GPU»

  probe/                              F0 — sonda de capacidades (Kotlin, sem dependências)
    app/src/main/kotlin/io/github/bmcsilva/latente/probe/
      CapabilityProbe.kt              o motor; nomes de constantes por reflexão
      ProbeModel.kt                   árvore de resultados + escritores JSON e texto
      ReportWriter.kt                 escrita em Downloads/Latente
      MainActivity.kt                 UI mínima em Views puros

  latente-app/                        a aplicação real
    app/src/main/kotlin/io/github/bmcsilva/latente/
      model/Exposure.kt               o que o utilizador decide
      model/LensProfile.kt            uma objectiva é uma câmara física
      model/Body.kt                   enumera lógicas e físicas
      model/WhiteBalance.kt           Kelvin + tinta → ganhos
      camera/HalClamp.kt              pedido vs aplicado, funções puras testáveis
      camera/Planner.kt               o nosso corte, antes do HAL
      camera/CleanRequest.kt          o pedido limpo — o coração do produto
      camera/CameraSession.kt         sessão RAW, API bloqueante
      camera/RawReader.kt             estatísticas do mosaico
      export/DngWriter.kt             DngCreator → MediaStore
      export/Sidecar.kt               o que o DNG não guarda
      export/MediaStoreOut.kt         escrita sem permissões
      export/Report.kt                escritores partilhados com a sonda
      export/DngReader.kt             leitor de TIFF/DNG, sem bibliotecas
      export/Tiff16Writer.kt          TIFF de 16 bits com ICC embutido
      export/IccProfile.kt            perfis ICC v2 gerados à mão
      render/ColorScience.kt          a ciência da cor, portada e testada
      render/Demosaic.kt              Malvar-He-Cutler e binning 2×2
      render/LensShading.kt           correcção de vinhetagem + perfis medidos
      render/RawPipeline.kt           o pipeline do lado do CPU, o oráculo
      render/GlUniforms.kt            o que vai para o shader, testável na JVM
      render/GlslSource.kt            os shaders; tradução directa do Kotlin
      render/GlDeveloper.kt           EGL, texturas, FBO — canalização, sem aritmética
      render/Gl.kt                    EglContext + compilação + uniformes, partilhado
      render/GlPreview.kt             o motor do visor: estado vivo entre frames
      render/Present.kt               rotação e enquadramento, aritmética testável
      ui/ViewfinderActivity.kt        o visor: fio de render próprio + SurfaceView
      diag/Experiments.kt             as nove experiências
      diag/GpuCheck.kt                o telefone a comparar CPU com GPU
      diag/PreviewProbe.kt            a medição de viabilidade do visor, em 5 fases
      ui/MainActivity.kt              andaime; substituído em F3/F4

  tools/                              verificação, Python
    dngcheck.py  verify.py  develop.py  compare.py  flatfield.py  flatpair.py

  dados-de-teste/                     um DNG de cena e uma chapa plana, com sidecars
```

## 11. Dados de teste

Em `dados-de-teste/`:

| Ficheiro | Para que serve |
|---|---|
| `chapa-plana.dng` | papel vegetal colado à lente. É a questão da vinhetagem em aberto (§8) |
| `canto-de-parede.dng` | superfície com gradiente suave, bom para exercitar o pipeline |
| `latente-f1-experiencias-*.txt` e `.json` | o relatório das nove experiências: é a fonte dos números da §7 |

Cada DNG leva o `.json` do sidecar, que documenta pedido vs aplicado e tudo o que o HAL confirmou.

**Nota honesta:** a fotografia com que se validou a cor — uma cena com objectos cor-de-rosa, um
cartão branco e um post-it verde — perdeu-se numa reorganização de pastas. Os números dessa validação
estão registados na §7 e no `latente-progresso.md`, mas **não são reproduzíveis com os ficheiros
deste pacote**.

Isso não bloqueia o próximo passo: para comparar o shader com o `develop.py` **pixel a pixel** serve
qualquer DNG, e ambos os que vão aqui servem. Se se quiser repetir a comparação de cromaticidade
contra o darktable, fotografe-se uma cena nova com cores variadas e de preferência um cinzento
neutro.
