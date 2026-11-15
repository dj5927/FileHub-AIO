from pathlib import Path
from PIL import Image
import hashlib

base = Path(r"D:\GPT\NAS_MOUNT\Android")
previous = base / "NASMountAndroid_0_1_62" / "app" / "src" / "main" / "res" / "drawable-nodpi"
current = base / "NASMountAndroid_0_1_63" / "app" / "src" / "main" / "res" / "drawable-nodpi"
current.mkdir(parents=True, exist_ok=True)

# The existing 320px-wide PNG clipped the O at the rightmost pixel.
# The rounded O is symmetric about x=303, reconstruct the missing arc
# from the intact left half (no text font dependencies or font files).
original = Image.open(previous / "filehub_aio_wordmark.png").convert("RGBA")
assert original.size == (320, 46)
complete = Image.new("RGBA", (328, 46), (0, 0, 0, 0))
complete.paste(original, (0, 0))
for y in range(46):
    for x in range(304, 328):
        complete.putpixel((x, y), original.getpixel((606-x, y)))
# Add real transparent breathing room so no letter can touch the PNG edge.
padded = Image.new("RGBA", (368, 66), (0, 0, 0, 0))
padded.paste(complete, (20, 10))
padded.save(current / "filehub_aio_wordmark.png", optimize=True)
assert padded.getpixel((0, 0))[3] == 0
assert padded.getpixel((367, 65))[3] == 0
assert any(padded.getpixel((20+323, y))[3] > 100 for y in range(66))
assert padded.getchannel("A").getbbox()[0] == 20

# Previous icon PNG had RGBA (251,248,252,24) at ALL four corners,
# tinting the entire drawer icon box on a dark theme.
icon = Image.open(previous / "filehub_aio_icon.png").convert("RGBA")
assert icon.size == (128, 122)
pixels = []
for r, g, b, a in icon.getdata():
    # Remove the 24/255 uniformly composited milky background.
    adjusted = max(0, min(255, round((a - 24) * 255 / (254 - 24))))
    if adjusted <= 8:
        adjusted = 0
    pixels.append((r, g, b, adjusted))
clean = Image.new("RGBA", icon.size)
clean.putdata(pixels)
# Keep the icon's detail but position it on a fully clear canvas.
icon_final = Image.new("RGBA", (148, 148), (0, 0, 0, 0))
icon_final.paste(clean, (10, 13))
icon_final.save(current / "filehub_aio_icon.png", optimize=True)

for image, name in [(padded, "filehub_aio_wordmark.png"), (icon_final, "filehub_aio_icon.png")]:
    alpha = image.getchannel("A")
    bbox = alpha.getbbox()
    assert bbox is not None
    assert 0 < bbox[0] < bbox[2] < image.width
    assert 0 < bbox[1] < bbox[3] < image.height
    for xy in [(0,0), (image.width-1,0), (0,image.height-1), (image.width-1,image.height-1)]:
        assert image.getpixel(xy)[3] == 0, (name,xy)
    file = current / name
    print(f"{name} size={image.size} bbox={bbox} corners_alpha=0 sha256={hashlib.sha256(file.read_bytes()).hexdigest().upper()} bytes={file.stat().st_size}")