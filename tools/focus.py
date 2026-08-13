#!/usr/bin/env python3
"""
Mede a nitidez de um DNG, directamente no mosaico.

Mede-se o **mosaico** e não uma revelação. Revelar em resolução reduzida — que é a única forma de o
fazer depressa em Python puro — destruiria precisamente as frequências altas que definem o foco, e a
medição passaria a dizer mais sobre o redimensionamento do que sobre a objectiva.

Mede-se o **verde**, e só num dos dois: os dois verdes de um quadrado do mosaico estão em posições
diferentes, e misturá-los introduziria um padrão em xadrez que o Laplaciano leria como detalhe.
Tomando um verde por quadrado fica uma grelha uniforme de metade da resolução.

A medida é a **variância do Laplaciano**, normalizada pelo quadrado da média. É o padrão para foco:
uma aresta desfocada tem gradiente mas segunda derivada pequena. A normalização torna-a comparável
entre fotografias com exposições diferentes — sem ela, mais luz parece mais foco.

Uso:
    python3 tools/focus.py a.dng b.dng ...          nitidez global de cada ficheiro
    python3 tools/focus.py --mapa a.dng b.dng ...   mapa por zonas, e onde cada zona fica mais nítida
"""

import sys
from array import array

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from dngcheck import Tiff          # noqa: E402

TILES_X = 6
TILES_Y = 8


def green_grid(path):
    """A grelha de verdes, um por quadrado do mosaico. Devolve (array, largura, altura)."""
    t = Tiff(open(path, "rb").read())
    tags, _ = t.ifd(t.first)
    w = tags[256][2][0] if isinstance(tags[256][2], list) else tags[256][2]
    h = tags[257][2][0] if isinstance(tags[257][2], list) else tags[257][2]
    offs = tags[273][2]
    counts = tags[279][2]
    if not isinstance(offs, list):
        offs, counts = [offs], [counts]

    cfa = tags.get(33422, (0, 0, [1, 2, 0, 1]))[2]
    # A posição do primeiro verde dentro do quadrado 2x2. Em GBRG é (0,0); em RGGB é (1,0).
    gx, gy = next(((i & 1, i >> 1) for i in range(4) if cfa[i] == 1), (0, 0))

    gw, gh = w // 2, h // 2
    out = array("H", bytes(2 * gw * gh))
    for j in range(gh):
        y = j * 2 + gy
        if y >= len(offs):
            break
        buf = t.data[offs[y]:offs[y] + counts[y]]
        row = array("H")
        row.frombytes(buf[:len(buf) - (len(buf) % 2)])
        if sys.byteorder != ("little" if t.end == "<" else "big"):
            row.byteswap()
        base = j * gw
        for i in range(gw):
            x = i * 2 + gx
            if x < len(row):
                out[base + i] = row[x]
    return out, gw, gh


def sharpness(g, gw, gh, x0, y0, x1, y1):
    """Variância do Laplaciano numa zona, normalizada pela média ao quadrado."""
    n = 0
    soma = 0.0
    soma2 = 0.0
    media = 0.0
    for y in range(max(1, y0), min(gh - 1, y1)):
        base = y * gw
        for x in range(max(1, x0), min(gw - 1, x1)):
            c = g[base + x]
            lap = 4 * c - g[base + x - 1] - g[base + x + 1] - g[base - gw + x] - g[base + gw + x]
            soma += lap
            soma2 += float(lap) * lap
            media += c
            n += 1
    if n < 2:
        return 0.0
    media /= n
    if media <= 0:
        return 0.0
    var = soma2 / n - (soma / n) ** 2
    return var / (media * media)


def main(argv):
    mapa = "--mapa" in argv
    ficheiros = [a for a in argv if not a.startswith("--")]
    if not ficheiros:
        print(__doc__)
        return 1

    dados = []
    for p in ficheiros:
        g, gw, gh = green_grid(p)
        global_ = sharpness(g, gw, gh, 0, 0, gw, gh)
        zonas = []
        if mapa:
            for ty in range(TILES_Y):
                linha = []
                for tx in range(TILES_X):
                    linha.append(sharpness(
                        g, gw, gh,
                        tx * gw // TILES_X, ty * gh // TILES_Y,
                        (tx + 1) * gw // TILES_X, (ty + 1) * gh // TILES_Y))
                zonas.append(linha)
        dados.append((p.rsplit("/", 1)[-1], global_, zonas))
        print("  %-34s nitidez %9.5f" % (dados[-1][0], global_))

    if not mapa or len(dados) < 2:
        return 0

    print("\n  Onde cada zona fica mais nítida (índice do ficheiro, 1 a %d):" % len(dados))
    for ty in range(TILES_Y):
        linha = []
        for tx in range(TILES_X):
            melhor = max(range(len(dados)), key=lambda k: dados[k][2][ty][tx])
            linha.append("%2d" % (melhor + 1))
        print("     " + " ".join(linha))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
