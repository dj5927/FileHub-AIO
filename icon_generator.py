"""Create the original NAS Mount drive icon (bundled .ico, no external assets)."""
from pathlib import Path
from PIL import Image, ImageDraw

out = Path(__file__).resolve().parent / "assets" / "NASMountNetwork.ico"
out.parent.mkdir(parents=True, exist_ok=True)
size = 256
im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
d = ImageDraw.Draw(im)
d.ellipse((8, 8, 247, 247), fill=(24, 136, 111, 255), outline=(11, 76, 99, 255), width=11)
d.ellipse((32, 32, 222, 222), outline=(241, 253, 249, 255), width=11)
d.ellipse((88, 30, 165, 225), outline=(230, 251, 244, 240), width=9)
d.line((32, 126, 224, 126), fill=(238, 255, 249, 255), width=10)
d.arc((34, 75, 223, 174), start=0, end=180, fill=(230, 251, 244, 245), width=8)
d.arc((34, 78, 223, 179), start=180, end=360, fill=(230, 251, 244, 245), width=8)
im.save(out, format="ICO", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print(f"ICON_CREATED={out} SIZE={out.stat().st_size}")
