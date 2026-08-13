# LATENTE — Especificação técnica

**Aplicação Android de captura fotográfica manual, sem processamento computacional.**

Documento de arranque: contém tudo o que é necessário para desenvolver o projeto a partir do zero
noutra máquina, sem depender do histórico da conversa que o originou.

Versão do documento: 16 · 2026-08-04 — **a F3 está cumprida**: visor verificado no telefone (uniformes
idênticos aos do ficheiro, cor a 0,7% / 1,9%, orientação a +0,993 de correlação) e disparo a partir dele
com o frame casado pelo timestamp. Nova regra em §9: o disparo a partir do visor não pára o pedido
repetido. O `matchedByTimestamp` passa a ficar no sidecar, não só no ecrã.
Antes, na v15: **a viabilidade do visor da F3 está medida**: 19–28 fps com o
pipeline completo, limitado pela câmara e não por nós, portanto a arquitectura mantém-se e não há
desempenho a resolver antes da UI. Ver §9. Nova regra: no visor consome-se por `acquireLatestImage`,
nunca por fila.
Antes, na v14: **a F2 está cumprida e medida no telefone**. §9: CPU e GPU
concordam a 1 em 255 sem uma única amostra acima disso; cor a 0,7% / 2,3% com os quase-neutros a 0,0%;
292 ms na GPU contra 3802 ms no CPU. E a ressalva que a execução obrigou a escrever: **o darktable
deixa de ser oráculo quando se corrige a vinhetagem**, porque não corrige nenhuma.
Antes, na v13: o porte para GLSL valida-se comparando o caminho do CPU com o da GPU dentro do próprio
telefone, em vez de contra um revelador externo.
Antes, na v12: as 8 experiências da F1 respondidas e o DNG verificado. §8.2: o `GainMap` tem de ser
inspeccionado (no dispositivo de referência é identidade, logo não há dupla correcção de vinhetagem).
O balanço de brancos passa a chegar ao DNG por `COLOR_CORRECTION_GAINS`. Ferramenta
`tools/dngcheck.py`, sem dependências.
Antes, na v10: **7 das 8 experiências da F1 respondidas** e os resultados
levados a §2.4: `SHADING_MODE` é decorativa, o pedestal está mesmo a zero, GBRG confirmado, ZSL
honrado. §5.2 com as três regras de assentamento e captura.
Antes, na v9: limite de exposição afinado: **1750 ms medidos contra 100 ms
declarados, 17,5×** (§2.4). Nova regra em §5.2: não parar o pedido repetido para disparar — a
captura intercala-se e a imagem identifica-se pelo timestamp.
Antes, na v8: `SENSOR_FRAME_DURATION` nunca abaixo de
`getOutputMinFrameDuration(RAW_SENSOR, tamanho)` (33,3 ms no dispositivo de referência). Era a causa
real de cinco experiências da F1 falharem. §2.4 com o limite de exposição afinado: está entre 1 s e
2 s, e o dispositivo não morre com pedidos acima do declarado.
Antes, na v7: resultados medidos da F1, e **correcção de uma conclusão
errada da v6**: o tecto de exposição não é 1/10 s. `SENSOR_INFO_EXPOSURE_TIME_RANGE` subdeclara em
10× e o HAL honra pelo menos 1 s (§2.4, §11) — a v6 dizia o contrário por causa de um limite de
espera curto na sonda. Também medido: RAW + preview coexistem (§11), e
`COLOR_CORRECTION_GAINS` determina o `AsShotNeutral` (§8.2), o que reverte a correcção da v5.
Antes, na v5: correcções vindas da implementação da F1: §5.2 (tonemap
linear por `CONTRAST_CURVE`; definir só chaves declaradas; assentar o sensor antes de disparar),
§5.4 (o *line time* não é exposto), §8.2 (`DngCreator` não permite definir `AsShotNeutral`).
Antes: §2.4 (dispositivo de referência medido) e §11 (estado real dos riscos) na v4;
§4.4 (tecnologias) e §6.5 (threading) na v2; correcções em §3.2 e §10.4 na v3.
Estado de execução em `latente-progresso.md`.

---

## 1. Objectivo

Uma câmara para Android que se comporta como uma câmara dedicada de objectivas intercambiáveis
(referência de ergonomia e de controlos: Sony α7 III), em que:

1. **O fotógrafo decide a exposição.** Tempo, ISO, abertura (quando o hardware a tem), foco e
   balanço de brancos são manuais, com modos M/A/S/P a resolver *só a exposição* — nunca os píxeis.
2. **O sistema operativo e o ISP do fabricante não tocam na imagem.** Sem *beautify*, sem nitidez
   automática, sem redução de ruído, sem *tone mapping* local, sem cores de memória, sem fusão
   multi-frame, sem correcção geométrica, sem o JPEG do HAL.
3. **O que se vê no visor é o ficheiro.** O visor não é uma pré-visualização decorativa: é o
   resultado do mesmo pipeline que escreve o ficheiro final (o equivalente ao *Setting Effect ON*
   do visor electrónico da Sony).
4. **O ficheiro é o melhor possível.** Um negativo digital intocado (DNG de um único frame) mais um
   positivo revelado pelo pipeline da própria aplicação, em 16 bits por canal.

### 1.1 Princípios não negociáveis

| # | Princípio | Consequência técnica |
|---|---|---|
| P1 | Um disparo = um frame do sensor | `CONTROL_ENABLE_ZSL = false`, sem *burst*, sem fusão |
| P2 | Nada é acrescentado que o utilizador não peça | Todos os blocos de pós-processamento do HAL em `OFF` |
| P3 | Visor e ficheiro saem do mesmo código | Um único pipeline GPU, alimentado pelo stream RAW |
| P4 | O que o HAL alterou é mostrado, não escondido | Ler `CaptureResult` e apresentar pedido *vs* aplicado |
| P5 | O negativo é imutável | DNG escrito uma vez; toda a revelação é derivada |
| P6 | Nunca se usa o JPEG/HEIC produzido pelo HAL | A codificação é feita a partir do render próprio |

### 1.2 O que este projeto **não** é

- Não é uma app de simulação de película. A revelação é neutra e controlada pelo utilizador.
- Não é um substituto da câmara do sistema em conveniência. Uma foto de frame único, sem redução
  de ruído nem fusão, terá **mais ruído nas sombras e menos alcance dinâmico** do que a foto
  computacional. Isso é o preço da fidelidade e deve ser dito na própria interface.
- Não usa *Camera Extensions* (`HDR`, `NIGHT`, `BOKEH`, `FACE_RETOUCH`). São exactamente o que se
  quer evitar.

---

## 2. Realidade da plataforma: o tecto

Antes de escrever código é preciso interiorizar onde é que o Android deixa chegar.

### 2.1 O que se consegue

`ImageFormat.RAW_SENSOR` via **Camera2** entrega o mosaico de Bayer em 16 bits, antes de
*demosaicing*. É genuinamente dados de sensor.

### 2.2 O que não se consegue

- **O tecto é o HAL do fabricante.** *Black level*, *binning* quad-Bayer e o ganho analógico já vêm
  decididos pelo vendor. A aplicação não pode prometer "só o sensor"; pode prometer
  *"nada que eu acrescente, e digo-te exactamente o que o telefone acrescentou"*.
- **Quad-Bayer / remosaico.** Num sensor de 50 MP o `RAW_SENSOR` é tipicamente já *binned* a
  12,5 MP. Resolução total só onde existirem as capabilities `ULTRA_HIGH_RESOLUTION_SENSOR` /
  `REMOSAIC_REPROCESSING`, e mesmo aí com restrições de stream.
- **Módulos secundários.** Grande-angular e teleobjectiva estão frequentemente em nível `LIMITED`
  e sem `RAW`. Em vários dispositivos só a câmara principal serve.
- **Shading possivelmente já aplicado.** Alguns HAL aplicam correcção de vinhetagem ao RAW mesmo
  com `SHADING_MODE = OFF`. Detecta-se empiricamente (ver §10.4).
- **Preview a partir de RAW é mais lento.** `getOutputMinFrameDuration(RAW_SENSOR, size)` pode
  indicar 10–30 fps à resolução máxima. Ver §6.4 para a mitigação.

### 2.3 Armadilha específica: RAW que não é RAW

Aplicações de fabricante do género *Expert RAW* devolvem um DNG **já empilhado e processado** —
um DNG *calculado*, com nome de RAW e conteúdo computacional. Não serve para este objectivo.
O que se quer é `RAW_SENSOR` de um único frame via Camera2, com ZSL desligado.

### 2.4 Dispositivo de referência: medido, não suposto

Valores lidos pela sonda em **samsung SM-S942B** (`m1s`, `s5e9965`, Android 16, API 36) em
2026-08-03. Substituem qualquer suposição anterior deste documento. O relatório completo está no
projeto da sonda.

| Constrangimento | Valor medido | Consequência de desenho |
|---|---|---|
| **Bits úteis por pixel** | `SENSOR_INFO_WHITE_LEVEL = 1023` → **10 bits** | O contentor `RAW_SENSOR` tem 16 bits mas o sensor entrega 10. Tecto teórico de ~10 stops. O revelador não deve assumir precisão que não existe |
| **Nível de preto** | padrão `0, 0, 0, 0` | O HAL já subtraiu o pedestal. **Não voltar a subtrair** na revelação |
| **Lens shading** | `SENSOR_INFO_LENS_SHADING_APPLIED = true` em todas as câmaras | Vinhetagem já corrigida. `STATISTICS_LENS_SHADING_CORRECTION_MAP` ausente, logo não se sabe o que foi aplicado nem se desfaz. **Medido na F1: `SHADING_MODE` é decorativa** — OFF e FAST dão o mesmo RAW (diferença de 0,002 a 0,012 na razão cantos/centro) |
| **Nível de preto real** | média 0,405, máx. 6, com a lente tapada | **Medido na F1:** ruído de leitura em cima do zero. Confirma que o pedestal já foi subtraído |
| **Mosaico confirmado** | GBRG, com as duas posições verdes iguais a 4 algarismos | **Medido na F1.** O *demosaicing* pode confiar no declarado |
| **ZSL** | frame chega 188–213 ms **depois** do pedido | **Medido na F1:** `CONTROL_ENABLE_ZSL = false` é honrado; é captura verdadeira |
| **Tecto de exposição** | declarado 100 ms; **medido 1750 ms** | `SENSOR_INFO_EXPOSURE_TIME_RANGE` **subdeclara em 17,5×**. Ver abaixo: a exposição longa existe, mas o limite tem de ser sondado, não lido |
| **Duração mínima de frame** | 33,33 ms (RAW 12,5 MP a 30 fps) | Piso obrigatório de `SENSOR_FRAME_DURATION`; pedir menos faz o HAL descartar a captura |
| **ISO** | 25–3200, analógico até 640 | Só 3,7 stops de ganho real; acima de 640 são 2,3 stops de volume digital |
| **RAW de resolução total** | `getHighResolutionOutputSizes()` vazio | Sem caminho para além dos 12,5 MP *binned* |
| **Saídas RAW simultâneas** | 1 | Sem bracketing por streams paralelos |
| **Mosaico de cor** | **GBRG** na principal, **RGGB** nas restantes | O *demosaicing* tem de ler o CFA por câmara; nunca assumir RGGB |
| **Ciência da cor** | completa nas cinco câmaras (dois iluminantes, *forward matrices*) | DNG com cor correcta sem perfil próprio |
| **`SENSOR_NOISE_PROFILE`** | presente | Escrever no DNG |
| **Chaves ausentes na principal** | `DISTORTION_CORRECTION_MODE`, `TONEMAP_GAMMA`, `SENSOR_DYNAMIC_BLACK_LEVEL`, `SENSOR_DYNAMIC_WHITE_LEVEL`, `SENSOR_ROLLING_SHUTTER_SKEW` | Usar os níveis estáticos; `TONEMAP_CURVE` existe e basta; não há *skew* para registar |

**Objectivas reais.** O corpo tem duas traseiras utilizáveis e duas vistas frontais:

| Câmara | Equivalente | Abertura | Foco | RAW | Notas |
|---|---|---|---|---|---|
| id 0 (= física 5) | 23 mm | f/1,8 (f/7,6 equiv.) | 10 cm–∞, calibrado, OIS | 4080×3060 · 12,5 MP | sensor 8,16×6,12 mm · recorte ×4,24 · 4,1 stops abaixo de full frame · ISO analógico ≤640 |
| id 2 | 14 mm | f/2,2 (f/13,6 equiv.) | **fixo**, sem OIS | 4000×3000 · 12,0 MP | `LIMITED`, sem `MANUAL_POST_PROCESSING` — **não bloqueante** (ver abaixo) |
| id 6 | 66 mm | f/2,4 | 50 cm–∞ | 3648×2736 | **inutilizável**: sem `MANUAL_SENSOR`, sem chave `SENSOR_EXPOSURE_TIME`, e não abre |
| id 1 / id 3 | 23 mm / 30 mm | f/2,2 | 20 cm–∞ | 12,0 / 7,0 MP | frontais |

**Critério de aptidão, corrigido.** É bloqueante apenas: saída `RAW_SENSOR`, capability `RAW`,
`MANUAL_SENSOR`, e as chaves `SENSOR_EXPOSURE_TIME` e `SENSOR_SENSITIVITY` declaradas.
`MANUAL_POST_PROCESSING` e o nível `FULL` **não** são bloqueantes neste projeto: dizem respeito ao
tonemap e à correcção de cor do ISP, que não se usam porque o revelador parte do RAW. Exigi-los
excluiria a ultra-grande-angular, que é perfeitamente utilizável.

**Câmaras físicas.** Os ids 5 e 6 não abrem directamente — `CAMERA_DISCONNECTED`, "No camera device
with ID available". Só existem dentro da lógica id 0. Como o id 0 tem exactamente as mesmas
características do id 5, a principal está acessível pelo id 0 e não se perde nada; a tele do id 6
é que fica fora de alcance.

**O tecto de exposição declarado não é o real.** Medido na F1, uma sessão nova por valor:

| Pedido | Aplicado |
|---|---|
| 50 ms | 50,0 ms |
| 100 ms | 100,0 ms |
| **250 ms** | **250,0 ms** |
| **500 ms** | **500,0 ms** |
| **1000 ms** | **1000,0 ms** |
| **1250 ms** | **1250,0 ms** |
| **1750 ms** | **1750,0 ms** |
| 2000 ms | sem resposta em 14 s |
| 2500 ms | sem resposta em 15,5 s |
| 4000 ms | sem resposta em 20 s |

**O limite real é 1,75 s — 17,5× o declarado.** `SENSOR_INFO_EXPOSURE_TIME_RANGE` diz 100 ms e o
HAL honra até 1750 ms, exactamente como pedido. O dispositivo **não morre** com pedidos acima:
simplesmente não entrega frame.

A duração mínima de frame do stream RAW é **33,3 ms** (12,5 MP a 30 fps), e é um piso obrigatório
para `SENSOR_FRAME_DURATION` (ver §5.2).

`SENSOR_INFO_EXPOSURE_TIME_RANGE` declara 100 ms e o HAL honra **pelo menos 1 s, exactamente como
pedido**. O `MAX_FRAME_DURATION` de 142,9 ms também não é vinculativo. O limite verdadeiro ainda não
é conhecido: a sonda dos 2 s falhou por o tempo de espera ser mais curto que a própria exposição, e a
escada foi estendida até 30 s com espera proporcional.

Consequências de desenho:

1. **A exposição longa existe neste telefone.** Fotografia nocturna volta ao âmbito do projeto.
2. **O limite descobre-se por sondagem, não por leitura.** A aplicação deve sondar uma vez, guardar
   o resultado por modelo, e oferecer até ao valor provado — em vez de acreditar num intervalo que
   subdeclara em 10×.
3. **O corte do lado da aplicação passa a usar o limite sondado**, não o declarado. Continua a ser
   necessário: acima do limite real o frame não chega.
4. Qualquer código que sonde limites tem de o fazer numa sessão isolada e descartável, e com espera
   proporcional ao tempo pedido.

---

## 3. Passo 0 — Sonda de capacidades

**Este é o primeiro entregável e o estudo de viabilidade.** Uma aplicação mínima que percorre todas
as câmaras — incluindo as físicas dentro das lógicas — e escreve um relatório legível no PC.
Sem UI de câmara, sem preview.

### 3.1 Enumeração

```kotlin
val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
val ids = mutableListOf<String>()
for (id in mgr.cameraIdList) {
    ids += id
    val ch = mgr.getCameraCharacteristics(id)
    // câmaras físicas escondidas dentro de uma lógica
    if (Build.VERSION.SDK_INT >= 28) ids += ch.physicalCameraIds
}
```

`cameraIdList` **não** inclui as câmaras físicas de um dispositivo lógico. É preciso
`getPhysicalCameraIds()` e depois tentar `getCameraCharacteristics()` de cada uma; em alguns
fabricantes a abertura directa de uma física é recusada mesmo que as características se leiam.
Registar ambos os resultados.

### 3.2 Chaves a despejar, por câmara

**Identidade e nível**

- `INFO_SUPPORTED_HARDWARE_LEVEL` — exige-se `FULL` ou `LEVEL_3`
- `REQUEST_AVAILABLE_CAPABILITIES` — exige-se `RAW`, `MANUAL_SENSOR`, `MANUAL_POST_PROCESSING`,
  `READ_SENSOR_SETTINGS`
- `LENS_FACING`, `LOGICAL_MULTI_CAMERA_PHYSICAL_IDS`
- `getAvailableCaptureRequestKeys()`, `getAvailableCaptureResultKeys()`,
  `getAvailableSessionKeys()` — **crítico**: dizem quais as chaves que o HAL declara honrar.
  São **métodos** de `CameraCharacteristics`, não chaves — não existe uma chave
  `REQUEST_AVAILABLE_REQUEST_KEYS` pública

**Sensor e geometria**

- `SCALER_STREAM_CONFIGURATION_MAP` → `getOutputSizes(ImageFormat.RAW_SENSOR)` e
  `getOutputMinFrameDuration(...)` para cada tamanho
- `SENSOR_INFO_PIXEL_ARRAY_SIZE`, `SENSOR_INFO_ACTIVE_ARRAY_SIZE`,
  `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`
- `SENSOR_INFO_PHYSICAL_SIZE` (mm) — necessário para o factor de recorte
- `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT` (RGGB / GRBG / GBRG / BGGR / MONO / NIR)
- `SENSOR_INFO_WHITE_LEVEL`, `SENSOR_BLACK_LEVEL_PATTERN`
- `SENSOR_INFO_TIMESTAMP_SOURCE`, `REQUEST_MAX_NUM_OUTPUT_RAW`
- `SENSOR_INFO_LENS_SHADING_APPLIED` — a plataforma diz directamente se o HAL já corrigiu
  vinhetagem antes de entregar o RAW. Responde a §10.4 sem experimentação (mas confirmar
  empiricamente, porque um HAL pode declarar mal)

**Exposição**

- `SENSOR_INFO_EXPOSURE_TIME_RANGE` (ns) — o limite superior é o que decide se há longas exposições
- `SENSOR_INFO_MAX_FRAME_DURATION`
- `SENSOR_INFO_SENSITIVITY_RANGE`
- `SENSOR_MAX_ANALOG_SENSITIVITY` — acima disto o ISO é ganho digital

**Óptica**

- `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`
- `LENS_INFO_AVAILABLE_APERTURES` — se tiver mais de um valor, o controlo de abertura existe
- `LENS_INFO_MINIMUM_FOCUS_DISTANCE`, `LENS_INFO_HYPERFOCAL_DISTANCE`
- `LENS_INFO_FOCUS_DISTANCE_CALIBRATION` (`UNCALIBRATED` / `APPROXIMATE` / `CALIBRATED`) — decide
  se a escala de distâncias pode ser mostrada em metros
- `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION`

**Blocos de processamento (o que se pode desligar)**

- `NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES` — confirmar que `OFF` está na lista
- `EDGE_AVAILABLE_EDGE_MODES`
- `HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES`
- `SHADING_AVAILABLE_MODES`
- `COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES`
- `TONEMAP_AVAILABLE_TONE_MAP_MODES`, `TONEMAP_MAX_CURVE_POINTS`
- `DISTORTION_CORRECTION_AVAILABLE_MODES` (API 28)
- `STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES`
- `CONTROL_AVAILABLE_MODES`, `CONTROL_AE_AVAILABLE_MODES`, `CONTROL_AF_AVAILABLE_MODES`,
  `CONTROL_AWB_AVAILABLE_MODES`

**Ciência da cor (indispensável para o DNG e para o revelador)**

- `SENSOR_REFERENCE_ILLUMINANT1`, `SENSOR_REFERENCE_ILLUMINANT2`
- `SENSOR_COLOR_TRANSFORM1`, `SENSOR_COLOR_TRANSFORM2`
- `SENSOR_CALIBRATION_TRANSFORM1`, `SENSOR_CALIBRATION_TRANSFORM2`
- `SENSOR_FORWARD_MATRIX1`, `SENSOR_FORWARD_MATRIX2`

Se estas matrizes vierem ausentes ou obviamente erradas, a cor do DNG será má e é preciso um
perfil próprio por dispositivo.

### 3.3 Formato do relatório

Escrever **JSON** (para diff e para processamento) e um **TXT** legível, ambos em
`MediaStore.Downloads`. O JSON é o artefacto que decide o desenho: é dele que sai o perfil de corpo
e de objectivas da aplicação (§7.1).

### 3.4 Critério de aceitação da fase

O relatório abre no PC, e para cada câmara sabe-se: se tem RAW, a que resolução, com que limites de
tempo e ISO, onde acaba o ganho analógico, se a abertura é variável, e quais dos blocos de
processamento admitem `OFF`.

---

## 4. Arquitectura

```
┌─────────────────────────────────────────────────────────────┐
│ UI (Compose)                                                │
│  Visor + controlos de câmara · Ficheiros · Análise          │
└───────────────┬─────────────────────────────┬───────────────┘
                │                             │
        ┌───────▼────────┐           ┌────────▼─────────┐
        │ CameraSession  │           │ Meter (próprio)  │
        │ Camera2        │           │ estatísticas RAW │
        └───────┬────────┘           └──────────────────┘
                │ ImageReader(RAW_SENSOR) + CaptureResult
        ┌───────▼──────────────────────────────────────┐
        │ RawPipeline (GPU)   ← ÚNICA FONTE DE VERDADE │
        │ black/white · WB · demosaic · matriz · expo  │
        │ · rolloff · transformada de saída            │
        └───────┬───────────────────────┬──────────────┘
                │ para o ecrã           │ para ficheiro
        ┌───────▼───────┐       ┌───────▼────────────────────┐
        │ Viewfinder    │       │ Export                     │
        │ (SurfaceView) │       │ DNG · TIFF16 · AVIF · JSON │
        └───────────────┘       └────────────────────────────┘
```

A regra de ouro é **P3**: o visor e a exportação usam o mesmo shader; só difere a resolução da
entrada. Qualquer atalho que use o stream YUV do ISP para o visor quebra a promessa do produto,
porque o YUV passou pelo ISP e não corresponde ao ficheiro.

### 4.1 Estrutura de pastas

```
app/src/main/kotlin/<pacote>/
  probe/     CapabilityProbe.kt  ProbeReport.kt
  camera/    CameraSession.kt  CleanRequest.kt  HalClamp.kt  RawFrame.kt
  model/     Body.kt  LensProfile.kt  Exposure.kt  ShootMode.kt  MeterMode.kt  Equivalence.kt
  meter/     Meter.kt  Histogram.kt
  render/    RawPipeline.kt  ColorScience.kt  Tone.kt
             shaders/  raw_to_rgb.frag  demosaic_mhc.frag  output.frag  peaking.frag
  export/    DngWriter.kt  Tiff16Writer.kt  AvifWriter.kt  Sidecar.kt  MediaStoreOut.kt
  ui/        Viewfinder.kt  Controls.kt  FilesScreen.kt  AnalysisScreen.kt
```

### 4.2 Dependências

Deliberadamente quase nenhuma:

- **Sem CameraX** para a captura. A CameraX existe para esconder o controlo manual; tudo o que se
  ganha em conveniência paga-se em acesso. Camera2 puro.
- Sem bibliotecas de câmara de terceiros.
- Sem bibliotecas de processamento de imagem — o pipeline é próprio (é o produto).
- UI: Jetpack Compose, com o visor num `SurfaceView`/`GLSurfaceView` embrulhado em `AndroidView`.
- Opcional: `androidx.heifwriter` se se quiser HEIC de 8 bits para partilha.

### 4.3 Configuração do projeto

| Item | Valor | Razão |
|---|---|---|
| Linguagem | Kotlin | — |
| `minSdk` | 31 (Android 12) | `DISTORTION_CORRECTION_MODE` (28), `DYNAMIC_BLACK_LEVEL` (24), armazenamento com âmbito (29); 31 evita ramos legados |
| `targetSdk` | o mais recente | — |
| GPU | OpenGL ES 3.1 (ou Vulkan) | texturas inteiras de 16 bits para o RAW |
| Permissões | `CAMERA` | escrita via `MediaStore`, sem permissão de armazenamento |
| ABI | arm64-v8a | — |

### 4.4 Tecnologias e alternativas rejeitadas

A decisão está ancorada num facto concreto: **o `DngCreator` só existe em Java/Kotlin.** Não há
equivalente no NDK. Escrever DNG à mão significa implementar TIFF/IFD, matrizes de cor,
`NoiseProfile` e opcodes de shading — semanas para reinventar algo que a plataforma entrega correcto.
Isto fixa o caminho de captura e de escrita no JVM.

O segundo facto: **nada neste projeto é limitado por CPU**, desde que os píxeis vivam na GPU. O
argumento clássico para C++ — *throughput* por pixel — desaparece quando o *demosaicing* e a cor são
shaders. O que sobra em CPU é I/O e metadados.

| Camada | Tecnologia | Razão |
|---|---|---|
| Camera2, sessão, metadados | Kotlin | API pensada para JVM; o NDK (`ACameraManager`) funciona mas é menos documentado e não traz vantagem |
| Escrita de DNG | Kotlin | `DngCreator`, sem alternativa nativa |
| Pipeline de píxeis | GLSL ES 3.1 | o trabalho é na GPU; a linguagem do *host* é irrelevante |
| TIFF16, sidecar, exportação | Kotlin | I/O, não é crítico em desempenho |
| UI de instrumento | Compose | encaixa em painéis, leituras e estado |
| Validação dos ficheiros | Python, no PC | ver §10; `rawpy` + `numpy` fazem-no em muito menos código |

**OpenGL ES 3.1 em vez de Vulkan.** ES 3.1 é universal, suporta *compute shaders* e permite
`GL_R16UI` para carregar o RAW sem perder bits — com muito menos código. Manter as etapas do
pipeline separadas e sem estado partilhado, para que a porta para Vulkan seja possível se algum dia
for preciso controlo fino de memória de 16 bits.

**Alternativas rejeitadas**

| Alternativa | Porque não |
|---|---|
| Flutter, React Native | Tudo o que importa acabaria em Kotlin atrás de *platform channels*; só acrescenta uma camada |
| RenderScript | Depreciado e removido |
| `RuntimeShader` / AGSL (API 33) | Só fragmento, sobre `Bitmap`/`Canvas`; insuficiente para um revelador RAW |
| Rust (JNI + `wgpu`) | `wgpu` é bom, mas Camera2 e `DngCreator` continuariam a ser chamados por JNI — atrito sem retorno |
| NDK completo (`AImageReader` + Vulkan) | Máximo controlo e cópia zero, mas sem `DngCreator` e com muito mais trabalho; considerar só se o visor se revelar insuficiente |
| Java em vez de Kotlin | Mesmo runtime, sem vantagem |

**A ferramenta de validação é um projeto separado.** Os testes da §10 — linearidade, espectro do
ruído, halos de bordo — correm sobre os DNG num computador, não no telefone. Python é a escolha
óbvia e não contamina a aplicação.

---

## 5. Captura: Camera2

### 5.1 Sessão

- `ImageReader` em `ImageFormat.RAW_SENSOR`, tamanho escolhido de
  `getOutputSizes(RAW_SENSOR)` (normalmente o maior), `maxImages = 2..3`.
- Uma segunda saída para o visor **não é uma saída do ISP**: o visor desenha o resultado do pipeline
  próprio numa `Surface` de GL. Ou seja, a sessão de captura tem **apenas** a saída RAW; o RAW
  repetido alimenta simultaneamente o visor e, no disparo, o ficheiro.
- `CameraDevice.createCaptureSession` com `SessionConfiguration` (API 28+).
- Pedido repetido (`setRepeatingRequest`) para o visor; `capture()` para o disparo, com os
  **mesmos** parâmetros.

### 5.2 O pedido limpo

Este bloco é o coração do produto. Aplica-se a **todos** os pedidos — repetido *e* de captura —
senão o visor mente.

```kotlin
fun CaptureRequest.Builder.makeClean(s: Exposure, ch: CameraCharacteristics) {
    // 3A completamente fora do caminho
    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
    set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
    set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF)
    set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_DISABLED)
    set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)

    // um disparo = um frame do sensor
    set(CaptureRequest.CONTROL_ENABLE_ZSL, false)

    // ganho digital pós-RAW: neutro
    set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 100)

    // estabilização: a electrónica deforma a imagem, a óptica não
    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, s.oisMode) // escolha do utilizador

    // blocos de pós-processamento
    set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
    set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
    set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF)
    set(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_OFF)
    set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
        CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF)
    if (Build.VERSION.SDK_INT >= 28) {
        set(CaptureRequest.DISTORTION_CORRECTION_MODE,
            CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)
    }
    set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_GAMMA_VALUE)
    set(CaptureRequest.TONEMAP_GAMMA, 1.0f)  // linear

    // sem recorte digital
    set(CaptureRequest.SCALER_CROP_REGION,
        ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE))
    if (Build.VERSION.SDK_INT >= 30) set(CaptureRequest.CONTROL_ZOOM_RATIO, 1.0f)

    // registar o mapa de shading mesmo sem o aplicar — é preciso para o DNG
    set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
        CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)

    // exposição manual
    set(CaptureRequest.SENSOR_EXPOSURE_TIME, s.exposureNs)
    set(CaptureRequest.SENSOR_SENSITIVITY, s.iso)
    set(CaptureRequest.SENSOR_FRAME_DURATION, s.frameDurationNs)
    set(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopters)
    s.aperture?.let { set(CaptureRequest.LENS_APERTURE, it) }
}
```

Notas importantes:

- **`CONTROL_MODE_OFF` vs `AUTO`.** `OFF` desliga o 3A inteiro e é o correcto. Em HAL problemáticos,
  `CONTROL_MODE_OFF` pode desactivar rotinas que se querem; a alternativa é `CONTROL_MODE_AUTO` com
  `AE/AWB/AF = OFF`. Verificar por dispositivo e registar a decisão.
- **OIS é óptica, não computacional.** Mover elementos de lente não adultera píxeis. Deve ser uma
  escolha do utilizador (por omissão ligada para uso à mão), ao contrário da estabilização de vídeo,
  que deforma a imagem e fica sempre desligada.
- **`TONEMAP` e `COLOR_CORRECTION` não afectam o RAW**, só as saídas processadas. Definem-se ainda
  assim, por higiene e porque em alguns HAL influenciam metadados.
- **Correcção vinda da F1: o bloco de tonemap acima não funciona no dispositivo de referência.**
  O SM-S942B não oferece `TONEMAP_MODE_GAMMA_VALUE` (só `CONTRAST_CURVE`, `FAST` e `HIGH_QUALITY`)
  e a chave `TONEMAP_GAMMA` está ausente. A alternativa é `TONEMAP_MODE_CONTRAST_CURVE` com uma
  `TonemapCurve` identidade `[0,0, 1,1]`, que dá o mesmo. A implementação tenta `GAMMA_VALUE` e cai
  para `CONTRAST_CURVE`.
- **Definir só o que o HAL declara.** Em vez de definir às cegas, o código consulta
  `getAvailableCaptureRequestKeys()` e as listas de modos disponíveis, e **registra o que ficou de
  fora** no sidecar. Uma chave ausente é uma promessa que aquele telefone não cumpre, e o
  utilizador tem direito a sabê-lo.
- **`TEMPLATE_MANUAL`** para o pedido repetido *e* para o disparo: é o único template que não
  injecta automatismos.
- **`SENSOR_FRAME_DURATION` nunca abaixo do mínimo do stream.** Custou duas corridas da F1.
  Punha-se a duração igual à exposição; com exposições curtas ficava abaixo de
  `getOutputMinFrameDuration(RAW_SENSOR, tamanho)` — 33,3 ms para os 12,5 MP a 30 fps — e o HAL
  descartava o pedido, umas vezes com `onCaptureFailed` razão 0, outras devolvendo um resultado sem
  imagem nenhuma. A regra é `max(exposição, mínimo do stream)`, e **não** se limita ao
  `SENSOR_INFO_MAX_FRAME_DURATION` declarado, que é subdeclarado como o de exposição.
- **Deixar o sensor assentar, e não parar o pedido repetido para disparar.** Três regras, todas
  aprendidas a falhar:
  1. **Assentar é esperar pelos valores, não contar frames.** Contam-se só os resultados que já
     reportam o `SENSOR_EXPOSURE_TIME` e o `SENSOR_SENSITIVITY` pedidos. Contar frames quaisquer
     deixa passar frames velhos com as definições anteriores, e a captura vai a meio da transição.
  2. **Não parar o repetido para disparar.** Parar e submeter logo a seguir faz o HAL descartar o
     pedido — `onCaptureFailed` razão 0, ou resultado sem imagem. Mantém-se o repetido e
     **intercala-se** a captura, identificando a imagem certa pelo `SENSOR_TIMESTAMP`.
  3. **Mudar parâmetros a meio de uma sessão é frágil.** Quando se varre uma gama de valores, vale
     a pena uma sessão por valor.
- **Verificar, não confiar.** Uma chave presente em `REQUEST_AVAILABLE_REQUEST_KEYS` pode ainda ser
  ignorada. A verdade está no `CaptureResult`.

### 5.3 Leitura do resultado

De cada `TotalCaptureResult` guardar:

`SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY`, `SENSOR_TIMESTAMP`,
`SENSOR_DYNAMIC_BLACK_LEVEL`, `SENSOR_DYNAMIC_WHITE_LEVEL`, `SENSOR_GREEN_SPLIT`,
`SENSOR_NEUTRAL_COLOR_POINT`, `SENSOR_NOISE_PROFILE`, `SENSOR_ROLLING_SHUTTER_SKEW`,
`STATISTICS_LENS_SHADING_CORRECTION_MAP`, `LENS_FOCUS_DISTANCE`, `LENS_APERTURE`,
`LENS_FOCAL_LENGTH`, `CONTROL_POST_RAW_SENSITIVITY_BOOST`, `NOISE_REDUCTION_MODE`, `EDGE_MODE`,
`SHADING_MODE`, `DISTORTION_CORRECTION_MODE`.

Usar o **black level dinâmico** do resultado, não o padrão estático — varia com temperatura e ISO.

### 5.4 Pedido vs aplicado

O HAL corta e quantiza. A UI mostra-o como funcionalidade de primeira linha:

```kotlin
data class Applied<T>(val requested: T, val applied: T, val clamped: Boolean)
```

- **Tempo**: limitado a `SENSOR_INFO_EXPOSURE_TIME_RANGE`. **Correcção vinda da F1:** o *line time*
  do sensor **não é exposto** pelo Camera2, portanto não se pode quantizar antes de pedir. Limita-se
  ao intervalo e lê-se no `CaptureResult` o que o HAL realmente usou — daí haver dois cortes a
  registar, o nosso e o dele.
- **ISO**: limitado a `SENSOR_INFO_SENSITIVITY_RANGE` e quantizado aos passos de ganho do HAL.
- **Foco**: limitado a `[0, 1/LENS_INFO_MINIMUM_FOCUS_DISTANCE]` dioptrias.
- **Abertura**: forçada ao conjunto de `LENS_INFO_AVAILABLE_APERTURES`.

---

## 6. Pipeline de revelação (GPU)

Uma única implementação, usada pelo visor e pela exportação.

### 6.1 Etapas

| # | Etapa | Detalhe |
|---|---|---|
| 1 | Descompactar | `RAW_SENSOR` = 1 plano de 16 bits; respeitar `rowStride` |
| 2 | Nível de preto e branco | `(x − blackLevel[cfa]) / (whiteLevel − blackLevel[cfa])` |
| 2b | **Correcção de vinhetagem** | **por perfil medido, por canal e por modelo de telefone.** Ver abaixo |
| 3 | Ganhos de balanço de brancos | do Kelvin escolhido pelo utilizador, aplicados por canal do CFA |
| 4 | *Demosaicing* | Malvar-He-Cutler 5×5 para exportação; *binning* 2×2 para o visor |
| 5 | Matriz do sensor → XYZ | `ForwardMatrix` interpolada entre os dois iluminantes de referência |
| 6 | XYZ → espaço de saída | Display P3 linear (ou sRGB / ProPhoto, à escolha) |
| 7 | Exposição de revelação | ganho linear, ±3 EV, por omissão 0 |
| 8 | *Rolloff* de altas luzes | Reinhard estendido com ponto branco configurável; por omissão suave |
| 9 | Contraste / saturação | ambos neutros por omissão (0 e 1,00) |
| 10 | Nitidez / redução de ruído | **por omissão zero**; existem como controlos explícitos |
| 11 | Transformada de saída | codificação sRGB/P3 para o ecrã, 16 bits lineares ou codificados para ficheiro |

As etapas 9 e 10 são controlos do utilizador com valor neutro por omissão. Nunca são aplicadas sem
pedido e o seu valor aparece no sidecar.

**A etapa 2b não existia nesta especificação.** Foi acrescentada depois de se medir que o RAW **não
vem corrigido**, apesar de o HAL declarar `SENSOR_INFO_LENS_SHADING_APPLIED = true` e de devolver um
mapa de correcção que é exactamente 1,0000 em todas as 221 posições da malha — um *stub*. Sem esta
etapa, todas as fotografias saem com os cantos a 20% do centro na objectiva principal e a 14% na
ultra-grande-angular. Isso não é fidelidade, é um defeito.

Regras da etapa 2b:

- **antes do balanço e do *demosaicing***: é propriedade da óptica e do sensor, e tem de sair antes
  de os píxeis se misturarem;
- **por canal, não por luminância**: medido, o vermelho cai ~15% mais do que o verde no canto, e
  quase na mesma proporção em duas ópticas diferentes — o que aponta para o filtro de cor do
  sensor. Corrigir com um ganho único deixaria desvio de cor visível nas bordas;
- **perfil medido, nunca inventado**: sem calibração para aquele modelo e aquela câmara, não se
  corrige;
- **força controlada pelo utilizador, 100% por omissão**, e aplicada **em stops**: metade da força
  é metade dos stops. O custo é real — o canto corrigido fica com 2,4 vezes o grão do centro — e
  esconder esse custo seria o tipo de decisão que este projeto existe para não tomar.

A calibração faz-se com `tools/shading.py` a partir de uma chapa plana: difusor encostado à lente
**mais** uma fonte extensa atrás (ecrã branco iluminado, ou céu). Valida-se por **simetria radial**,
não pela largura da distribuição.

### 6.2 Ciência da cor

O caminho segue a especificação DNG:

```
camRGB_norm = camRGB / AsShotNeutral
XYZ_D50     = ForwardMatrix(cct) · camRGB_norm
XYZ_D65     = adaptação de Bradford(XYZ_D50)
RGB_linear  = M_saída · XYZ_D65
```

- `ForwardMatrix(cct)` interpola-se entre `SENSOR_FORWARD_MATRIX1/2` conforme a temperatura de cor
  escolhida, entre `SENSOR_REFERENCE_ILLUMINANT1/2`.
- `AsShotNeutral` deriva do Kelvin escolhido pelo utilizador — é o balanço de brancos que **ele**
  escolheu, e vai como metadado, sem ser cozinhado no pixel do DNG.
- Se as matrizes do dispositivo forem ausentes ou grosseiramente erradas, calibrar um perfil próprio
  com uma carta de cor e guardá-lo por modelo de dispositivo.

### 6.3 Formato das texturas

RAW de 16 bits sem sinal: textura `GL_R16UI` com amostragem inteira (`usampler2D`), ou `GL_R16F` se
a precisão bastar. Evitar converter para 8 bits em qualquer ponto do caminho.

### 6.4 Desempenho do visor

O visor corre o pipeline sobre o stream RAW repetido. Mitigações, por ordem:

1. **Visor a meia resolução por *binning* 2×2 do CFA** — cada quarteto RGGB dá directamente um
   pixel RGB, sem *demosaicing*. Rápido e sem artefactos. A exportação usa o algoritmo completo.
2. Escolher um tamanho RAW menor de `getOutputSizes` quando exista.
3. Aceitar a taxa de frames que o HAL permitir para RAW e dizê-lo na UI.

O visor a meia resolução é uma **excepção declarada à regra P3**: a diferença é apenas de
resolução e de algoritmo de *demosaicing*, nunca de tom, cor ou exposição. Tem de haver um teste
que o comprove (§10.3).

### 6.5 Threading

O visor tem de ter ciclo próprio. Misturar o desenho da imagem com a recomposição do Compose é a
receita para um visor aos saltos.

| Thread | Responsabilidade |
|---|---|
| Principal / Compose | controlos, leituras, estado da UI. **Nunca** toca em GL nem em `Image` |
| Render (EGL sobre `SurfaceView`) | possui o contexto GL e corre o pipeline; consome do `ImageReader` |
| Camera (`HandlerThread`) | *callbacks* de `CameraDevice` e da sessão; entrega `TotalCaptureResult` |
| I/O | escrita de DNG, TIFF, AVIF e sidecar, fora do caminho do visor |

Regras:

- Usar `SurfaceView` com EGL próprio, não `GLSurfaceView` (dá mais controlo sobre a cadência) nem
  `TextureView` (acrescenta uma composição).
- O disparo **reutiliza o mesmo contexto GL e os mesmos shaders** do visor — é o que garante P3.
  Só muda a resolução de entrada e o algoritmo de *demosaicing*.
- Fechar sempre o `Image` do `ImageReader`; com `maxImages = 2..3`, um vazamento pára o stream.
- A exportação não pode bloquear o visor: copiar o RAW para um *buffer* próprio, libertar o `Image`,
  e revelar em segundo plano.

---

## 7. Modelo de câmara

### 7.1 Corpo e objectivas fixas

Um telefone é **um corpo com várias primes fixas**. Escolher "objectiva" é escolher a câmara física.
Cada perfil de objectiva sai da sonda:

```kotlin
data class LensProfile(
    val cameraId: String,
    val focalLengthMm: Float,          // LENS_INFO_AVAILABLE_FOCAL_LENGTHS
    val apertures: List<Float>,        // LENS_INFO_AVAILABLE_APERTURES
    val minFocusDistanceM: Float,      // 1 / LENS_INFO_MINIMUM_FOCUS_DISTANCE
    val focusCalibration: Int,         // LENS_INFO_FOCUS_DISTANCE_CALIBRATION
    val hasRaw: Boolean,
    val rawSize: Size,
    val sensorPhysicalSizeMm: SizeF,   // SENSOR_INFO_PHYSICAL_SIZE
)
```

Se `apertures.size > 1`, o controlo de abertura aparece; se for 1, desaparece e mostra-se o valor
como facto — não como controlo desactivado.

### 7.2 Equivalência a 35 mm

Mostrar isto na UI é honestidade útil, não é decoração:

```
crop        = 43,27 mm / diagonal(SENSOR_INFO_PHYSICAL_SIZE)
focalEquiv  = focalLengthMm × crop
aperturaEq  = fNumber × crop                       // profundidade de campo e luz recolhida
isoEquiv    = iso × (areaFullFrame / areaSensor)   // ruído comparável, a igual exposição
```

Com `areaFullFrame = 864 mm²`. A equivalência de ISO ignora diferenças de geração de sensor e serve
como ordem de grandeza — deve ser apresentada como tal.

### 7.3 Modos de disparo

**Automatizar a exposição não é mastigar a imagem.** O que nunca se automatiza é o que acontece aos
píxeis. Os modos usam o **fotómetro próprio** (§7.4), nunca o AE do HAL, que está em `OFF`.

| Modo | O utilizador define | A aplicação resolve |
|---|---|---|
| **M** | tempo, abertura, ISO | nada; a agulha do fotómetro só informa |
| **A** | abertura, ISO | tempo |
| **S** | tempo, ISO | abertura (se variável; se fixa, S ≡ M e diz-se) |
| **P** | ISO | tempo e abertura, por linha de programa |

Compensação de exposição de ±3 EV em terços. Em **M** a compensação só desloca o alvo da agulha,
como numa câmara a sério.

### 7.4 Fotómetro próprio

Com o AE desligado, a medição é da aplicação. Calcula-se das estatísticas do RAW (histograma da
imagem reduzida, na GPU ou numa versão em CPU de baixa resolução):

- **Multi** — média geométrica da luminância de toda a moldura
- **Centro** — média com peso gaussiano no centro
- **Spot** — média num disco central de ~5% do quadro

Alvo: 18% de reflectância na escala linear do sensor. Saída: desvio em EV, sugestão de par
tempo/abertura, e alcance dinâmico da cena (percentis 3% e 97%).

### 7.5 Ajudas de visor

Todas legítimas, porque o visor é WYSIWYG:

- **Histograma ao vivo** do RAW, com eixo em *stops* abaixo da saturação — não em 0–255 depois de
  uma curva
- **Zebras** nos píxeis com qualquer canal a saturar
- **Focus peaking** por gradiente de luminância, com limiar ajustável
- **Ampliação de foco** 4× na zona de foco
- **Nível** a partir do acelerómetro
- **Grelha** de terços
- Escala de distância de foco, se `focusCalibration != UNCALIBRATED`

---

## 8. Saída de ficheiros

### 8.1 O que se escreve por disparo

| Ficheiro | Conteúdo | Papel |
|---|---|---|
| `LTNT_0001.dng` | `RAW_SENSOR` de frame único, 16 bits, metadados completos | o negativo, imutável |
| `LTNT_0001.tif` | 16 bits por canal, sem compressão, Display P3 | o positivo revelado, para editar |
| `LTNT_0001.avif` | 10 bits, com perdas | opcional, para partilhar |
| `LTNT_0001.json` | sidecar | pedido vs aplicado, parâmetros de revelação, perfil |

**Nunca** se escreve o JPEG/HEIC produzido pelo HAL.

### 8.2 DNG

`DngCreator(characteristics, captureResult)` faz o trabalho pesado:

```kotlin
DngCreator(ch, result).apply {
    setOrientation(exifOrientation)
    setDescription(sidecarSummary)
    setThumbnail(previewBitmap)
}.writeImage(outputStream, rawImage)
```

Cuidados e limitações:

- Escreve DNG **sem compressão** a partir de `RAW_SENSOR` (não aceita RAW10/RAW12 empacotado).
  ~2 bytes por pixel: 12,5 MP ≈ 25 MB.
- Escreve `ColorMatrix1/2`, `ForwardMatrix1/2`, `AsShotNeutral`, `NoiseProfile` e o mapa de lens
  shading como opcode a partir do `CaptureResult`. **Confirmar com `exiftool`** o que saiu de facto
  no dispositivo em causa, em vez de assumir.
- **O `GainMap` tem de ser inspeccionado, não presumido.** O `DngCreator` escreve quatro opcodes
  `GainMap` em `OpcodeList2`, um por canal. Como o RAW já vem com o shading aplicado
  (`SENSOR_INFO_LENS_SHADING_APPLIED`), havia o risco de um revelador corrigir a vinhetagem **duas
  vezes**. Verificado no dispositivo de referência: os ganhos são **1,0000 em toda a malha de
  13×17**, ou seja um mapa identidade, e não há dupla correcção. Num telefone onde não fossem,
  seria preciso remover o opcode. `tools/dngcheck.py` descodifica-os.
- **`AsShotNeutral` controla-se por via indirecta, e funciona.** O `DngCreator` não tem API para o
  definir — deriva-o de `SENSOR_NEUTRAL_COLOR_POINT`. Mas a experiência 8 da F1 mostrou que
  **`COLOR_CORRECTION_GAINS` determina o ponto neutro, e é exactamente o seu recíproco**:

  | Ganhos RGGB pedidos | Ponto neutro devolvido |
  |---|---|
  | 1,0 / 1,0 / 1,0 / 1,0 | 1,0 · 1,0 · 1,0 |
  | 2,0 / 1,0 / 1,0 / 0,5 | 0,5 · 1,0 · 2,0 |

  Portanto: para escrever no DNG o balanço de brancos escolhido pelo utilizador, calculam-se os
  ganhos do iluminante pretendido e definem-se em `COLOR_CORRECTION_GAINS` com
  `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX`. O `AsShotNeutral` sai correcto.

  E é honesto: com AWB desligado, os ganhos aplicam-se às saídas processadas, **não ao RAW**. O
  mosaico fica intocado e são só os metadados a declarar a intenção do fotógrafo — que é
  exactamente o que se quer. Não é preciso escrever o DNG à mão.
- Em nenhum caso se cozinha um *look* no DNG.

### 8.3 TIFF de 16 bits

Não há escritor no Android. É um TIFF baseline trivial (~150 linhas): `BitsPerSample = 16,16,16`,
`SamplesPerPixel = 3`, `PhotometricInterpretation = 2`, `PlanarConfiguration = 1`,
`Compression = 1`, mais a etiqueta ICC (34675) com o perfil de saída.

Evitar `Bitmap`: `RGBA_F16` existe mas o caminho de compressão do Android reduz a 8 bits.

### 8.4 AVIF / HEIC

Codificação, não processamento — não viola nenhum princípio. AV1 de 10 bits via `MediaCodec` onde o
codificador exista; `androidx.heifwriter` para HEIC de 8 bits como alternativa. Sempre a partir do
render próprio.

### 8.5 Armazenamento

`MediaStore.Images` em `Pictures/Latente/`, com `IS_PENDING` durante a escrita. Sem permissões de
armazenamento em API 29+. Nomes sequenciais persistidos em preferências, ao estilo `DSC00001`.

---

## 9. Fases de trabalho

Cada fase tem um critério de aceitação verificável. Não avançar sem o cumprir.

### F0 — Sonda de capacidades
Aplicação de despejo de características (§3).
**Aceitação:** relatório JSON+TXT no PC, com o veredicto por câmara.

### F1 — Captura RAW crua
Um botão, parâmetros manuais em código, escrita de DNG. Sem visor decente.
**Aceitação:** o DNG abre em darktable/RawTherapee; `exiftool` mostra os metadados esperados;
pedido e aplicado coincidem dentro da tolerância; a imagem **não** está com redução de ruído
(verificar §10.2).

### F2 — Revelador, fora de linha
O pipeline GPU aplicado a um DNG já no disco, com saída TIFF16.

**Aceitação, em dois níveis — ambos CUMPRIDOS.**

O primeiro valida a matemática da cor: o render próprio e um render independente concordam dentro de
poucos por cento nos valores de um cartão de cinza. **0,7% em R/G, 2,3% em B/G, e os quase-neutros a
0,0%.**

Uma ressalva que a execução obrigou a escrever: **o oráculo não pode ser o darktable quando se corrige
a vinhetagem.** O `GainMap` destes DNG é identidade, portanto o darktable não corrige vinhetagem
nenhuma; a nossa correcção é por canal e muda a cromaticidade em direcção às bordas de propósito. Com
correcção, contra darktable, a divergência sobe a 4,6% / 10,8% — sem ser erro. O oráculo é o
`tools/develop.py` com o mesmo perfil.

O segundo valida o porte para GLSL, e é mais apertado: **o caminho do CPU e o caminho da GPU, sobre o
mesmo DNG, dentro do telefone, têm de concordar a menos de 1 em 255** — o arredondamento de oito
bits. Não se compara com um revelador externo porque a matemática dos dois caminhos é a mesma de
propósito, ao ponto da ordem das operações; portanto qualquer diferença maior é bug de canalização, e
não dúvida sobre cor. Está implementado como um botão da aplicação, `diag/GpuCheck.kt`.
**Medido: diferença máxima de 1 em 255, e nenhuma amostra acima disso.**

A razão de ser um botão e não um teste no PC: o oráculo em Kotlin e o shader estão os dois no
telefone, e trazer ficheiros para comparar fora perderia precisão e tempo sem ganhar rigor.

**Desempenho medido**, que interessa à F3: 292 ms na GPU contra 3802 ms no CPU para 12,48 Mpx com
Malvar-He-Cutler completo — 13×. A resolução de visor é uma fracção disto.

### F3 — Visor WYSIWYG
O mesmo pipeline sobre o stream RAW ao vivo.
**Aceitação: CUMPRIDA.** Disparado a partir do visor, quatro vezes, com o frame **casado pelo
`SENSOR_TIMESTAMP`** nas quatro e o visor a continuar a correr entre os disparos. O ficheiro corresponde
ao que estava no visor por construção — mesmo stream, mesmos uniformes, mesmos shaders — e por medição:
uniformes ao vivo idênticos aos do ficheiro, cor a 0,7% / 1,9%.

Regra que a implementação obrigou a escrever: **o disparo a partir do visor não pára o pedido
repetido.** Parar apagaria o visor durante a captura, e já se sabia da F1 que parar e disparar logo a
seguir faz o HAL descartar o pedido. Intercala-se, e o frame certo identifica-se pelo timestamp.

**Viabilidade: MEDIDA e confirmada.** O visor alimentado pelo stream RAW dá **19 a 28 fps** neste
dispositivo, conforme o estado térmico, e **o travão é a câmara**: 25 a 37 ms bloqueado à espera do
frame contra 9 a 14 ms de trabalho na GPU. Os 23 MB por frame atravessam a fronteira CPU → GPU a
1,3–1,8 GB/s e cabem inteiros dentro da espera, portanto optimizar a GPU não daria um frame a mais.

Duas consequências para o desenho. O ritmo do visor é **um limite do dispositivo e não nosso**, e não
há trabalho de desempenho a fazer antes da UI. E o piso de frame declarado pelo HAL — 33,3 ms — é um
limite do que se pode **pedir**, não do que se recebe: o stream entrega 36 a 52 ms.

Regra que a medição obrigou a escrever: **no visor, o consumo é por `acquireLatestImage`, não por
fila.** Um *listener* que adquira imagens e as guarde mata o stream — com três por fechar, o
`acquireNextImage` recusa, a notificação perde-se e a imagem congela. E é a semântica certa de
qualquer maneira: um visor mostra o presente, não uma fila do passado.

**O visor está no ecrã e verificado.** 21,9 fps; orientação confirmada por correlação contra a
referência a **+0,993**, sem espelhamento; enquadramento medido a 0,7505 contra 0,7500 esperado, com
barras e nada cortado; uniformes ao vivo **idênticos** aos do ficheiro; cor a **0,7% / 1,9%** da
referência independente. Falta só disparar a partir do visor, bloqueado pela unificação dos dois
caminhos de consumo do `ImageReader`.

**A apresentação é um passe separado da revelação**, por decisão de desenho: a revelação desenha para
uma textura com a matemática do ficheiro, e a apresentação leva-a ao ecrã com rotação e enquadramento.
Assim mudar como se mostra não pode mudar o que se mostra. E o enquadramento é **por dentro, com
barras**: cortar para preencher o ecrã esconderia parte do que vai ser gravado, e um visor que esconde
é da mesma família de mentira que este documento existe para evitar.

**Nota sobre verificar por captura de ecrã:** o PNG do `screencap` é **Display P3**, não sRGB. Lê-lo
como sRGB dá 7,5% de erro sistemático que não existe. Ver o ENTREGA para a matriz de conversão.

E daí uma decisão em aberto para a F5: o ecrã é P3 e o compositor converte, portanto sair em sRGB
**atira gamut fora**. O `ColorScience.Output.DISPLAY_P3` já existe e está testado.

### F4 — Câmara
Objectivas, modos M/A/S/P, fotómetro, compensação, medição, peaking, zebras, ampliação, histograma,
nível, equivalências.
**Aceitação:** uma sessão fotográfica completa sem tocar em código.

### F5 — Exportação e biblioteca
TIFF16, sidecar, nomes, MediaStore, ecrã de ficheiros e de análise.

**Aceitação:** os ficheiros aparecem na galeria e **o sidecar reconstrói a revelação**. A segunda parte
está cumprida e medida: o mesmo DNG revelado pelo telefone e por `develop.py --sidecar` concorda a
**0,13% em R/G e 0,40% em B/G**.

**Decisão: não há AVIF, e não há formato comprimido nenhum.** Verificou-se no `android.jar` da API 36
que o `Bitmap.CompressFormat` só tem JPEG, PNG e WEBP — AVIF exigiria libavif, o que quebra a decisão de
zero dependências de runtime, e HEIF exigiria `MediaCodec` com `MediaMuxer`. Escolheu-se o que **entrega
a melhor imagem sem perdas**: o TIFF de 16 bits com ICC, que já existe. A compressão seria conveniência
de tamanho, e este projeto existe para não trocar qualidade por conveniência.

São portanto **três ficheiros por fotografia**, e cada um tem um papel: o **DNG** é o negativo e é
imutável; o **TIFF16** é a cópia revelada; o **JSON** é a receita que liga um ao outro.

### F6 — Verificação anti-mastigação
A bateria de testes de §10 automatizada onde possível.
**Aceitação:** relatório assinado por dispositivo, dizendo o que o HAL faz e o que não faz.

---

## 10. Verificação: provar a promessa

A promessa do produto é verificável. Não deve ser afirmada sem estes testes.

**Resultado no dispositivo de referência:** linear dentro de 1,5% do ideal ao longo de sete
duplicações, de 0,3% a 39% do nível de branco. Medir a **média** não chega perto da saturação —
usar o **percentil 20** do mesmo recorte, que são os píxeis escuros e não saturam, para separar
corte de cena de um joelho no caminho do RAW.

### 10.1 Linearidade
Fotografar uma cena estática variando só o tempo de exposição em passos de 1 EV. A média do RAW numa
zona de médios tem de escalar linearmente com o tempo. Desvios revelam curva de tom, ganho oculto
ou substituição de frame.

### 10.2 Detecção de redução de ruído
Disparo a ISO alto de uma superfície uniforme. Calcular o espectro de potência do ruído: **plano**
significa intocado; supressão de altas frequências significa redução de ruído aplicada ao RAW.

### 10.3 Visor = ficheiro
Com a cena e os parâmetros fixos, capturar o frame do visor e o ficheiro exportado. Ao comparar à
mesma resolução, a diferença tem de ser desprezável e explicável apenas pelo *demosaicing*.

### 10.4 Lens shading
Ler primeiro `SENSOR_INFO_LENS_SHADING_APPLIED`, que declara a resposta. Depois confirmar:
fotografar um campo uniforme com `SHADING_MODE = OFF`. Se os cantos já vierem planos, o HAL aplicou
correcção ao RAW e o mapa de shading do resultado é redundante — registar por dispositivo.

### 10.5 ZSL desligado
Fotografar um cronómetro em movimento e confirmar que o frame corresponde ao instante do disparo,
não a um frame anterior do anel de ZSL.

### 10.6 Nitidez
Fotografar uma aresta de alto contraste e procurar sobre-oscilação no perfil de bordo. Qualquer halo
no RAW é nitidez do HAL.

### 10.7 Comparação de referência
O mesmo enquadramento, na câmara do sistema e nesta. Não é um teste de qualidade, é documentação da
diferença — serve para a interface poder mostrar o que o pipeline computacional faria.

---

## 11. Riscos conhecidos

Estado à luz do dispositivo de referência (§2.4). **C** = confirmado como real, **A** = afastado,
**?** = ainda por verificar.

| | Risco | Impacto | Mitigação |
|---|---|---|---|
| **A** | Só a câmara principal expõe RAW | — | Há duas traseiras utilizáveis, 23 e 14 mm. Só a tele fica fora |
| **C** | *Binning* quad-Bayer | 12,5 MP, sem alternativa | `getHighResolutionOutputSizes()` vazio: não há caminho. Declarar na UI |
| **C** | **RAW de 10 bits** | ~10 stops de tecto teórico | Novo risco, não previsto. Revelador em ponto flutuante, sem fingir 16 bits |
| **C** | Shading já aplicado ao RAW | RAW menos cru do que se diz | Declarado pela plataforma; sem mapa para desfazer. Dizê-lo na interface |
| **C** | Nível de preto já subtraído | Dupla subtracção corromperia as sombras | Não subtrair na revelação |
| **A** | Tecto de exposição baixo | — | **Afastado na F1: o declarado subdeclara em 10×.** O HAL honra ≥1 s. Sondar o limite real ao arranque e guardá-lo por modelo |
| **?** | Combinação RAW + preview recusada | Sem visor WYSIWYG | **Afastado na F1:** RAW de 12,5 MP + YUV 1080p aceites e a entregar frames |
| **C** | Acesso restrito a câmaras físicas | Tele de 66 mm inacessível | Registado; a principal chega-se pelo id lógico 0 |
| **A** | Matrizes de cor ausentes/erradas | — | Completas nas cinco câmaras utilizáveis |
| **A** | Obturador electrónico: registar *skew* | — | `SENSOR_ROLLING_SHUTTER_SKEW` ausente. A distorção existe, a medição não |
| **?** | Chaves silenciosamente ignoradas | Promessa quebrada sem aviso | Verificar sempre no `CaptureResult` e avisar |
| **?** | `SHADING_MODE = OFF` sem efeito no RAW | Promessa quebrada | Contradiz `LENS_SHADING_APPLIED`; testar campo plano em F1 |
| **?** | Taxa de frames baixa no visor | Visor pouco fluido | RAW a 30 fps e *binning* 2×2 |
| **?** | Sobreaquecimento | Ruído e limitação | Menos relevante com tecto de 1/10 s |

---

## 12. Decisões de desenho já tomadas

Fixadas em conversa e assumidas neste documento:

1. **O visor mostra o resultado final** (WYSIWYG). É o *Setting Effect ON*. Consequência directa:
   o pipeline próprio é obrigatório na v1, não é um extra.
2. **Não há restrições de disciplina.** Histograma, zebras e revisão estão sempre disponíveis. Só a
   *captura* é manual e deliberada.
3. **A abertura é controlo quando o hardware a tem** e facto declarado quando não tem, decidido por
   `LENS_INFO_AVAILABLE_APERTURES`.
4. **Sem metáfora de película.** A revelação é neutra, com todos os controlos a zero por omissão.
5. **O ficheiro é o melhor possível**: DNG intocado mais TIFF de 16 bits do render próprio.

---

## 13. Referências

- Camera2: `android.hardware.camera2` — `CameraCharacteristics`, `CaptureRequest`, `CaptureResult`
- `android.hardware.camera2.DngCreator`
- Especificação Adobe DNG 1.6 — matrizes de cor, `AsShotNeutral`, `NoiseProfile`, opcodes
- Malvar, He, Cutler (2004), *High-quality linear interpolation for demosaicing*
- `exiftool` — inspecção dos DNG produzidos
- darktable / RawTherapee — referência de revelação neutra para validar o pipeline
- Android CTS ITS (*Image Test Suite*) — inspiração para os testes de §10
