#!/usr/bin/env python3
"""
Verificação completa de um DNG da Latente.

Faz três coisas, por esta ordem:

1. lê os metadados com `dngcheck.py` (Python puro, sem dependências);
2. revela o ficheiro com o **darktable**, um revelador independente — é o critério de aceitação
   da F1 (§9 da especificação): se um revelador a sério abre o ficheiro, o DNG está bem escrito;
3. mede o equilíbrio de cor da revelação, que é como se prova que o `AsShotNeutral` está certo.

O darktable é usado por flatpak, para não exigir root:

    flatpak install --user flathub org.darktable.Darktable

Uso:  python3 tools/verify.py ficheiro.dng [mais.dng ...]
"""

import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
WORK = os.path.join(ROOT, "out")

sys.path.insert(0, HERE)
import dngcheck  # noqa: E402


def render(dng, tag):
    """Revela com o darktable e devolve o caminho do JPEG, ou None."""
    os.makedirs(os.path.join(WORK, "dtconf"), exist_ok=True)
    src = os.path.join(WORK, "in_%s.dng" % tag)
    out = os.path.join(WORK, "render_%s.jpg" % tag)
    shutil.copyfile(dng, src)
    if os.path.exists(out):
        os.remove(out)

    cmd = [
        "flatpak", "run", "--filesystem=%s" % ROOT,
        "--command=darktable-cli", "org.darktable.Darktable",
        src, out, "--width", "1200",
        "--core", "--configdir", os.path.join(WORK, "dtconf"), "--library", ":memory:",
    ]
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=400)
    except FileNotFoundError:
        print("  darktable não encontrado (flatpak install --user flathub org.darktable.Darktable)")
        return None
    except subprocess.TimeoutExpired:
        print("  darktable excedeu o tempo")
        return None

    if not os.path.exists(out):
        print("  a revelação falhou:")
        for line in (p.stderr or p.stdout or "").splitlines()[-8:]:
            print("    " + line)
        return None
    return out


def colours(jpg, facts):
    """
    Equilíbrio de cor da revelação.

    O veredicto vem do `AsShotNeutral` do ficheiro, não das médias da imagem: uma cena real não é
    cinzenta, e o mundo-cinzento dá falsos alarmes. As médias ficam como informação.
    """
    try:
        from PIL import Image
    except ImportError:
        print("  sem PIL: não se mede a cor (pip install pillow)")
        return

    im = Image.open(jpg).convert("RGB")
    px = list(im.getdata())
    n = len(px)
    r = sum(p[0] for p in px) / n
    g = sum(p[1] for p in px) / n
    b = sum(p[2] for p in px) / n
    if g <= 0:
        print("  revelação sem sinal no verde")
        return

    rg, bg = r / g, b / g
    print("    revelação              %s (%dx%d)" % (os.path.basename(jpg), im.size[0], im.size[1]))
    print("    média R G B            %.1f  %.1f  %.1f" % (r, g, b))
    print("    razões R/G · B/G       %.3f · %.3f  (só informativo: a cena não é cinzenta)" % (rg, bg))

    neutral = (facts or {}).get("asShotNeutral")
    if not neutral:
        print("    BALANÇO DE BRANCOS     AUSENTE — o DNG não traz AsShotNeutral")
    elif max(abs(v - 1.0) for v in neutral) < 0.02:
        print("    BALANÇO DE BRANCOS     NEUTRO [1,1,1] — o ficheiro diz \'não corrijas nada\'.")
        print("                           A revelação vai sair verde. Verificar")
        print("                           COLOR_CORRECTION_GAINS no pedido de captura.")
    else:
        print("    BALANÇO DE BRANCOS     presente: %s" % [round(v, 4) for v in neutral])
        print("                           o revelador honra-o; se a cor parecer quente ou fria,")
        print("                           é a temperatura escolhida a não bater com a luz da cena")

    orient = (facts or {}).get("orientation")
    if orient not in (None, 1):
        print("    orientação             %s" % orient)
    elif orient == 1:
        print("    orientação             1 (normal) — se o telefone estava em retrato, o ficheiro")
        print("                           sai deitado: falta ler o sensor de rotação")

    if max(r, g, b) < 40:
        print("    NOTA                   revelação escura: pouca luz para a exposição usada")


def main(paths):
    for dng in paths:
        facts = dngcheck.check(dng)
        tag = os.path.splitext(os.path.basename(dng))[0][-8:]
        print()
        print("  REVELAÇÃO INDEPENDENTE (darktable)")
        jpg = render(dng, tag)
        if jpg:
            colours(jpg, facts)
        print()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1:])
