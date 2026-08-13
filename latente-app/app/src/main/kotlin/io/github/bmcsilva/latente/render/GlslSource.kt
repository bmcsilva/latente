package io.github.bmcsilva.latente.render

/**
 * Os shaders do revelador.
 *
 * Cada bloco é a tradução directa do Kotlin equivalente, e está anotado com o sítio de onde veio.
 * A regra é não haver aritmética aqui que não exista lá: o Kotlin é o oráculo, e qualquer
 * divergência entre os dois é bug do shader.
 *
 * O que **não** está aqui de propósito: a interpolação de matrizes por temperatura, a força da
 * correcção de vinhetagem, e a conversão de ordem de linhas para colunas. Tudo isso é feito na CPU
 * pelo `GlUniforms`, uma vez por imagem, e chega ao shader já resolvido. Regra geral: o que se faz
 * uma vez por imagem não se faz uma vez por pixel, e o que é subtil não se faz onde não se pode
 * testar.
 */
object GlslSource {

    /**
     * Tamanho da tabela de vinhetagem declarada nos shaders.
     *
     * O GLSL ES não tem arrays de tamanho variável em uniformes, por isso reserva-se um máximo. Os
     * perfis medidos têm 16 anéis; o `GlDeveloper` recusa perfis maiores em vez de os cortar em
     * silêncio, porque um perfil truncado dá uma imagem plausível e errada nos cantos.
     */
    const val MAX_RINGS = 32

    /**
     * Os nomes dos uniformes, tal como declarados nos shaders.
     *
     * Estão aqui — e não escritos à mão no `GlDeveloper` — porque um nome com erro dá
     * `glGetUniformLocation` a -1, e o `glUniform*` com -1 **não é erro de GL**: passa em silêncio e
     * o shader trabalha com zeros. Tendo os nomes num só sítio, um teste na JVM confirma que cada um
     * existe mesmo no texto do shader.
     */
    val UNIFORM_NAMES = listOf(
        "uMosaico", "uMatrizCor", "uBalanco", "uCfa", "uPreto", "uBranco",
        "uVinhetagem", "uAneis", "uForca", "uRolloff", "uTamanho")

    /**
     * Vértice comum: um triângulo que cobre o ecrã, sem vértices em memória.
     *
     * Três vértices gerados a partir do `gl_VertexID` cobrem o alvo inteiro. É mais barato do que
     * dois triângulos e evita a costura na diagonal.
     */
    const val VERTEX = """#version 310 es
precision highp float;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

    /**
     * Revelação completa, do mosaico ao espaço de saída.
     *
     * O mosaico entra como `usampler2D` de `GL_R16UI`: inteiros de 16 bits sem sinal, sem
     * interpolação nem normalização automática. É o único formato que garante que os dez bits
     * úteis chegam intactos — um `GL_R16F` perderia precisão nas sombras, onde ela mais falta faz.
     *
     * Ordem das etapas, igual à do `RawPipeline`:
     * níveis → vinhetagem → balanço → *demosaicing* → matriz de cor → *rolloff* → codificação.
     *
     * A vinhetagem e o balanço são aplicados **dentro** do `amostra()`, e não depois: o filtro do
     * Malvar-He-Cutler mistura vizinhos, e misturar amostras corrigidas de forma diferente daria
     * um resultado diferente de as corrigir antes.
     */
    const val DEVELOP_FRAGMENT = """#version 310 es
precision highp float;
precision highp int;
precision highp usampler2D;

out vec4 fragColor;

uniform usampler2D uMosaico;
uniform mat3 uMatrizCor;        // câmara → saída, já em ordem de colunas
uniform vec3 uBalanco;          // ganhos de balanço, já com a exposição incluída
uniform ivec4 uCfa;             // cores em (0,0) (1,0) (0,1) (1,1); 0=R 1=G 2=B
uniform vec4 uPreto;            // nível de preto por posição do mosaico
uniform float uBranco;          // nível de branco
uniform vec3 uVinhetagem[32];   // queda medida por anel, do centro ao canto
uniform int uAneis;
uniform float uForca;           // força da correcção; 0 desliga
uniform float uRolloff;         // ponto branco do rolloff; 1.0 é linear puro
uniform ivec2 uTamanho;

// Índice da posição no mosaico: o mesmo `(y & 1) * 2 + (x & 1)` do Kotlin.
int posicaoCfa(ivec2 p) {
    return (p.y & 1) * 2 + (p.x & 1);
}

int corEm(ivec2 p) {
    int i = posicaoCfa(p);
    return i == 0 ? uCfa.x : (i == 1 ? uCfa.y : (i == 2 ? uCfa.z : uCfa.w));
}

// Reflexão nas bordas. Reflectir preserva a paridade do mosaico; limitar trocaria as cores nas
// duas primeiras colunas e linhas. Igual ao `Mosaic.at` do Kotlin.
ivec2 reflectir(ivec2 p) {
    if (p.x < 0) p.x = -p.x;
    if (p.y < 0) p.y = -p.y;
    if (p.x >= uTamanho.x) p.x = 2 * uTamanho.x - p.x - 2;
    if (p.y >= uTamanho.y) p.y = 2 * uTamanho.y - p.y - 2;
    return p;
}

// Interpola a queda e só depois inverte e eleva à força — a mesma ordem do `ShadingProfile.gain`
// seguido de `LensShading.applyStrength`. Inverter antes de interpolar daria outro número.
vec3 ganhoVinhetagem(float r) {
    if (uForca <= 0.0) return vec3(1.0);
    float pos = r * float(uAneis) - 0.5;
    int k = int(floor(pos));
    int a = clamp(k, 0, uAneis - 1);
    int b = clamp(k + 1, 0, uAneis - 1);
    float t = (k < 0 || k >= uAneis - 1) ? 0.0 : pos - float(k);
    vec3 queda = mix(uVinhetagem[a], uVinhetagem[b], t);
    vec3 ganho = 1.0 / max(queda, vec3(1e-4));
    return uForca >= 1.0 ? ganho : pow(ganho, vec3(uForca));
}

// Uma amostra do mosaico, já com níveis, vinhetagem e balanço aplicados.
float amostra(ivec2 p) {
    p = reflectir(p);
    int idx = posicaoCfa(p);
    float preto = idx == 0 ? uPreto.x : (idx == 1 ? uPreto.y : (idx == 2 ? uPreto.z : uPreto.w));

    float v = float(texelFetch(uMosaico, p, 0).r);
    v = (v - preto) / (uBranco - preto);

    // Raio normalizado pela meia-diagonal, como o perfil foi medido.
    vec2 centro = vec2(uTamanho) * 0.5;
    vec2 d = vec2(p) + 0.5 - centro;
    float r = length(d) / length(centro);

    int cor = corEm(p);
    vec3 ganho = ganhoVinhetagem(r);
    float g = cor == 0 ? ganho.r : (cor == 1 ? ganho.g : ganho.b);
    float wb = cor == 0 ? uBalanco.r : (cor == 1 ? uBalanco.g : uBalanco.b);

    return v * g * wb;
}

// --- Malvar-He-Cutler. Os quatro filtros somam 8 e dividem-se por 8, e é por isso que um campo
// --- uniforme atravessa intacto. Idênticos aos do `Demosaic.kt`.

float verdeEmRB(ivec2 p) {
    float acc = 4.0 * amostra(p)
        + 2.0 * (amostra(p + ivec2(-1, 0)) + amostra(p + ivec2(1, 0))
               + amostra(p + ivec2(0, -1)) + amostra(p + ivec2(0, 1)))
        - (amostra(p + ivec2(-2, 0)) + amostra(p + ivec2(2, 0))
         + amostra(p + ivec2(0, -2)) + amostra(p + ivec2(0, 2)));
    return acc / 8.0;
}

float rbEmVerdeMesmaLinha(ivec2 p) {
    float acc = 5.0 * amostra(p)
        + 4.0 * (amostra(p + ivec2(-1, 0)) + amostra(p + ivec2(1, 0)))
        - (amostra(p + ivec2(-2, 0)) + amostra(p + ivec2(2, 0)))
        - (amostra(p + ivec2(-1, -1)) + amostra(p + ivec2(1, -1))
         + amostra(p + ivec2(-1, 1)) + amostra(p + ivec2(1, 1)))
        + 0.5 * (amostra(p + ivec2(0, -2)) + amostra(p + ivec2(0, 2)));
    return acc / 8.0;
}

float rbEmVerdeOutraLinha(ivec2 p) {
    float acc = 5.0 * amostra(p)
        + 4.0 * (amostra(p + ivec2(0, -1)) + amostra(p + ivec2(0, 1)))
        - (amostra(p + ivec2(0, -2)) + amostra(p + ivec2(0, 2)))
        - (amostra(p + ivec2(-1, -1)) + amostra(p + ivec2(1, -1))
         + amostra(p + ivec2(-1, 1)) + amostra(p + ivec2(1, 1)))
        + 0.5 * (amostra(p + ivec2(-2, 0)) + amostra(p + ivec2(2, 0)));
    return acc / 8.0;
}

float rbNaDiagonal(ivec2 p) {
    float acc = 6.0 * amostra(p)
        + 2.0 * (amostra(p + ivec2(-1, -1)) + amostra(p + ivec2(1, -1))
               + amostra(p + ivec2(-1, 1)) + amostra(p + ivec2(1, 1)))
        - 1.5 * (amostra(p + ivec2(-2, 0)) + amostra(p + ivec2(2, 0))
               + amostra(p + ivec2(0, -2)) + amostra(p + ivec2(0, 2)));
    return acc / 8.0;
}

vec3 demosaico(ivec2 p) {
    int cor = corEm(p);
    float centro = amostra(p);
    if (cor == 1) {
        int corDaLinha = corEm(p + ivec2(1, 0));
        float mesma = rbEmVerdeMesmaLinha(p);
        float outra = rbEmVerdeOutraLinha(p);
        return corDaLinha == 0 ? vec3(mesma, centro, outra) : vec3(outra, centro, mesma);
    }
    if (cor == 0) {
        return vec3(centro, verdeEmRB(p), rbNaDiagonal(p));
    }
    return vec3(rbNaDiagonal(p), verdeEmRB(p), centro);
}

// Reinhard estendido. Com ponto branco 1.0 é a identidade — é assim que se obtém linear puro.
vec3 rolloff(vec3 x) {
    if (uRolloff <= 1.0) return x;
    float w2 = uRolloff * uRolloff;
    return x * (1.0 + x / w2) / (1.0 + x);
}

// Codificação sRGB, igual à do `ColorScience.srgbEncode`.
vec3 codificar(vec3 x) {
    vec3 c = clamp(x, 0.0, 1.0);
    vec3 baixo = c * 12.92;
    vec3 alto = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
    return mix(alto, baixo, step(c, vec3(0.0031308)));
}

void main() {
    // `gl_FragCoord` dá o pixel exacto, sem interpolação nem arredondamento. Não se inverte o Y:
    // a linha 0 do alvo fica a ser a linha 0 da imagem, e o `glReadPixels` — que devolve de baixo
    // para cima nas coordenadas do GL — entrega-as já na ordem certa do ficheiro.
    ivec2 p = ivec2(gl_FragCoord.xy);
    p = clamp(p, ivec2(0), uTamanho - 1);

    vec3 cam = demosaico(p);
    vec3 saida = uMatrizCor * cam;
    fragColor = vec4(codificar(rolloff(saida)), 1.0);
}
"""

    /**
     * Caminho do visor: *binning* 2×2 em vez de Malvar-He-Cutler.
     *
     * Quatro vezes mais barato e não inventa nada — é média de amostras verdadeiras. É a **única**
     * diferença permitida entre o que se vê e o que se grava, e existe apenas por causa da taxa de
     * frames (§6.4). A cor tem de ser idêntica, e há um teste no `RawPipelineTest` que o exige.
     */
    const val PREVIEW_FRAGMENT = """#version 310 es
precision highp float;
precision highp int;
precision highp usampler2D;

out vec4 fragColor;

uniform usampler2D uMosaico;
uniform mat3 uMatrizCor;
uniform vec3 uBalanco;
uniform ivec4 uCfa;
uniform vec4 uPreto;
uniform float uBranco;
uniform vec3 uVinhetagem[32];
uniform int uAneis;
uniform float uForca;
uniform float uRolloff;
uniform ivec2 uTamanho;

// Interpola a queda e só depois inverte e eleva à força — a mesma ordem do `ShadingProfile.gain`
// seguido de `LensShading.applyStrength`. Inverter antes de interpolar daria outro número.
vec3 ganhoVinhetagem(float r) {
    if (uForca <= 0.0) return vec3(1.0);
    float pos = r * float(uAneis) - 0.5;
    int k = int(floor(pos));
    int a = clamp(k, 0, uAneis - 1);
    int b = clamp(k + 1, 0, uAneis - 1);
    float t = (k < 0 || k >= uAneis - 1) ? 0.0 : pos - float(k);
    vec3 queda = mix(uVinhetagem[a], uVinhetagem[b], t);
    vec3 ganho = 1.0 / max(queda, vec3(1e-4));
    return uForca >= 1.0 ? ganho : pow(ganho, vec3(uForca));
}

vec3 codificar(vec3 x) {
    vec3 c = clamp(x, 0.0, 1.0);
    vec3 baixo = c * 12.92;
    vec3 alto = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
    return mix(alto, baixo, step(c, vec3(0.0031308)));
}

vec3 rolloff(vec3 x) {
    if (uRolloff <= 1.0) return x;
    float w2 = uRolloff * uRolloff;
    return x * (1.0 + x / w2) / (1.0 + x);
}

void main() {
    ivec2 base = ivec2(gl_FragCoord.xy) * 2;

    vec2 centro = vec2(uTamanho) * 0.5;
    float r = length(vec2(base) + 1.0 - centro) / length(centro);
    vec3 ganho = ganhoVinhetagem(r);

    vec3 soma = vec3(0.0);
    float verdes = 0.0;
    // O alfa leva o corte **do sensor**, antes de ganhos e de matriz. É o corte que interessa: o do
    // render recupera-se baixando a exposição na revelação; o do sensor não existe, não há lá nada
    // para recuperar. O passe de apresentação lê isto para desenhar as zebras.
    float cortado = 0.0;
    for (int i = 0; i < 4; ++i) {
        ivec2 p = base + ivec2(i & 1, i >> 1);
        float preto = i == 0 ? uPreto.x : (i == 1 ? uPreto.y : (i == 2 ? uPreto.z : uPreto.w));
        float v = (float(texelFetch(uMosaico, p, 0).r) - preto) / (uBranco - preto);
        if (v > 0.99) cortado = 1.0;
        int cor = i == 0 ? uCfa.x : (i == 1 ? uCfa.y : (i == 2 ? uCfa.z : uCfa.w));
        if (cor == 0) {
            soma.r = v * ganho.r * uBalanco.r;
        } else if (cor == 2) {
            soma.b = v * ganho.b * uBalanco.b;
        } else {
            soma.g += v * ganho.g * uBalanco.g;
            verdes += 1.0;
        }
    }
    if (verdes > 0.0) soma.g /= verdes;

    fragColor = vec4(codificar(rolloff(uMatrizCor * soma)), cortado);
}
"""

    /** Os uniformes da apresentação. Conjunto próprio, distinto do da revelação. */
    val PRESENT_UNIFORM_NAMES =
        listOf("uImagem", "uAlvo", "uEscala", "uDesvio", "uRotacao", "uPicos", "uZebras")

    /**
     * Leva a imagem revelada ao ecrã: roda, enquadra e mais nada.
     *
     * Passe separado de propósito. A revelação desenha para uma textura, com a matemática do ficheiro;
     * este passe só a mostra. Assim **mudar como se mostra não pode mudar o que se mostra** — e o custo
     * é um quadrilátero com textura, que ao lado de um carregamento de 23 MB não se mede.
     *
     * Duas convenções que interessam. Primeira: nesta textura a linha 0 é o **topo** da imagem, porque
     * foi assim que o passe de revelação a escreveu; converte-se o `gl_FragCoord`, cujo y cresce para
     * cima, em coordenadas de ecrã que crescem para baixo. Segunda: para rodar a **imagem** de 90° no
     * sentido dos ponteiros, aplica-se aos pontos de destino a rotação **inversa** — daí o
     * `vec2(d.y, 1.0 - d.x)` e não o contrário.
     *
     * Fora do enquadramento pinta-se preto: são as barras que garantem que se vê o quadro todo.
     */
    const val PRESENT_FRAGMENT = """#version 310 es
precision highp float;

out vec4 fragColor;

uniform sampler2D uImagem;
uniform ivec2 uAlvo;
uniform vec2 uEscala;
uniform vec2 uDesvio;
uniform int uRotacao;
uniform float uPicos;   // limiar do peaking; 0 ou menos desliga
uniform int uZebras;    // 0 desliga; 1 risca o que o sensor cortou

// Laplaciano da luminância: mede **nitidez**, não contraste.
//
// Uma aresta desfocada tem gradiente mas segunda derivada pequena; uma aresta nítida tem-na grande. É
// por isso que se usa esta e não o gradiente — o gradiente acendia em tudo o que tivesse contraste,
// focado ou não, e um peaking que acende em tudo não diz nada.
float nitidez(vec2 s) {
    vec2 t = 1.0 / vec2(textureSize(uImagem, 0));
    const vec3 pesos = vec3(0.2126, 0.7152, 0.0722);
    float c = dot(texture(uImagem, s).rgb, pesos);
    float e = dot(texture(uImagem, s - vec2(t.x, 0.0)).rgb, pesos);
    float d = dot(texture(uImagem, s + vec2(t.x, 0.0)).rgb, pesos);
    float a = dot(texture(uImagem, s - vec2(0.0, t.y)).rgb, pesos);
    float b = dot(texture(uImagem, s + vec2(0.0, t.y)).rgb, pesos);
    return abs(4.0 * c - e - d - a - b);
}

void main() {
    // Ecrã normalizado com a origem em cima à esquerda.
    vec2 tela = vec2(gl_FragCoord.x / float(uAlvo.x), 1.0 - gl_FragCoord.y / float(uAlvo.y));

    // Do ecrã para a imagem apresentada. Fora de 0..1 são as barras.
    vec2 d = (tela - uDesvio) / uEscala;
    if (d.x < 0.0 || d.x > 1.0 || d.y < 0.0 || d.y > 1.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // Rotação inversa: onde é que este ponto de destino estava na imagem.
    vec2 s = d;
    if (uRotacao == 90) {
        s = vec2(d.y, 1.0 - d.x);
    } else if (uRotacao == 180) {
        s = vec2(1.0 - d.x, 1.0 - d.y);
    } else if (uRotacao == 270) {
        s = vec2(1.0 - d.y, d.x);
    }

    vec4 amostra = texture(uImagem, s);
    vec3 cor = amostra.rgb;

    // Zebras sobre o corte do sensor, lido do alfa. Riscas na diagonal e não uma cor chapada: sobre
    // uma zona já branca, uma marca branca não se via, e sobre uma zona colorida uma marca colorida
    // confundir-se-ia com a fotografia.
    if (uZebras != 0 && amostra.a > 0.5 &&
        mod(gl_FragCoord.x + gl_FragCoord.y, 16.0) < 8.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // O peaking vive **aqui**, no passe de apresentação, e não na revelação.
    //
    // Não é arrumação: é o que torna impossível a ajuda de visor contaminar o ficheiro. A revelação
    // desenha para a textura e não sabe que isto existe; se um dia alguém quiser gravar o que o visor
    // mostra, grava a imagem, não as marcas.
    // O piso de luminância não é cosmético. Sem ele o Laplaciano acende no **ruído** das sombras, e
    // viu-se: com a cena bem focada e com ela desfocada, os salpicos vermelhos no primeiro plano
    // escuro eram os mesmos. Um realce que acende onde não há sinal não está a indicar foco — está a
    // indicar ISO, e é ruído a fingir de detalhe.
    const vec3 pesosLum = vec3(0.2126, 0.7152, 0.0722);
    if (uPicos > 0.0 && dot(cor, pesosLum) > 0.12 && nitidez(s) > uPicos) {
        // Ciano, e não laranja como na primeira versão. O ciano quase não ocorre em cenas naturais —
        // num pôr do sol, um realce laranja desaparece dentro da própria cor da cena.
        cor = vec3(0.0, 1.0, 0.95);
    }

    fragColor = vec4(cor, 1.0);
}
"""
}
