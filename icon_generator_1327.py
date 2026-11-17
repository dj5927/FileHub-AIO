"""Original NAS Mount application/Explorer icon, multi-resolution Windows ICO.
Design: midnight rounded tile, cyan N-shaped network route and storage bays.
No third-party artwork is used.
"""
from pathlib import Path
from PIL import Image, ImageDraw

out = Path(__file__).resolve().parent / 'assets' / 'NASMountNetwork.ico'
preview = out.with_suffix('.png')
out.parent.mkdir(parents=True, exist_ok=True)
S = 1024
image = Image.new('RGBA', (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(image)
d.rounded_rectangle((72, 72, 952, 952), radius=240,
                    fill=(17, 42, 67, 255))
d.rounded_rectangle((91, 91, 933, 933), radius=222,
                    outline=(68, 139, 177, 200), width=15)

# Upper part: a simple, distinct letter N / network routing mark.
d.line([(270, 418), (270, 235), (510, 443), (510, 230)],
       fill=(45, 197, 246, 255), width=90, joint='curve')
d.line([(510, 230), (685, 230)], fill=(45, 197, 246, 255), width=90)
d.ellipse((653, 178, 760, 285), fill=(231, 253, 255, 255))

# Lower part: two compact NAS storage bays with online status lamps.
for top in (540, 694):
    d.rounded_rectangle((210, top, 808, top + 123), radius=39,
                        fill=(238, 248, 252, 255))
    d.rounded_rectangle((235, top + 17, 780, top + 105), radius=27,
                        outline=(163, 192, 211, 255), width=8)
    d.ellipse((257, top + 47, 300, top + 90), fill=(27, 200, 157, 255))
    d.line((363, top + 69, 705, top + 69), fill=(94, 140, 164, 255), width=17)
d.rounded_rectangle((310, 836, 720, 862), radius=13, fill=(24, 197, 209, 255))

image.save(preview, format='PNG')
image.save(out, format='ICO', sizes=[
    (16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print(f'NASMOUNT_CUSTOM_ICON={out} BYTES={out.stat().st_size}')
