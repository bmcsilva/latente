#!/usr/bin/env python3
"""
Vinhetagem a partir de duas chapas com o telefone rodado 180°.

O problema de medir vinhetagem à mão é que a iluminação nunca é perfeitamente uniforme, e um
gradiente de luz é indistinguível de vinhetagem numa só fotografia. Mas os dois têm uma diferença
fundamental: **a vinhetagem está presa ao sensor, o gradiente está preso à cena.**

Rodando o telefone 180° sobre o eixo da objectiva:

    chapa A:  I_A(x,y) = V(x,y) · S(x,y)
    chapa B:  I_B(x,y) = V(x,y) · S(−x,−y)

A **média geométrica** das duas, na mesma posição do sensor, dá:

    √(I_A · I_B) = V(x,y) · √(S(x,y)·S(−x,−y)) ≈ V(x,y) · S₀

Um gradiente linear S₀(1 + g·x) cancela-se até segunda ordem, porque
(1+gx)(1−gx) = 1 − g²x². Sobra a vinhetagem multiplicada por uma constante.

Não é preciso rodar as imagens: basta multiplicar as duas na mesma posição do sensor.

O que isto **não** corrige é uma queda radial da própria montagem — um difusor colado à lente não
alimenta os ângulos extremos do campo, e essa falha é centro-simétrica como a vinhetagem. Para isso
é preciso uma fonte extensa que preencha o campo todo: um ecrã branco ou céu encoberto.

Uso:  python3 tools/flatpair.py chapa_0graus.dng chapa_180graus.dng
"""

import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import develop as dev  # noqa: E402


def blocks(raw, patch=200):
    """Médias em nove posições: centro, quatro bordas, quatro cantos."""
    w, h = raw.width, raw.height

    def block(x0, y0):
        x0 = max(0, min(x0, w - patch - 2))
        y0 = max(0, min(y0, h - patch - 2))
        acc = 0.0
        cnt = 0
        for y in range(y0, y0 + patch, 2):
            r0, r1 = raw.rows[y], raw.rows[y + 1]
            for x in range(x0, x0 + patch, 2):
                acc += r0[x] + r0[x + 1] + r1[x] + r1[x + 1]
                cnt += 4
        return acc / cnt if cnt else 0.0

    cx, cy = w // 2 - patch // 2, h // 2 - patch // 2
    return {
        "centro": block(cx, cy),
        "borda esq": block(0, cy),
        "borda dir": block(w - patch, cy),
        "borda sup": block(cx, 0),
        "borda inf": block(cx, h - patch),
        "canto SE": block(0, 0),
        "canto SD": block(w - patch, 0),
        "canto IE": block(0, h - patch),
        "canto ID": block(w - patch, h - patch),
    }


def angle(raw, nome, patch=200):
    """Ângulo de campo aproximado de cada posição, do tamanho do sensor e da focal."""
    sw, sh, f = raw.sensorWidth, raw.sensorHeight, raw.focal
    if not f:
        return 0.0
    dx = {"esq": sw / 2, "dir": sw / 2}.get(nome[-3:], 0.0)
    if nome == "centro":
        return 0.0
    if nome.startswith("borda"):
        if nome.endswith(("esq", "dir")):
            r = sw / 2
        else:
            r = sh / 2
    else:
        r = math.hypot(sw / 2, sh / 2)
    return math.degrees(math.atan(r / f))


def main(a_path, b_path):
    a = dev.Raw(a_path)
    b = dev.Raw(b_path)
    print("  0°    %s" % os.path.basename(a_path))
    print("  180°  %s" % os.path.basename(b_path))

    ba = blocks(a)
    bb = blocks(b)

    ca = ba["centro"]
    cb = bb["centro"]
    cg = math.sqrt(ca * cb)
    if cg <= 0:
        print("  centro sem sinal")
        return

    print()
    print("  %-11s %6s %9s %9s %11s %9s %8s" % (
        "posição", "θ°", "0°", "180°", "média geom.", "cos⁴θ", "razão"))
    razoes = []
    for nome in ("borda esq", "borda dir", "borda sup", "borda inf",
                 "canto SE", "canto SD", "canto IE", "canto ID"):
        va = ba[nome] / ca
        vb = bb[nome] / cb
        g = math.sqrt(max(va * vb, 0.0))
        th = angle(a, nome)
        c4 = math.cos(math.radians(th)) ** 4
        razoes.append(g / c4 if c4 else 0.0)
        print("  %-11s %6.1f %9.3f %9.3f %11.3f %9.3f %8.3f" % (nome, th, va, vb, g, c4, g / c4))

    cantos = [math.sqrt(max(ba[n] / ca * bb[n] / cb, 0.0))
              for n in ("canto SE", "canto SD", "canto IE", "canto ID")]
    media = sum(cantos) / 4
    espalha = (max(cantos) - min(cantos)) / max(media, 1e-9)

    print()
    print("  cantos/centro após cancelar o gradiente   %.3f" % media)
    print("  dispersão entre os quatro cantos          %.0f%%" % (espalha * 100))
    m = sum(razoes) / len(razoes)
    print("  razão média medido/cos⁴θ                  %.3f" % m)

    print()
    if espalha > 0.12:
        print("  → ainda há %.0f%% de dispersão entre cantos. O gradiente não era linear," % (espalha * 100))
        print("    ou as duas chapas não foram tiradas na mesma posição.")
    elif media > 0.90:
        print("  → cantos a %.0f%% do centro: sem vinhetagem apreciável. O HAL corrige-a, como declara."
              % (media * 100))
    else:
        print("  → VINHETAGEM REAL: a objectiva perde %.0f%% de luz nos cantos." % ((1 - media) * 100))
        if m < 0.85:
            print("    E é mais acentuada que a lei natural cos⁴θ (razão %.2f), portanto há" % m)
            print("    vinhetagem óptica ou mecânica a somar-se — ou a fonte não preenche o campo.")
        else:
            print("    Acompanha a lei natural cos⁴θ (razão %.2f): é a queda geométrica." % m)
        print("    Em qualquer caso, o RAW não vem corrigido e o revelador tem de o fazer.")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1], sys.argv[2])
