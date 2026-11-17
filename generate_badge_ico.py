"""Create Windows multi-resolution BMP-format ICO and small local protocol PNGs.

Input NASMountBadge.png was downsampled from the user's supplied round teal
badge; the opaque/transparent pixels and color motif are retained. BMP-format
ICO entries are intentional: some System.Drawing icon loaders render PNG-format
ICO entries with incorrect alpha. This format matches the EXE icon resource.
"""
from pathlib import Path
from PIL import Image

assets = Path(__file__).resolve().parent / "assets"
badge = Image.open(assets / "NASMountBadge.png").convert("RGBA")
badge = badge.resize((256, 256), Image.Resampling.LANCZOS)
badge.save(
    assets / "NASMountBadge.ico", format="ICO",
    sizes=[(16, 16), (24, 24), (32, 32), (48, 48),
           (64, 64), (128, 128), (256, 256)],
    bitmap_format="bmp",
)

for source, dest in (
    ("Google_Drive_logo.png", "GoogleDriveMark.png"),
    ("favicon.ico", "TeraBoxMark.png"),
):
    image = Image.open(assets / source).convert("RGBA")
    image.thumbnail((89, 89), Image.Resampling.LANCZOS)
    final = Image.new("RGBA", (96, 96), (0, 0, 0, 0))
    final.alpha_composite(image, ((96 - image.width) // 2,
                                  (96 - image.height) // 2))
    final.save(assets / dest, optimize=True)

print("BADGE_ICO_OK", (assets / "NASMountBadge.ico").stat().st_size)
print("GOOGLE_AND_TERABOX_MARKS_OK")
