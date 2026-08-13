#!/usr/bin/env python3
"""
Compara a cor de duas revelações da mesma fotografia.

É o critério de aceitação da F2 (§9): o nosso revelador e o do darktable têm de concordar na cor.

Compara-se **cromaticidade**, não brilho. As duas revelações usam curvas de tom diferentes — o
darktable tem o seu fluxo cena-referido com exposição automática, o nosso é linear com um ombro —
portanto os valores absolutos não têm de bater. O que tem de bater é a cor: as razões R/G e B/G
em espaço linear.

Uso:  python3 tools/compare.py nosso.png darktable.jpg
"""

import sys


def srgb_decode(x):
    x = x / 255.0
    return x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4


def patches(path, grid):
    """Reduz a imagem à grelha pedida — o redimensionamento faz a média de cada bloco."""
    from PIL import Image
    im = Image.open(path).convert("RGB").resize(grid, Image.BOX)
    return list(im.getdata())


def compare(ours, theirs, cols=24, rows=18, floor=0.004, ceiling=0.95):
    a = patches(ours, (cols, rows))
    b = patches(theirs, (cols, rows))

    used = 0
    skipped_dark = 0
    skipped_clip = 0
    drg = []
    dbg = []
    # Subconjunto quase-neutro: é o "cartão de cinza" do critério de aceitação da §9.
    ndrg = []
    ndbg = []

    for pa, pb in zip(a, b):
        la = [srgb_decode(v) for v in pa]
        lb = [srgb_decode(v) for v in pb]
        # Sem verde não há razão; e em zonas muito escuras a cromaticidade é ruído.
        if min(la[1], lb[1]) < floor:
            skipped_dark += 1
            continue
        if max(max(la), max(lb)) > ceiling:
            skipped_clip += 1
            continue
        rg_a, bg_a = la[0] / la[1], la[2] / la[1]
        rg_b, bg_b = lb[0] / lb[1], lb[2] / lb[1]
        if rg_b <= 0 or bg_b <= 0:
            continue
        drg.append(abs(rg_a - rg_b) / rg_b)
        dbg.append(abs(bg_a - bg_b) / bg_b)
        if abs(rg_b - 1.0) < 0.20 and abs(bg_b - 1.0) < 0.20:
            ndrg.append(abs(rg_a - rg_b) / rg_b)
            ndbg.append(abs(bg_a - bg_b) / bg_b)
        used += 1

    print("  amostras           %d de %d  (escuras %d · cortadas %d)" % (
        used, cols * rows, skipped_dark, skipped_clip))
    if not used:
        print("  sem amostras utilizáveis — imagens demasiado escuras ou desalinhadas")
        return

    drg.sort()
    dbg.sort()
    med_r = drg[len(drg) // 2] * 100
    med_b = dbg[len(dbg) // 2] * 100
    p90_r = drg[int(len(drg) * 0.9)] * 100
    p90_b = dbg[int(len(dbg) * 0.9)] * 100

    print("  desvio em R/G      mediana %.1f%%  ·  p90 %.1f%%" % (med_r, p90_r))
    print("  desvio em B/G      mediana %.1f%%  ·  p90 %.1f%%" % (med_b, p90_b))
    if ndrg:
        ndrg.sort()
        ndbg.sort()
        print("  quase-neutros      %d amostras · R/G %.1f%% · B/G %.1f%%  (o cartão de cinza da §9)"
              % (len(ndrg), ndrg[len(ndrg) // 2] * 100, ndbg[len(ndbg) // 2] * 100))

    pior = max(med_r, med_b)
    print()
    if pior < 5.0:
        print("  VEREDICTO          CONCORDAM (%.1f%%) — a ciência da cor está validada" % pior)
    elif pior < 12.0:
        print("  VEREDICTO          próximos (%.1f%%) — plausível, mas vale a pena investigar" % pior)
    else:
        print("  VEREDICTO          DISCORDAM (%.1f%%) — há erro na matriz, no balanço ou no espaço"
              % pior)


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    print("  nosso     %s" % sys.argv[1])
    print("  darktable %s" % sys.argv[2])
    compare(sys.argv[1], sys.argv[2])
