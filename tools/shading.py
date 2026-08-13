#!/usr/bin/env python3
"""
Extrai o perfil de vinhetagem de uma chapa plana, e valida-a como chapa.

O HAL deste telefone declara `SENSOR_INFO_LENS_SHADING_APPLIED = true` e devolve um mapa de
correcção que é **exactamente 1,0000** em todas as posições — um *stub*. Mas a medição mostra que o
RAW **não está corrigido**. Logo a correcção tem de ser nossa, e o perfil tem de ser medido.

**Como se valida uma chapa.** Não pela largura da distribuição: se a objectiva vinhetar a sério,
mesmo uma chapa perfeita tem distribuição larga — foi o erro da primeira versão desta ferramenta,
que rejeitava medições boas. O critério certo é:

1. **sem saturação** — com o sinal cortado qualquer razão é sem sentido;
2. **simetria radial** entre pares opostos: a vinhetagem de uma lente é simétrica, um gradiente de
   iluminação não é. É este o teste que separa a óptica da montagem;
3. **queda monótona** do centro para a periferia.

**Como se faz uma chapa boa.** Difusor encostado à lente (papel, pano branco) **mais** uma fonte
extensa atrás: um ecrã branco iluminado, ou o céu. Nenhum dos dois chega sozinho — um ecrã sem
difusor mede a directividade do LCD, e um difusor com lâmpada atrás não alimenta os ângulos
extremos do campo.

Uso:  python3 tools/shading.py chapa.dng [mais.dng ...] [--json perfil.json]
"""

import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import develop as dev  # noqa: E402

ANEIS = 16


def block(raw, x0, y0, p=120):
    """Média por canal do CFA num bloco."""
    w, h = raw.width, raw.height
    x0 = max(0, min(x0, w - p - 2))
    y0 = max(0, min(y0, h - p - 2))
    acc = [0.0, 0.0, 0.0]
    cnt = [0, 0, 0]
    for y in range(y0, y0 + p, 2):
        r0, r1 = raw.rows[y], raw.rows[y + 1]
        for x in range(x0, x0 + p, 2):
            pix = (r0[x], r0[x + 1], r1[x], r1[x + 1])
            for pos in range(4):
                c = raw.cfa[pos]
                if c > 2:
                    continue
                acc[c] += pix[pos]
                cnt[c] += 1
    return [acc[c] / cnt[c] if cnt[c] else 0.0 for c in range(3)]


def validate(raw):
    """Devolve (válida, mensagens, assimetria máxima)."""
    w, h = raw.width, raw.height
    msgs = []
    ok = True

    # 1. saturação
    limite = raw.white * 0.98
    sat = 0
    n = 0
    for y in range(0, h - 1, 8):
        r = raw.rows[y]
        for x in range(0, w, 8):
            n += 1
            if r[x] >= limite:
                sat += 1
    frac = sat / n if n else 0.0
    msgs.append("saturação                %.2f%%" % (frac * 100))
    if frac > 0.02:
        msgs.append("  → INVÁLIDA: sinal cortado; encurtar a exposição")
        ok = False

    # 2. simetria radial — o teste que separa a óptica da iluminação
    p = 120
    centro = sum(block(raw, w // 2 - p // 2, h // 2 - p // 2)) / 3
    if centro <= 0:
        return False, msgs + ["  → INVÁLIDA: centro sem sinal"], 1.0
    pares = (
        ("esquerda/direita", (0, h // 2 - p // 2), (w - p, h // 2 - p // 2)),
        ("cima/baixo", (w // 2 - p // 2, 0), (w // 2 - p // 2, h - p)),
        ("diagonal SE/ID", (0, 0), (w - p, h - p)),
        ("diagonal SD/IE", (w - p, 0), (0, h - p)),
    )
    pior = 0.0
    for nome, a, b in pares:
        va = sum(block(raw, *a)) / 3 / centro
        vb = sum(block(raw, *b)) / 3 / centro
        d = abs(va - vb) / max(va, vb, 1e-9)
        pior = max(pior, d)
        msgs.append("simetria %-16s %.3f vs %.3f  →  %2.0f%%" % (nome, va, vb, d * 100))
    if pior > 0.08:
        msgs.append("  → INVÁLIDA: assimetria de %.0f%%. A vinhetagem de uma lente é radialmente"
                    % (pior * 100))
        msgs.append("    simétrica; isto é iluminação desigual.")
        ok = False

    return ok, msgs, pior


def profile(raw):
    """
    Perfil radial por canal, em anéis do centro ao canto.

    Devolve a lista de raios normalizados e, por canal, o ganho que corrige a queda.
    """
    w, h = raw.width, raw.height
    cx, cy = w / 2.0, h / 2.0
    rmax = math.hypot(cx, cy)

    soma = [[0.0] * ANEIS for _ in range(3)]
    cont = [[0] * ANEIS for _ in range(3)]

    # Amostragem esparsa: um quarteto a cada 6, que dá ~350 mil amostras.
    for y in range(0, h - 1, 12):
        r0, r1 = raw.rows[y], raw.rows[y + 1]
        for x in range(0, w - 1, 12):
            rr = math.hypot(x + 0.5 - cx, y + 0.5 - cy) / rmax
            k = min(ANEIS - 1, int(rr * ANEIS))
            pix = (r0[x], r0[x + 1], r1[x], r1[x + 1])
            for pos in range(4):
                c = raw.cfa[pos]
                if c > 2:
                    continue
                soma[c][k] += pix[pos]
                cont[c][k] += 1

    raios = [(k + 0.5) / ANEIS for k in range(ANEIS)]
    valores = []
    for c in range(3):
        v = [soma[c][k] / cont[c][k] if cont[c][k] else 0.0 for k in range(ANEIS)]
        base = v[0] if v[0] > 0 else 1.0
        valores.append([x / base for x in v])
    return raios, valores


def analyse(path):
    raw = dev.Raw(path)
    print("=" * 78)
    print(os.path.basename(path))
    print("=" * 78)

    ok, msgs, assim = validate(raw)
    for m in msgs:
        print("  " + m)
    if not ok:
        print()
        print("  chapa rejeitada — perfil não extraído")
        return None
    print("  → CHAPA VÁLIDA: simetria dentro de %.0f%%, sem corte" % (assim * 100))

    raios, val = profile(raw)
    monotona = all(val[1][k + 1] <= val[1][k] + 0.02 for k in range(ANEIS - 1))
    print()
    print("  PERFIL RADIAL (1,000 no centro)")
    print("    %-8s %8s %8s %8s %10s" % ("raio", "R", "G", "B", "ganho G"))
    for k in range(ANEIS):
        g = 1.0 / val[1][k] if val[1][k] > 0 else 0.0
        print("    %6.3f   %8.3f %8.3f %8.3f %10.2f" % (
            raios[k], val[0][k], val[1][k], val[2][k], g))

    canto = val[1][-1]
    print()
    print("    queda no canto          %.3f  (perde %.0f%% da luz)" % (canto, (1 - canto) * 100))
    print("    ganho máximo necessário %.2f×" % (1.0 / canto if canto > 0 else 0))
    print("    monótona                %s" % ("sim" if monotona else "NÃO — suspeito"))

    # Comparação com a lei natural, para saber quanto é geometria e quanto é a óptica.
    if raw.focal:
        rdiag = math.hypot(raw.sensorWidth / 2, raw.sensorHeight / 2)
        th = math.degrees(math.atan(rdiag / raw.focal))
        c4 = math.cos(math.radians(th)) ** 4
        print("    cos⁴θ no canto (θ=%.1f°)  %.3f" % (th, c4))
        print("    medido / cos⁴θ          %.3f" % (canto / c4))
        if th > 50.0:
            print("    → objectiva muito larga (θ>50°): a lei cos⁴θ pressupõe projecção")
            print("      rectilínea e deixa de valer. A razão acima não é interpretável.")
        elif canto / c4 < 0.85:
            print("    → mais acentuada que a lei natural: há vinhetagem óptica a somar-se")
        else:
            print("    → acompanha a lei natural: é a queda geométrica do campo")

    return {"ficheiro": os.path.basename(path), "raios": raios,
            "R": val[0], "G": val[1], "B": val[2]}


if __name__ == "__main__":
    args = [a for a in sys.argv[1:]]
    saida = None
    if "--json" in args:
        i = args.index("--json")
        saida = args[i + 1]
        del args[i:i + 2]
    if not args:
        print(__doc__)
        sys.exit(1)

    perfis = []
    for p in args:
        r = analyse(p)
        if r:
            perfis.append(r)
        print()

    if saida and perfis:
        # Média das chapas válidas: mais amostras, menos ruído.
        n = len(perfis)
        media = {
            "raios": perfis[0]["raios"],
            "R": [sum(p["R"][k] for p in perfis) / n for k in range(ANEIS)],
            "G": [sum(p["G"][k] for p in perfis) / n for k in range(ANEIS)],
            "B": [sum(p["B"][k] for p in perfis) / n for k in range(ANEIS)],
            "chapas": [p["ficheiro"] for p in perfis],
        }
        with open(saida, "w", encoding="utf-8") as fh:
            json.dump(media, fh, ensure_ascii=False, indent=2)
        print("perfil médio de %d chapas escrito em %s" % (n, saida))
