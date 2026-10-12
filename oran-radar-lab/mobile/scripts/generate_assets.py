"""Generate the Oran Radar icon assets without external image files."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / "assets"
N = 1024


def icon(transparent=False, mono=False):
    scale = 2
    im = Image.new("RGBA", (N * scale, N * scale),
                   (0, 0, 0, 0) if transparent else (9, 17, 29, 255))
    d = ImageDraw.Draw(im)
    S = lambda x: int(x * scale)
    teal = (255, 255, 255, 255) if mono else (93, 225, 187, 255)
    muted = (255, 255, 255, 160) if mono else (48, 111, 114, 255)
    # Open rings suggest a scanning radar. The rising price path is a second cue.
    for radius, width in ((315, 8), (241, 12), (163, 12)):
        box = (S(512-radius), S(512-radius), S(512+radius), S(512+radius))
        d.arc(box, 202, 340, fill=muted, width=S(width))
        d.arc(box, 16, 155, fill=muted, width=S(width))
    points = [(S(x), S(y)) for x, y in
              ((255, 635), (380, 568), (476, 590), (580, 452), (684, 480), (778, 333))]
    d.line(points, fill=teal, width=S(37), joint="curve")
    r = S(18)
    for x, y in points:
        d.ellipse((x-r, y-r, x+r, y+r), fill=teal)
    d.polygon([(S(778), S(333)), (S(670), S(342)), (S(755), S(433))], fill=teal)
    return im.resize((N, N), Image.Resampling.LANCZOS)


if __name__ == "__main__":
    ROOT.mkdir(exist_ok=True)
    icon().convert("RGB").save(ROOT / "icon.png")
    icon(transparent=True).save(ROOT / "android-icon-foreground.png")
    icon(transparent=True, mono=True).save(ROOT / "android-icon-monochrome.png")
    Image.new("RGB", (N, N), (9, 17, 29)).save(ROOT / "android-icon-background.png")
    icon().convert("RGB").resize((512, 512), Image.Resampling.LANCZOS).save(ROOT / "splash-icon.png")
    icon().convert("RGB").resize((48, 48), Image.Resampling.LANCZOS).save(ROOT / "favicon.png")
