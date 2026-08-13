# LATENTE — brief de UI/UX

Texto para colar num assistente de design (Figma Make ou equivalente). Está escrito para ser lido por
quem não conhece o projeto, e todos os números aqui são **medidos no dispositivo**, não estimados.

---

## O prompt

Desenha a interface de uma aplicação Android de fotografia chamada **Latente**.

### O que a aplicação é

Uma câmara manual que grava RAW **sem processamento computacional nenhum**: sem *beautify*, sem nitidez
automática, sem redução de ruído, sem *tone mapping* local, sem fusão de vários frames, sem o JPEG do
sistema. O visor mostra o resultado final produzido pelo pipeline da própria aplicação e não pelo
processador de imagem do fabricante — o equivalente ao *Setting Effect ON* de uma Sony.

O utilizador é um fotógrafo que quer controlo total e que está farto de telefones que decidem por ele. A
referência de ergonomia é uma **Sony α7 III**: modos M/A/S/P, fotómetro, ajudas de visor, tudo à mão.

O princípio que manda em tudo, e que a interface tem de servir:

> **Nunca prometer mais do que o telefone entrega, e dizer sempre o que o sistema fez pelas nossas
> costas.**

### O que **não** desenhar

Nada de filtros, modos de cena, «melhorar automaticamente», desfoque de retrato simulado, HDR, nem
qualquer referência a IA. Não é omissão de funcionalidades: são o oposto do produto. Também não desenhes
molduras, autocolantes nem partilha em redes sociais.

E nada de animações que sugiram fluidez que a imagem não tem — ver a taxa de frames abaixo.

### Restrições reais, medidas no dispositivo

Estas não são preferências. São o que o hardware faz, e a interface tem de as respeitar.

| O que | Valor medido | Consequência para o desenho |
|---|---|---|
| Taxa do visor | **19 a 29 fps**, limitado pela câmara | não desenhar transições a 60 fps sobre a imagem; a imagem ao vivo não é fluida e não vale finge-lo |
| Formato do sensor | 4080×3060, **4:3** | num ecrã 1080×2340 sobra muito espaço acima e abaixo — é aí que os controlos vivem |
| Enquadramento | **nunca se corta a imagem** | a imagem aparece inteira com barras; um visor que esconde parte do quadro é uma mentira |
| Exposição possível | de 15 µs a **1,8 s** | um disparo pode levar quase dois segundos; é preciso mostrar que está a acontecer |
| Abertura | **uma só, f/1,8** | um controlo de abertura seria decorativo neste corpo; os modos A e P coincidem, e a interface deve dizê-lo em vez de fingir escolha |
| ISO | 25 a 3200, **analógico só até 640** | acima de 640 é ganho digital — tem de estar marcado como diferente, não é mais sensibilidade |
| Foco | 10 cm a infinito | o controlo é linear em **dioptrias**, não em metros: assim o curso reparte-se pelo perto, onde o foco é crítico |
| Ecrã | 1080×2340, retrato fixo | uma mão, alcance do polegar |

### O que tem de estar sempre visível

Isto decide uma fotografia, e nenhuma aplicação de fabricante mostra:

1. **Margem até ao corte**, em EV, e **percentagem já cortada**. É o corte **do sensor** — o que se
   perde para sempre —, não o do que está no ecrã.
2. Modo (M/S/A/P), tempo, ISO, distância de foco, temperatura em K, tinta.
3. Quando a exposição do visor **difere** da do disparo, as duas. Acontece em cena escura: o visor
   trava em 1/8 s para não congelar e compensa a luz que falta com ganho, portanto fica mais ruidoso e
   com o brilho certo. A interface tem de dizer «disparo 1,8 s · visor 1/8 s +4,6 EV de ganho» em vez de
   esconder a diferença.

### Ajudas de visor

Ligáveis, e nunca ficam no ficheiro:

- **Realce de foco** (*peaking*): as arestas nítidas marcadas a laranja.
- **Zebras**: riscas diagonais sobre o que o **sensor** cortou.
- **Histograma** do verde do sensor, escala logarítmica, com a marca do corte.
- **Nível**: uma linha que fica verde dentro de meio grau.

### Os controlos, e o problema a resolver

Há mais parâmetros contínuos do que espaço: **foco, temperatura, tinta**, e a compensação de exposição.
A versão actual resolve-o com um comando único e um botão que diz o que ele mexe — como o anel de uma
máquina. Funciona, mas é um compromisso, e é o problema de UX mais interessante deste desenho.

Ações discretas: obturador, ciclo de modo, escolher objectiva (duas: 23 mm f/1,8 e 14 mm f/2,2), ligar
ajudas, ir aos negativos.

### O ecrã de negativos

Cada fotografia são **três ficheiros** com papéis distintos, e a lista mostra **uma linha por
fotografia**, não uma por ficheiro:

- o **negativo** (DNG), que é imutável;
- a **receita** (JSON), que diz exactamente como foi revelado e permite repetir a revelação ao bit;
- a **cópia revelada** (TIFF de 16 bits), que pode ainda não existir.

Tocar numa fotografia mostra a análise: se o frame gravado é o que se pediu, onde o sistema divergiu do
que lhe pedimos, a receita, e a lista do que ele faz pelas costas.

### Tom visual

Instrumento, não aparelho de consumo. Escuro, porque se está a olhar para imagens e o interface não deve
competir com elas. Tipografia legível de relance e números mais importantes que ícones — quem usa isto
lê valores, não adivinha símbolos. Sem cantos redondos exagerados nem sombras decorativas.

Uma nota sobre cor: o ecrã é Display P3 e a aplicação sabe-o. A imagem no visor está calibrada; **a
interface não deve competir com ela em saturação**.

### O que existe hoje

Andaime funcional em componentes Android simples, deliberadamente feio, feito para verificar e não para
usar. Tudo o que está descrito acima já funciona e está medido — falta-lhe a forma. Não há nada a
preservar visualmente.

### Entregáveis pedidos

1. O **visor** em condições normais, com tudo visível.
2. O **visor em cena escura**, com as duas exposições e o aviso de ganho.
3. O visor com **cada ajuda** ligada.
4. O ecrã de **negativos** e o de **análise**.
5. A solução para os controlos contínuos, que é o problema a resolver.
