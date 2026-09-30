"""Gera a logo DeskLink (SVG + PNG + ICO) e os ícones do Android a partir de uma única definição."""
import os
from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), "..")
C1, C2 = (79, 70, 229), (6, 182, 212)  # indigo -> ciano

SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="100" height="100">
  <defs>
    <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#4F46E5"/><stop offset="1" stop-color="#06B6D4"/>
    </linearGradient>
  </defs>
  <rect width="100" height="100" rx="24" fill="url(#g)"/>
  <rect x="16" y="22" width="54" height="38" rx="6" fill="#fff" fill-opacity=".16" stroke="#fff" stroke-width="5"/>
  <path d="M43 60v10M32 72h22" stroke="#fff" stroke-width="5" stroke-linecap="round"/>
  <rect x="56" y="42" width="26" height="42" rx="7" fill="#fff" stroke="#4F46E5" stroke-width="3"/>
  <rect x="64" y="78" width="10" height="2.5" rx="1.25" fill="#4F46E5"/>
  <circle cx="69" cy="66" r="2.2" fill="#06B6D4"/>
  <path d="M64 61a7 7 0 0 1 10 0M60.6 57.6a12 12 0 0 1 16.8 0" stroke="#06B6D4" stroke-width="3" stroke-linecap="round" fill="none"/>
</svg>
"""


def gradient(size):
    big = Image.linear_gradient("L").resize((int(size * 1.5), int(size * 1.5))).rotate(45)
    o = (big.width - size) // 2
    mask = big.crop((o, o, o + size, o + size))
    return Image.composite(Image.new("RGB", (size, size), C2), Image.new("RGB", (size, size), C1), mask)


def draw(size, rounded=True, scale_fg=1.0, fg_only=False):
    S = 4  # supersample
    n = size * S
    u = n / 100.0
    k = scale_fg

    def T(v, axis=None):  # escala em torno do centro (para ícone adaptativo)
        return (50 + (v - 50) * k) * u

    def sc(v):
        return v * k * u

    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    if not fg_only:
        bg = gradient(n).convert("RGBA")
        m = Image.new("L", (n, n), 0)
        ImageDraw.Draw(m).rounded_rectangle((0, 0, n - 1, n - 1), 24 * u if rounded else 0, fill=255)
        img.paste(bg, (0, 0), m)
    # tela do monitor: preenchimento translúcido (composto, não substituído)
    ov = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    ImageDraw.Draw(ov).rounded_rectangle((T(16), T(22), T(70), T(60)), sc(6), fill=(255, 255, 255, 41))
    img = Image.alpha_composite(img, ov)
    d = ImageDraw.Draw(img)
    W = (255, 255, 255, 255)
    lw = int(sc(5))
    d.rounded_rectangle((T(16), T(22), T(70), T(60)), sc(6), outline=W, width=lw)
    d.line((T(43), T(60), T(43), T(70)), fill=W, width=lw)
    d.line((T(32), T(72), T(54), T(72)), fill=W, width=lw)
    for x in (32, 54):
        r = lw / 2
        d.ellipse((T(x) - r, T(72) - r, T(x) + r, T(72) + r), fill=W)
    ind = C1 + (255,)
    d.rounded_rectangle((T(56), T(42), T(82), T(84)), sc(7), fill=W, outline=ind, width=max(1, int(sc(3))))
    d.rounded_rectangle((T(64), T(78), T(74), T(80.5)), sc(1.25), fill=ind)
    cy = C2 + (255,)
    aw = int(sc(3))
    cx, cyy = T(69), T(66)
    d.ellipse((cx - sc(2.2), cyy - sc(2.2), cx + sc(2.2), cyy + sc(2.2)), fill=cy)
    for rad in (7, 12):
        d.arc((cx - sc(rad), cyy - sc(rad), cx + sc(rad), cyy + sc(rad)), 225, 315, fill=cy, width=aw)
    return img.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(os.path.join(ROOT, "assets"), exist_ok=True)
    open(os.path.join(ROOT, "assets/logo.svg"), "w").write(SVG)
    big = draw(1024)
    big.save(os.path.join(ROOT, "assets/logo-1024.png"))
    big.save(os.path.join(ROOT, "assets/icon.ico"), sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])
    ui_public = os.path.join(ROOT, "desktop/ui/public")
    os.makedirs(ui_public, exist_ok=True)
    open(os.path.join(ui_public, "logo.svg"), "w").write(SVG)
    draw(64).save(os.path.join(ui_public, "favicon.png"))
    # Android: ícones legados (API < 26) e store
    res = os.path.join(ROOT, "android/app/src/main/res")
    for dens, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        d = os.path.join(res, f"mipmap-{dens}")
        os.makedirs(d, exist_ok=True)
        draw(px).save(os.path.join(d, "ic_launcher.png"))
        # redondo
        r = draw(px, rounded=True)
        m = Image.new("L", (px * 4, px * 4), 0)
        ImageDraw.Draw(m).ellipse((0, 0, px * 4 - 1, px * 4 - 1), fill=255)
        m = m.resize((px, px), Image.LANCZOS)
        r.putalpha(Image.composite(r.getchannel("A"), Image.new("L", (px, px), 0), m))
        r.save(os.path.join(d, "ic_launcher_round.png"))
    nodpi = os.path.join(res, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)
    draw(256).save(os.path.join(nodpi, "logo.png"))
    draw(512).save(os.path.join(ROOT, "assets/play-store-512.png"))


if __name__ == "__main__":
    main()
