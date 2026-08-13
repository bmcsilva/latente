#!/usr/bin/env python3
"""
Análise de uma chapa plana: uma superfície uniforme a preencher o quadro.

Três coisas que só se conseguem medir assim:

1. **Vinhetagem real** — razão entre cantos e centro, por canal do mosaico. Fecha a experiência 2
   da F1, que até aqui só tinha conseguido comparar modos entre si.
2. **Verificação cruzada do `WhiteBalance.kt`** — recalcula-se aqui, em Python, o ponto neutro para
   a temperatura escolhida e compara-se com o `AsShotNeutral` que a aplicação escreveu. Se baterem,
   as duas implementações independentes concordam.
3. **A temperatura verdadeira da luz** — procura-se o Kelvin que faz a superfície renderizar
   neutra. É o teste do cartão de cinza da §9.

Uso:  python3 tools/flatfield.py chapa.dng
"""

import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import dngcheck  # noqa: E402
import develop as dev  # noqa: E402


def chromaticity(kelvin):
    """Locus de Planck abaixo de 4000 K, locus da luz do dia acima. Igual ao WhiteBalance.kt."""
    t = min(max(float(kelvin), 1667.0), 25000.0)
    if t < 4000.0:
        x = -0.2661239e9 / t ** 3 - 0.2343589e6 / t ** 2 + 0.8776956e3 / t + 0.179910
    elif t <= 7000.0:
        x = 0.244063 + 0.09911e3 / t + 2.9678e6 / t ** 2 - 4.6070e9 / t ** 3
    else:
        x = 0.237040 + 0.24748e3 / t + 1.9018e6 / t ** 2 - 2.0064e9 / t ** 3
    if t < 2222.0:
        y = -0.9549476 * x ** 3 - 1.37418593 * x ** 2 + 2.09137015 * x - 0.16748867
    elif t < 4000.0:
        y = -1.1063814 * x ** 3 - 1.34811020 * x ** 2 + 2.18555832 * x - 0.20219683
    else:
        y = -3.000 * x ** 2 + 2.870 * x - 0.275
    return x, y


def neutral_for(raw, kelvin):
    """Ponto neutro em espaço da câmara — o mesmo caminho do WhiteBalance.kt no telefone."""
    cm = raw.colour_matrix(kelvin)
    if not cm:
        return None
    x, y = chromaticity(kelvin)
    w = (x / y, 1.0, (1.0 - x - y) / y)
    cam = dev.mat_vec(cm, w)
    m = max(cam)
    if m <= 0:
        return None
    return [max(v / m, 1e-4) for v in cam]


def regions(raw, patch=240):
    """Médias por canal do mosaico no centro e nos quatro cantos."""
    w, h = raw.width, raw.height

    def block(x0, y0):
        acc = [0.0, 0.0, 0.0]
        cnt = [0, 0, 0]
        for y in range(y0, min(y0 + patch, h - 1), 2):
            r0, r1 = raw.rows[y], raw.rows[y + 1]
            for x in range(x0, min(x0 + patch, w - 1), 2):
                pix = (r0[x], r0[x + 1], r1[x], r1[x + 1])
                for pos in range(4):
                    c = raw.cfa[pos]
                    if c > 2:
                        continue
                    acc[c] += pix[pos]
                    cnt[c] += 1
        return [acc[c] / cnt[c] if cnt[c] else 0.0 for c in range(3)]

    centre = block(w // 2 - patch // 2, h // 2 - patch // 2)
    corners = [block(0, 0), block(w - patch, 0), block(0, h - patch), block(w - patch, h - patch)]
    return centre, corners


def radial(raw, patch=160):
    """
    Perfil radial contra a lei cos⁴θ, a vinhetagem natural de qualquer objectiva.

    O ângulo de campo de cada posição sai do tamanho do sensor e da distância focal, ambos lidos
    do ficheiro. Compara-se em **pares simétricos**: a média de cada par cancela um gradiente
    linear de iluminação, e a diferença dentro do par mede quanto gradiente sobrou. Sem isso o
    desvio padrão global disfarça um gradiente sistemático — foi o que me aconteceu à primeira.
    """
    w, h = raw.width, raw.height
    sw, sh = raw.sensorWidth, raw.sensorHeight
    f = raw.focal
    if not (sw and sh and f):
        return None

    def block(x0, y0):
        acc = 0.0
        cnt = 0
        for y in range(max(0, y0), min(y0 + patch, h - 1), 2):
            r0, r1 = raw.rows[y], raw.rows[y + 1]
            for x in range(max(0, x0), min(x0 + patch, w - 1), 2):
                acc += r0[x] + r0[x + 1] + r1[x] + r1[x + 1]
                cnt += 4
        return acc / cnt if cnt else 0.0

    centre = block(w // 2 - patch // 2, h // 2 - patch // 2)
    if centre <= 0:
        return None

    pares = (
        ("bordas E/D", (0, h // 2 - patch // 2), (w - patch, h // 2 - patch // 2), sw / 2, 0.0),
        ("bordas S/I", (w // 2 - patch // 2, 0), (w // 2 - patch // 2, h - patch), 0.0, sh / 2),
        ("meia diagonal", (w // 4 - patch // 2, h // 4 - patch // 2),
         (3 * w // 4 - patch // 2, 3 * h // 4 - patch // 2), sw / 4, sh / 4),
        ("cantos", (0, 0), (w - patch, h - patch), sw / 2, sh / 2),
    )

    print()
    print("  PERFIL RADIAL contra cos⁴θ (pares simétricos)")
    print("    %-16s %6s %8s %8s %10s %10s" % ("par", "θ°", "média", "cos⁴θ", "razão", "gradiente"))
    razoes = []
    grad = []
    for nome, a, b, dx, dy in pares:
        va = block(*a) / centre
        vb = block(*b) / centre
        media = (va + vb) / 2
        th = math.degrees(math.atan(math.hypot(dx, dy) / f))
        c4 = math.cos(math.radians(th)) ** 4
        g = abs(va - vb) / max(va, vb, 1e-9)
        razoes.append(media / c4)
        grad.append(g)
        print("    %-16s %6.1f %8.3f %8.3f %10.3f %9.0f%%" % (nome, th, media, c4, media / c4, g * 100))

    m = sum(razoes) / len(razoes)
    gmax = max(grad)
    espalha = max(razoes) - min(razoes)
    print("    razão média medido/cos⁴θ      %.3f" % m)
    print("    gradiente residual máximo     %.0f%%" % (gmax * 100))

    if gmax > 0.12:
        print("    → o gradiente residual de %.0f%% é grande demais para se separar a óptica da"
              % (gmax * 100))
        print("      iluminação. A magnitude é fiável; a atribuição não é.")
    elif espalha < 0.15:
        print("    → o perfil acompanha cos⁴θ a %.0f%%: é vinhetagem natural da objectiva."
              % (espalha * 100))
    else:
        print("    → o perfil afasta-se de cos⁴θ: há vinhetagem óptica ou mecânica a somar-se.")
    return m, gmax


def uniformity(raw, step=8):
    """
    Fracção de píxeis próxima do valor central, e fracção saturada.

    Uma chapa plana verdadeira tem distribuição estreita e unimodal. Um ecrã que não preenche o
    quadro dá uma distribuição **bimodal** — claro no meio, escuro em volta — e qualquer razão
    cantos/centro calculada sobre isso é sem sentido.
    """
    w, h = raw.width, raw.height
    centro = raw.rows[h // 2][w // 2]
    perto = 0
    saturado = 0
    n = 0
    limite = raw.white * 0.98
    for y in range(0, h - 1, step):
        r = raw.rows[y]
        for x in range(0, w, step):
            v = r[x]
            n += 1
            if v >= limite:
                saturado += 1
            if centro > 0 and abs(v - centro) <= 0.25 * centro:
                perto += 1
    return (perto / n if n else 0.0), (saturado / n if n else 0.0)


def analyse(path):
    raw = dev.Raw(path)
    print("=" * 78)
    print(os.path.basename(path))
    print("=" * 78)
    print("  mosaico %dx%d · CFA %s · branco %d" % (
        raw.width, raw.height,
        "".join(dngcheck.CFA_COLOR.get(c, "?") for c in raw.cfa), raw.white))

    # --- 0. a chapa é chapa? ---------------------------------------------------------------
    perto, saturado = uniformity(raw)
    print()
    print("  A CHAPA É CHAPA?")
    print("    píxeis a menos de 25%% do centro   %.0f%%" % (perto * 100))
    print("    píxeis saturados                  %.1f%%" % (saturado * 100))
    valida = True
    if saturado > 0.02:
        print("    → INVÁLIDA: %.0f%% dos píxeis estão no tecto. Com o sinal cortado, qualquer"
              % (saturado * 100))
        print("      razão cantos/centro é sem sentido. É preciso exposição mais curta.")
        valida = False
    if perto < 0.60:
        print("    → INVÁLIDA: só %.0f%% dos píxeis estão perto do centro. A distribuição é"
              % (perto * 100))
        print("      bimodal, ou tem uma queda forte: a fonte não é uniforme no campo todo.")
        print("      ATENÇÃO: um ecrã LCD **não serve** de chapa plana. O brilho de um LCD")
        print("      depende do ângulo de visão, e num campo de 74° os cantos vêem o ecrã a")
        print("      ~40° fora do eixo, onde é muito mais escuro. Mede-se a directividade do")
        print("      monitor, não a objectiva.")
        print("      O que serve: pano ou papel branco encostado à lente, apontado a CÉU aberto.")
        print("      O céu ilumina o difusor de todo o hemisfério, e só assim ele se comporta")
        print("      como emissor lambertiano e alimenta todos os ângulos do campo.")
        valida = False
    if valida:
        print("    → distribuição estreita e sem corte: serve como chapa plana")

    # --- 1. vinhetagem ---------------------------------------------------------------------
    centre, corners = regions(raw)
    print()
    print("  VINHETAGEM (chapa plana, blocos de 240 px)")
    print("    centro R G B           %.1f  %.1f  %.1f" % tuple(centre))
    nomes = ("sup.esq", "sup.dir", "inf.esq", "inf.dir")
    for nome, c in zip(nomes, corners):
        ratios = [c[i] / centre[i] if centre[i] else 0.0 for i in range(3)]
        print("    %-22s %.3f  %.3f  %.3f" % (nome, ratios[0], ratios[1], ratios[2]))
    media = sum(sum(c) / 3 for c in corners) / 4 / (sum(centre) / 3)
    print("    média cantos/centro    %.3f" % media)

    # Guarda de validade: a vinhetagem de uma lente é radialmente simétrica. Se os cantos da
    # esquerda diferirem dos da direita, o que se está a medir é a iluminação, não a óptica.
    esq = (sum(corners[0]) + sum(corners[2])) / 6.0
    dirr = (sum(corners[1]) + sum(corners[3])) / 6.0
    sup = (sum(corners[0]) + sum(corners[1])) / 6.0
    inf = (sum(corners[2]) + sum(corners[3])) / 6.0
    assim_h = abs(esq - dirr) / max(esq, dirr, 1e-9)
    assim_v = abs(sup - inf) / max(sup, inf, 1e-9)
    assim = max(assim_h, assim_v)
    print("    assimetria E/D · S/I   %.0f%% · %.0f%%" % (assim_h * 100, assim_v * 100))

    if not valida:
        print("    → medição não interpretada: a chapa não passou a validação acima")
    elif assim > 0.10:
        print("    → MEDIÇÃO INVÁLIDA: assimetria de %.0f%%. A vinhetagem de uma lente é" % (assim * 100))
        print("      radialmente simétrica, logo isto é iluminação desigual e não a óptica.")
        print("      Para medir a sério: difusor translúcido colado à lente, apontado a céu")
        print("      encoberto — é o método das chapas planas da astrofotografia.")
    elif media > 0.90:
        print("    → vinhetagem residual: o HAL já a corrigiu, como declara")
    else:
        print("    → queda simétrica de %.0f%% nos cantos: vinhetagem real da objectiva"
              % ((1 - media) * 100))

    radial(raw)

    # --- 2. verificação cruzada do WhiteBalance.kt -----------------------------------------
    print()
    print("  BALANÇO DE BRANCOS: Python contra Kotlin")
    print("    AsShotNeutral do ficheiro   %s" % [round(v, 4) for v in raw.neutral])
    calc = neutral_for(raw, 5500)
    if calc:
        print("    recalculado aqui a 5500 K   %s" % [round(v, 4) for v in calc])
        d = max(abs(calc[i] - raw.neutral[i]) / raw.neutral[i] for i in range(3)) * 100
        print("    diferença máxima            %.2f%%" % d)
        print("    → %s" % ("as duas implementações concordam" if d < 2.0
                            else "DIVERGEM — rever o WhiteBalance.kt"))

    # --- 3. a temperatura verdadeira da luz ------------------------------------------------
    print()
    print("  TEMPERATURA DA LUZ (o cartão de cinza da §9)")
    melhor = None
    for k in range(2000, 9001, 25):
        n = neutral_for(raw, k)
        if not n:
            continue
        # Se a superfície é neutra, o sinal da câmara é proporcional ao ponto neutro daquela luz.
        escala = centre[1] / n[1]
        erro = max(abs(centre[i] / escala - n[i]) / n[i] for i in range(3))
        if melhor is None or erro < melhor[1]:
            melhor = (k, erro)
    if melhor:
        k, erro = melhor
        n = neutral_for(raw, k)
        print("    a superfície fica neutra a  %d K" % k)
        print("    ponto neutro dessa luz      %s" % [round(v, 4) for v in n])
        print("    erro residual               %.2f%%" % (erro * 100))
        print("    escolhido no disparo        5500 K")
        print("    → desvio de %d K entre o que se pediu e a luz real" % (5500 - k))
    return melhor


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    for p in sys.argv[1:]:
        analyse(p)
        print()
