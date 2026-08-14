#!/usr/bin/env python3
"""
Verificador de DNG, sem dependências.

Um DNG é um TIFF: basta percorrer os IFD. Serve o critério de aceitação da F1 (§9 da
especificação) e é o embrião da ferramenta de validação da §10.

Uso:  python3 dngcheck.py ficheiro.dng [outro.dng ...]
"""

import struct
import sys
from array import array

TYPE_SIZE = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8}

TAGS = {
    254: "NewSubfileType", 256: "ImageWidth", 257: "ImageLength", 258: "BitsPerSample",
    259: "Compression", 262: "PhotometricInterpretation", 271: "Make", 272: "Model",
    273: "StripOffsets", 274: "Orientation", 277: "SamplesPerPixel", 278: "RowsPerStrip",
    279: "StripByteCounts", 282: "XResolution", 283: "YResolution", 305: "Software",
    306: "DateTime", 330: "SubIFDs", 33421: "CFARepeatPatternDim", 33422: "CFAPattern",
    33434: "ExposureTime", 33437: "FNumber", 34665: "ExifIFD", 34855: "ISOSpeedRatings",
    36867: "DateTimeOriginal", 37386: "FocalLength", 50706: "DNGVersion",
    50707: "DNGBackwardVersion", 50708: "UniqueCameraModel", 50710: "CFAPlaneColor",
    50711: "CFALayout", 50712: "LinearizationTable", 50713: "BlackLevelRepeatDim",
    50714: "BlackLevel", 50717: "WhiteLevel", 50718: "DefaultScale",
    50721: "ColorMatrix1", 50722: "ColorMatrix2", 50723: "CameraCalibration1",
    50724: "CameraCalibration2", 50727: "AnalogBalance", 50728: "AsShotNeutral",
    50730: "BaselineExposure", 50731: "BaselineNoise", 50733: "BaselineSharpness",
    50734: "LinearResponseLimit", 50739: "ShadowScale", 50740: "DNGPrivateData",
    50778: "CalibrationIlluminant1", 50779: "CalibrationIlluminant2",
    50781: "RawDataUniqueID", 50827: "OriginalRawFileName",
    50964: "ForwardMatrix1", 50965: "ForwardMatrix2",
    51008: "OpcodeList1", 51009: "OpcodeList2", 51022: "OpcodeList3",
    51041: "NoiseProfile",
}

CFA_COLOR = {0: "R", 1: "G", 2: "B", 3: "C", 4: "M", 5: "Y", 6: "W"}

ILLUMINANT = {
    1: "Daylight", 2: "Fluorescent", 3: "Tungsten", 4: "Flash", 17: "Standard A",
    18: "Standard B", 19: "Standard C", 20: "D55", 21: "D65", 22: "D75", 23: "D50",
    24: "ISO studio tungsten",
}

PHOTOMETRIC = {1: "BlackIsZero", 2: "RGB", 6: "YCbCr", 32803: "CFA (mosaico)", 34892: "LinearRaw"}


class Tiff:

    def __init__(self, data):
        self.data = data
        if data[:2] == b"II":
            self.end = "<"
        elif data[:2] == b"MM":
            self.end = ">"
        else:
            raise ValueError("não é TIFF/DNG")
        magic, self.first = struct.unpack(self.end + "HI", data[2:8])
        if magic != 42:
            raise ValueError("magia TIFF inesperada: %d" % magic)

    def u(self, fmt, off):
        return struct.unpack_from(self.end + fmt, self.data, off)

    def ifd(self, off):
        """Devolve {tag: (tipo, contagem, valor)} e o offset do IFD seguinte."""
        count, = self.u("H", off)
        out = {}
        p = off + 2
        for _ in range(count):
            tag, typ, n = self.u("HHI", p)
            size = TYPE_SIZE.get(typ, 1) * n
            if size <= 4:
                raw = self.data[p + 8:p + 8 + size]
            else:
                voff, = self.u("I", p + 8)
                raw = self.data[voff:voff + size]
            out[tag] = (typ, n, self.decode(typ, n, raw))
            p += 12
        nxt, = self.u("I", p)
        return out, nxt

    def decode(self, typ, n, raw):
        e = self.end
        try:
            if typ == 2:
                return raw.split(b"\0")[0].decode("utf-8", "replace")
            if typ in (1, 7):
                return list(raw[:n])
            if typ == 3:
                return list(struct.unpack(e + "%dH" % n, raw[:2 * n]))
            if typ == 4:
                return list(struct.unpack(e + "%dI" % n, raw[:4 * n]))
            if typ == 9:
                return list(struct.unpack(e + "%di" % n, raw[:4 * n]))
            if typ in (5, 10):
                f = "%dI" % (2 * n) if typ == 5 else "%di" % (2 * n)
                v = struct.unpack(e + f, raw[:8 * n])
                return [(v[2 * i], v[2 * i + 1]) for i in range(n)]
            if typ == 11:
                return list(struct.unpack(e + "%df" % n, raw[:4 * n]))
            if typ == 12:
                return list(struct.unpack(e + "%dd" % n, raw[:8 * n]))
        except struct.error:
            return None
        return list(raw[:min(n, 32)])


OPCODES = {
    1: "WarpRectilinear (distorção/aberração)", 2: "WarpFisheye", 3: "FixVignetteRadial",
    4: "FixBadPixelsConstant", 5: "FixBadPixelsList", 6: "TrimBounds", 7: "MapTable",
    8: "MapPolynomial", 9: "GainMap (vinhetagem)", 10: "DeltaPerRow", 11: "DeltaPerColumn",
    12: "ScalePerRow", 13: "ScalePerColumn",
}


def gainmap(payload):
    """
    Descodifica um opcode GainMap (id 9) da especificação DNG.

    Cabeçalho: Top, Left, Bottom, Right, Plane, Planes, RowPitch, ColPitch, MapPointsV,
    MapPointsH (10 × uint32), MapSpacingV/H e MapOriginV/H (4 × double), MapPlanes (uint32),
    e depois MapGains (float32).
    """
    if len(payload) < 76:
        return None
    top, left, bottom, right, plane, planes, rowp, colp, pv, ph = struct.unpack_from(">10I", payload, 0)
    sv, sh, ov, oh = struct.unpack_from(">4d", payload, 40)
    mp, = struct.unpack_from(">I", payload, 72)
    n = pv * ph * mp
    if 76 + 4 * n > len(payload):
        return None
    gains = struct.unpack_from(">%df" % n, payload, 76)
    return {
        "plano": plane, "planos": planes, "pontos": (pv, ph), "mapPlanes": mp,
        "passo": (rowp, colp), "gains": gains,
    }


def opcodes(raw):
    """
    Descodifica uma lista de opcodes DNG.

    As listas de opcodes são sempre big-endian, independentemente da ordem de bytes do TIFF.
    """
    if not raw or len(raw) < 4:
        return []
    data = bytes(raw)
    count = struct.unpack_from(">I", data, 0)[0]
    out = []
    p = 4
    for _ in range(count):
        if p + 16 > len(data):
            break
        oid, ver, flags, size = struct.unpack_from(">IIII", data, p)
        payload = data[p + 16:p + 16 + size]
        out.append((oid, OPCODES.get(oid, "desconhecido %d" % oid), size, flags, payload))
        p += 16 + size
    return out


def rat(v):
    """Racionais para float. Aceita valores já numéricos — o NoiseProfile vem em DOUBLE."""
    if not v:
        return None
    out = []
    for item in v:
        if isinstance(item, tuple) and len(item) == 2:
            num, den = item
            out.append(num / den if den else 0.0)
        else:
            out.append(item)
    return out


def fmt_matrix(v):
    f = rat(v)
    if not f or len(f) != 9:
        return str(f)
    return "\n".join("      [%9.5f %9.5f %9.5f]" % tuple(f[i * 3:i * 3 + 3]) for i in range(3))


def show(tiff, tags, title, indent="  "):
    print("%s%s" % (indent, title))
    for tag in sorted(tags):
        typ, n, val = tags[tag]
        name = TAGS.get(tag, "tag %d" % tag)
        if tag in (50721, 50722, 50723, 50724, 50964, 50965):
            print("%s  %-24s\n%s" % (indent, name, fmt_matrix(val)))
        elif tag == 33422:
            letters = "".join(CFA_COLOR.get(c, "?") for c in val)
            print("%s  %-24s %s   %s" % (indent, name, val, letters))
        elif tag in (50778, 50779):
            code = val[0] if isinstance(val, list) else val
            print("%s  %-24s %s (%s)" % (indent, name, code, ILLUMINANT.get(code, "?")))
        elif tag == 262:
            code = val[0] if isinstance(val, list) else val
            print("%s  %-24s %s (%s)" % (indent, name, code, PHOTOMETRIC.get(code, "?")))
        elif tag in (50728, 50727, 51041, 50718):
            print("%s  %-24s %s" % (indent, name, rat(val)))
        elif typ in (5, 10):
            print("%s  %-24s %s" % (indent, name, rat(val)))
        elif tag in (51008, 51009, 51022, 50712, 50740):
            print("%s  %-24s %d bytes" % (indent, name, n))
        else:
            s = str(val)
            print("%s  %-24s %s" % (indent, name, s if len(s) < 90 else s[:87] + "..."))


def raw_stats(tiff, tags, white):
    """Estatísticas sobre os dados do mosaico, por amostragem."""
    if 273 not in tags or 279 not in tags:
        return None
    offs = tags[273][2]
    counts = tags[279][2]
    bits = tags[258][2]
    bits = bits[0] if isinstance(bits, list) else bits
    if bits != 16:
        return "amostragem só implementada para 16 bits (este tem %s)" % bits

    lo, hi, total, n = 65535, 0, 0, 0
    over = 0
    for off, cnt in zip(offs, counts):
        buf = tiff.data[off:off + cnt]
        a = array("H")
        a.frombytes(buf[:len(buf) - (len(buf) % 2)])
        if sys.byteorder != ("little" if tiff.end == "<" else "big"):
            a.byteswap()
        step = max(1, len(a) // 200000)
        for i in range(0, len(a), step):
            v = a[i]
            if v < lo:
                lo = v
            if v > hi:
                hi = v
            if v > white:
                over += 1
            total += v
            n += 1
    if not n:
        return None
    return {"min": lo, "max": hi, "média": total / n, "amostras": n, "acima do branco": over}


def ler_negativo(path):
    """Os bytes do negativo, esteja ele solto ou dentro do arquivo `.zip` da aplicação.

    A aplicação passou a guardar cada fotografia num zip com o `.dng` e a receita lá dentro — 24 MB
    passam a 7 ou 8, sem tocar num bit da imagem. As ferramentas têm de continuar a servir para os
    dois casos, senão a promessa de o negativo ser verificável fica dependente de quem se lembra de
    descomprimir primeiro.
    """
    if path.lower().endswith(".zip"):
        import zipfile
        with zipfile.ZipFile(path) as z:
            nomes = [n for n in z.namelist() if n.lower().endswith(".dng")]
            if not nomes:
                raise SystemExit("%s não tem nenhum .dng lá dentro" % path)
            return z.read(nomes[0])
    with open(path, "rb") as fh:
        return fh.read()


def check(path):
    print("=" * 78)
    print(path)
    print("=" * 78)
    data = ler_negativo(path)
    print("  tamanho                  %.1f MB" % (len(data) / 1024 / 1024))

    tiff = Tiff(data)
    ifd0, _ = tiff.ifd(tiff.first)
    show(tiff, ifd0, "IFD0 (metadados e pré-visualização)")

    subs = ifd0.get(330, (None, 0, []))[2] or []
    raw_tags = None

    # O DngCreator escreve o mosaico no próprio IFD0, sem SubIFD.
    photo0 = ifd0.get(262, (None, 0, [None]))[2]
    photo0 = photo0[0] if isinstance(photo0, list) else photo0
    if photo0 == 32803:
        raw_tags = ifd0

    for i, off in enumerate(subs):
        sub, _ = tiff.ifd(off)
        photo = sub.get(262, (None, 0, [None]))[2]
        photo = photo[0] if isinstance(photo, list) else photo
        print()
        show(tiff, sub, "SubIFD %d%s" % (i, "  ← imagem RAW" if photo == 32803 else ""))
        if photo == 32803:
            raw_tags = sub

    if 34665 in ifd0:
        exif_off = ifd0[34665][2]
        exif_off = exif_off[0] if isinstance(exif_off, list) else exif_off
        exif, _ = tiff.ifd(exif_off)
        print()
        show(tiff, exif, "EXIF")

    print()
    print("  VEREDICTO")
    if raw_tags is None:
        print("    sem imagem de mosaico — o DNG não tem RAW")
        return None

    def one(tags, tag, default=None):
        v = tags.get(tag, (None, 0, default))[2]
        return v[0] if isinstance(v, list) and len(v) == 1 else v

    white = one(raw_tags, 50717) or 1023
    black = raw_tags.get(50714, (None, 0, None))[2]
    cfa = raw_tags.get(33422, (None, 0, []))[2]
    letters = "".join(CFA_COLOR.get(c, "?") for c in cfa) if cfa else "?"
    comp = one(raw_tags, 259)

    print("    mosaico                %s" % letters)
    print("    nível de branco        %s  (%d bits úteis)" % (white, white.bit_length()))
    print("    nível de preto         %s" % (rat(black) if black and isinstance(black[0], tuple) else black))
    print("    compressão             %s%s" % (comp, " (sem compressão)" if comp == 1 else ""))
    print("    ciência da cor         ColorMatrix1 %s · ForwardMatrix1 %s" % (
        "sim" if 50721 in raw_tags or 50721 in ifd0 else "NÃO",
        "sim" if 50964 in raw_tags or 50964 in ifd0 else "NÃO"))
    print("    AsShotNeutral          %s" % rat(ifd0.get(50728, raw_tags.get(50728, (None, 0, None)))[2]))
    print("    NoiseProfile           %s" % rat(ifd0.get(51041, raw_tags.get(51041, (None, 0, None)))[2]))
    for tag, name, quando in ((51008, "OpcodeList1", "antes da linearização"),
                              (51009, "OpcodeList2", "depois do mapeamento para linear"),
                              (51022, "OpcodeList3", "depois do demosaico")):
        if tag not in raw_tags:
            continue
        ops = opcodes(raw_tags[tag][2])
        print("    %-22s %d bytes · %s" % (name, raw_tags[tag][1], quando))
        maxgain = 0.0
        for oid, nome, size, flags, payload in ops:
            print("      → %s  (%d bytes, flags %d)" % (nome, size, flags))
            if oid != 9:
                continue
            gm = gainmap(payload)
            if not gm:
                continue
            g = gm["gains"]
            lo, hi = min(g), max(g)
            if hi > maxgain:
                maxgain = hi
            print("         plano %d de %d · malha %dx%d · ganho %.4f a %.4f (centro %.4f)" % (
                gm["plano"], gm["planos"], gm["pontos"][0], gm["pontos"][1],
                lo, hi, g[len(g) // 2]))
        if maxgain:
            if maxgain < 1.02:
                print("      OK: os ganhos são ~1,0 — o mapa é neutro, não há correcção a dobrar.")
            else:
                print("      AVISO: ganho máximo de %.2f. Como o RAW já vem com shading aplicado," % maxgain)
                print("             um revelador que honre o GainMap corrige DUAS vezes e os cantos")
                print("             ficam claros demais. Ver §10.4 da especificação.")

    st = raw_stats(tiff, raw_tags, white if isinstance(white, int) else 1023)
    if isinstance(st, dict):
        print("    dados do mosaico       mín %d · máx %d · média %.1f  (%d amostras)" % (
            st["min"], st["max"], st["média"], st["amostras"]))
        if st["acima do branco"]:
            print("    AVISO                  %d amostras acima do nível de branco" % st["acima do branco"])
        if st["max"] <= white:
            print("    intervalo              coerente com %d bits" % white.bit_length())
    elif st:
        print("    dados do mosaico       %s" % st)

    orient = one(ifd0, 274)
    return {
        "asShotNeutral": rat(ifd0.get(50728, raw_tags.get(50728, (None, 0, None)))[2]),
        "orientation": orient,
        "white": white,
        "cfa": letters,
    }


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    for p in sys.argv[1:]:
        check(p)
        print()
