#!/usr/bin/env python3
"""
Revelador de referência da Latente, em Python.

É a implementação de referência do pipeline da §6.1 da especificação. Existe para uma coisa:
**validar a matemática da cor onde ela se pode medir**, antes de a portar para GLSL, onde não há
como inspeccionar valores intermédios.

Trabalha em resolução reduzida por *binning*, porque para validar cor a resolução é irrelevante e
sem numpy o preço por pixel é alto. O caminho matemático é exactamente o mesmo do shader.

Uso:  python3 tools/develop.py ficheiro.dng [--bin 4] [--ev 0] [--rolloff 1.6] [--out saida.png]
"""

import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from array import array  # noqa: E402
import dngcheck  # noqa: E402

# Bradford, D50 → D65. As ForwardMatrix da DNG entregam XYZ em D50.
BRADFORD_D50_D65 = (
    0.9555766, -0.0230393, 0.0631636,
    -0.0282895, 1.0099416, 0.0210077,
    0.0122982, -0.0204830, 1.3299098,
)

# XYZ D65 → sRGB linear
XYZ_TO_SRGB = (
    3.2404542, -1.5371385, -0.4985314,
    -0.9692660, 1.8760108, 0.0415560,
    0.0556434, -0.2040259, 1.0572252,
)

ILLUMINANT_K = {
    1: 5503.0, 2: 4150.0, 3: 2856.0, 17: 2856.0, 20: 5503.0,
    21: 6504.0, 22: 7504.0, 23: 5003.0, 24: 3200.0,
}


def mat_inv(m):
    """Inversa de uma 3x3. Precisa-se para o caminho da ColorMatrix, que mapeia XYZ → câmara."""
    a, b, c, d, e, f, g, h, i = m
    det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    if abs(det) < 1e-12:
        return None
    return (
        (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
        (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
        (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
    )


def mat_vec(m, v):
    return (m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
            m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
            m[6] * v[0] + m[7] * v[1] + m[8] * v[2])


def bradford_matrix(src_white, dst_white):
    """Adaptação de Bradford entre dois brancos, em XYZ."""
    M = (0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296)
    Mi = mat_inv(M)
    s = mat_vec(M, src_white)
    d = mat_vec(M, dst_white)
    diag = (d[0] / s[0], 0.0, 0.0, 0.0, d[1] / s[1], 0.0, 0.0, 0.0, d[2] / s[2])
    return mat_mul(Mi, mat_mul(diag, M))


def mat_mul(a, b):
    """Produto de duas matrizes 3x3 em ordem de linhas."""
    out = [0.0] * 9
    for i in range(3):
        for j in range(3):
            out[i * 3 + j] = sum(a[i * 3 + k] * b[k * 3 + j] for k in range(3))
    return tuple(out)


def srgb_encode(x):
    if x <= 0.0:
        return 0.0
    if x >= 1.0:
        return 1.0
    return x * 12.92 if x <= 0.0031308 else 1.055 * (x ** (1.0 / 2.4)) - 0.055


class Raw:
    """O mosaico e os metadados de que o revelador precisa."""

    def __init__(self, path):
        # Solto ou dentro do arquivo: quem revela não tem de saber. Ver `dngcheck.ler_negativo`.
        data = dngcheck.ler_negativo(path)
        self.t = dngcheck.Tiff(data)
        ifd0, _ = self.t.ifd(self.t.first)
        self.ifd = ifd0

        def one(tag, default=None):
            v = ifd0.get(tag, (None, 0, default))[2]
            if isinstance(v, list) and len(v) == 1:
                return v[0]
            return v

        self.width = one(256)
        self.height = one(257)
        self.white = one(50717) or 1023
        black = dngcheck.rat(ifd0.get(50714, (None, 0, None))[2]) or [0.0, 0.0, 0.0, 0.0]
        self.black = black
        self.cfa = ifd0.get(33422, (None, 0, [1, 2, 0, 1]))[2]
        self.neutral = dngcheck.rat(ifd0.get(50728, (None, 0, None))[2]) or [1.0, 1.0, 1.0]
        self.cm1 = dngcheck.rat(ifd0.get(50721, (None, 0, None))[2])
        self.cm2 = dngcheck.rat(ifd0.get(50722, (None, 0, None))[2])
        self.fm1 = dngcheck.rat(ifd0.get(50964, (None, 0, None))[2])
        self.fm2 = dngcheck.rat(ifd0.get(50965, (None, 0, None))[2])
        self.il1 = one(50778)
        phys = dngcheck.rat(ifd0.get(50719, (None, 0, None))[2])
        # Tamanho físico do sensor e distância focal, para o perfil radial. Vêm do EXIF.
        self.focal = one(37386)
        if isinstance(self.focal, tuple):
            self.focal = self.focal[0] / self.focal[1] if self.focal[1] else None
        elif isinstance(self.focal, list) and self.focal:
            v = self.focal[0]
            self.focal = v[0] / v[1] if isinstance(v, tuple) and v[1] else v
        # O tamanho do sensor não vem no DNG. Lê-se do sidecar da aplicação, que traz o factor
        # de recorte — e daí sai a diagonal. Ter isto fixo no valor da câmara principal fazia a
        # comparação com cos⁴θ dar disparates nas outras objectivas.
        self.sensorWidth = 8.16
        self.sensorHeight = 6.12
        lado = os.path.splitext(path)[0] + ".json"
        receita = None
        if path.lower().endswith(".zip"):
            # A receita viaja no mesmo saco que o negativo.
            import zipfile
            with zipfile.ZipFile(path) as z:
                nomes = [n for n in z.namelist() if n.lower().endswith(".json")]
                if nomes:
                    receita = z.read(nomes[0]).decode("utf-8")
        elif os.path.exists(lado):
            with open(lado, encoding="utf-8") as fh:
                receita = fh.read()
        if receita is not None:
            try:
                import json as _json
                meta = _json.loads(receita)
                for filho in meta.get("filhos", []):
                    if filho.get("nome") != "Objectiva":
                        continue
                    recorte = filho.get("factor de recorte")
                    if recorte:
                        diag = 43.2666 / recorte
                        # sensor 4:3
                        self.sensorWidth = diag * 0.8
                        self.sensorHeight = diag * 0.6
                    if filho.get("distância focal mm"):
                        self.focal = float(filho["distância focal mm"])
            except Exception:
                pass
        self.il2 = one(50779)

        offs = ifd0[273][2]
        counts = ifd0[279][2]
        self.rows = []
        for off, cnt in zip(offs, counts):
            a = array("H")
            a.frombytes(data[off:off + cnt - (cnt % 2)])
            if sys.byteorder != ("little" if self.t.end == "<" else "big"):
                a.byteswap()
            self.rows.append(a)

    def forward_matrix(self, kelvin):
        """
        ForwardMatrix interpolada entre os dois iluminantes de referência.

        Interpola-se em 1/T, como a especificação DNG indica.
        """
        if self.fm1 and not self.fm2:
            return tuple(self.fm1)
        if self.fm2 and not self.fm1:
            return tuple(self.fm2)
        if not self.fm1:
            return None
        t1 = ILLUMINANT_K.get(self.il1, 6504.0)
        t2 = ILLUMINANT_K.get(self.il2, 2856.0)
        hot, cold = max(t1, t2), min(t1, t2)
        m_hot = self.fm1 if t1 >= t2 else self.fm2
        m_cold = self.fm2 if t1 >= t2 else self.fm1
        t = min(max(float(kelvin), cold), hot)
        g = 0.0 if hot == cold else (1.0 / t - 1.0 / hot) / (1.0 / cold - 1.0 / hot)
        return tuple(m_hot[i] * (1.0 - g) + m_cold[i] * g for i in range(9))

    def colour_matrix(self, kelvin):
        """ColorMatrix interpolada. Mapeia XYZ (D50) → câmara; é preciso inverter para revelar."""
        if not self.cm1:
            return None
        if not self.cm2:
            return tuple(self.cm1)
        t1 = ILLUMINANT_K.get(self.il1, 6504.0)
        t2 = ILLUMINANT_K.get(self.il2, 2856.0)
        hot, cold = max(t1, t2), min(t1, t2)
        m_hot = self.cm1 if t1 >= t2 else self.cm2
        m_cold = self.cm2 if t1 >= t2 else self.cm1
        t = min(max(float(kelvin), cold), hot)
        g = 0.0 if hot == cold else (1.0 / t - 1.0 / hot) / (1.0 / cold - 1.0 / hot)
        return tuple(m_hot[i] * (1.0 - g) + m_cold[i] * g for i in range(9))

    def bin_rgb(self, factor, shading=None, shading_strength=1.0):
        """
        Mosaico → três planos lineares, por média de blocos.

        O *binning* respeita o padrão do CFA: cada quarteto dá um R, dois G e um B. É o mesmo
        que o visor fará no telemóvel (§6.4), e para validar cor chega e sobra.
        """
        idx = {}
        for pos, colour in enumerate(self.cfa):
            idx.setdefault(colour, []).append((pos % 2, pos // 2))

        quads = max(1, factor // 2)
        ow = self.width // (quads * 2)
        oh = self.height // (quads * 2)
        white = float(self.white)
        # O nível de preto vem por posição do CFA; aqui já se sabe que é zero neste telefone,
        # mas o código não presume.
        black = [self.black[i % len(self.black)] for i in range(4)]

        cx, cy = self.width / 2.0, self.height / 2.0
        rmax = math.hypot(cx, cy)

        out = []
        for oy in range(oh):
            row = []
            for ox in range(ow):
                acc = [0.0, 0.0, 0.0]
                cnt = [0, 0, 0]
                for qy in range(quads):
                    y0 = (oy * quads + qy) * 2
                    if y0 + 1 >= self.height:
                        continue
                    r0 = self.rows[y0]
                    r1 = self.rows[y0 + 1]
                    for qx in range(quads):
                        x0 = (ox * quads + qx) * 2
                        if x0 + 1 >= self.width:
                            continue
                        pix = (r0[x0], r0[x0 + 1], r1[x0], r1[x0 + 1])
                        for pos in range(4):
                            colour = self.cfa[pos]
                            if colour > 2:
                                continue
                            v = (pix[pos] - black[pos]) / (white - black[pos])
                            if shading is not None:
                                # A correcção de vinhetagem entra aqui: sobre o mosaico, antes do
                                # balanço e de qualquer interpolação. É propriedade da óptica e do
                                # sensor, e tem de sair antes de os píxeis se misturarem.
                                px = x0 + (pos % 2)
                                py = y0 + (pos // 2)
                                rr = math.hypot(px + 0.5 - cx, py + 0.5 - cy) / rmax
                                v *= shading_gain(shading, colour, rr, shading_strength)
                            acc[colour] += v
                            cnt[colour] += 1
                row.append(tuple(acc[c] / cnt[c] if cnt[c] else 0.0 for c in range(3)))
            out.append(row)
        return ow, oh, out


def load_shading(caminho):
    """Perfil radial por canal, gerado por tools/shading.py."""
    import json as _json
    with open(caminho, encoding="utf-8") as fh:
        d = _json.load(fh)
    return d["R"], d["G"], d["B"]


def shading_gain(perfil, canal, r, forca=1.0):
    """Ganho interpolado. Os anéis foram medidos nos seus centros: o anel k está em (k+0,5)/n."""
    v = perfil[canal]
    n = len(v)
    pos = r * n - 0.5
    k = int(math.floor(pos))
    t = pos - k
    if k < 0:
        queda = v[0]
    elif k >= n - 1:
        queda = v[n - 1]
    else:
        queda = v[k] + (v[k + 1] - v[k]) * t
    g = 1.0 / queda if queda > 1e-4 else 1e4
    if forca >= 1.0 or g <= 1.0:
        return g
    # A força aplica-se em stops: metade da força é metade dos stops, não metade do factor.
    return g ** max(0.0, min(1.0, forca))


def develop(path, factor=4, ev=0.0, rolloff=1.6, kelvin=None, out_path=None, bradford=True,
            matrix="forward", shading=None, shading_strength=1.0):
    raw = Raw(path)
    print("  %s" % os.path.basename(path))
    print("    mosaico %dx%d · branco %d · CFA %s" % (
        raw.width, raw.height, raw.white,
        "".join(dngcheck.CFA_COLOR.get(c, "?") for c in raw.cfa)))
    print("    AsShotNeutral %s" % [round(v, 4) for v in raw.neutral])

    # A temperatura de revelação, por omissão, é a que o ficheiro traz.
    if kelvin is None:
        kelvin = 5500
    fm = raw.forward_matrix(kelvin)
    if fm is None:
        print("    sem ForwardMatrix — impossível revelar com fidelidade")
        return None

    # camRGB / AsShotNeutral → XYZ D50 → XYZ D65 → sRGB linear, numa só matriz.
    # A ForwardMatrix entrega XYZ com ponto branco D50 (confirma-se somando as linhas: dá o
    # branco D50). O caminho conforme a especificação adapta D50 → D65 antes do sRGB. O
    # interruptor existe para descobrir que convenção usa um revelador de referência.
    if matrix == "color":
        # Caminho da ColorMatrix, que é o que reveladores como o darktable usam:
        # XYZ = inv(CM)·câmara, adaptado do branco da cena para D65.
        cm = raw.colour_matrix(kelvin)
        inv = mat_inv(cm) if cm else None
        if inv is None:
            print("    sem ColorMatrix invertível")
            return None
        scene_white = mat_vec(inv, raw.neutral)
        d65 = (0.95047, 1.0, 1.08883)
        m = mat_mul(XYZ_TO_SRGB, mat_mul(bradford_matrix(scene_white, d65), inv))
        # Neste caminho o balanço já está na adaptação: não se divide pelo AsShotNeutral.
        wb_in_matrix = True
    else:
        m = (mat_mul(XYZ_TO_SRGB, mat_mul(BRADFORD_D50_D65, fm)) if bradford
             else mat_mul(XYZ_TO_SRGB, fm))
        wb_in_matrix = False
    gain = 2.0 ** ev
    wb = ([1.0, 1.0, 1.0] if wb_in_matrix
          else [1.0 / max(v, 1e-6) for v in raw.neutral])

    ow, oh, planes = raw.bin_rgb(factor, shading, shading_strength)
    print("    revelado a %dx%d (bin %d) · %d K · %+.1f EV · matriz %s · shading %s" % (
        ow, oh, factor, kelvin, ev, matrix,
        ("%.0f%%" % (shading_strength * 100)) if shading else "não"))

    buf = bytearray(ow * oh * 3)
    wp2 = rolloff * rolloff
    p = 0
    for row in planes:
        for cam in row:
            r = cam[0] * wb[0] * gain
            g = cam[1] * wb[1] * gain
            b = cam[2] * wb[2] * gain
            x = m[0] * r + m[1] * g + m[2] * b
            y = m[3] * r + m[4] * g + m[5] * b
            z = m[6] * r + m[7] * g + m[8] * b
            for v in (x, y, z):
                if v < 0.0:
                    v = 0.0
                # Reinhard estendido: linear em baixo, ombro suave no topo.
                v = v * (1.0 + v / wp2) / (1.0 + v)
                buf[p] = int(srgb_encode(v) * 255.0 + 0.5)
                p += 1

    if out_path is None:
        out_path = os.path.join(os.path.dirname(HERE), "out",
                                os.path.splitext(os.path.basename(path))[0] + "_latente.png")
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    try:
        from PIL import Image
        Image.frombytes("RGB", (ow, oh), bytes(buf)).save(out_path)
        print("    escrito %s" % out_path)
    except ImportError:
        print("    sem PIL: não se escreveu imagem")
    return out_path


def main(argv):
    args = {"bin": 4, "ev": 0.0, "rolloff": 1.6, "kelvin": None, "out": None, "bradford": True,
            "matrix": "forward", "shading": None,
            "shading_strength": 1.0}
    files = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--bin":
            i += 1
            args["bin"] = int(argv[i])
        elif a == "--ev":
            i += 1
            args["ev"] = float(argv[i])
        elif a == "--rolloff":
            i += 1
            args["rolloff"] = float(argv[i])
        elif a == "--kelvin":
            i += 1
            args["kelvin"] = int(argv[i])
        elif a == "--shading-strength":
            i += 1
            args["shading_strength"] = float(argv[i])
        elif a == "--shading":
            i += 1
            args["shading"] = load_shading(argv[i])
        elif a == "--sidecar":
            # Reconstrói a revelação a partir da receita que o telefone gravou. É o critério de
            # aceitação da F5: o sidecar não descreve a revelação, repete-a.
            i += 1
            import json as _json
            with open(argv[i]) as fh:
                doc = _json.load(fh)
            bloco = None
            for c in doc.get("filhos", []):
                if c.get("nome") == "Revelação":
                    bloco = c
                    break
            if bloco is None:
                print("  o sidecar não tem bloco de Revelação — foi escrito antes da F5?")
                return 1
            args["ev"] = float(bloco["exposição de revelação EV"])
            args["kelvin"] = int(bloco["temperatura K"])
            args["rolloff"] = float(bloco["rolloff"])
            args["shading_strength"] = float(bloco["força da vinhetagem"])
            print("  receita do sidecar: %+.2f EV · %d K · rolloff %.2f · vinhetagem %.0f%%" % (
                args["ev"], args["kelvin"], args["rolloff"], args["shading_strength"] * 100))
        elif a == "--matrix":
            i += 1
            args["matrix"] = argv[i]
        elif a == "--no-bradford":
            args["bradford"] = False
        elif a == "--out":
            i += 1
            args["out"] = argv[i]
        else:
            files.append(a)
        i += 1
    if not files:
        print(__doc__)
        return 1
    for f in files:
        develop(f, args["bin"], args["ev"], args["rolloff"], args["kelvin"], args["out"],
                args["bradford"], args["matrix"], args["shading"], args["shading_strength"])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
