"""Surgical patch: fan/peltier wires off + one up-arrow each. Rest locked from v2."""
from pathlib import Path

from PIL import Image, ImageDraw

SRC = Path(r"c:\Work\curax_app\android\branding\poster_hardware_diagram_ai_v2.png")
OUT = Path(r"c:\Work\curax_app\android\branding\poster_hardware_diagram_ai_v3.png")

img = Image.open(SRC).convert("RGB")
draw = ImageDraw.Draw(img)
w, h = img.size
bg = img.getpixel((40, 600))


def fill(box):
    draw.rectangle(box, fill=bg, outline=bg)


def arrow_up(x, y0, y1, width=5, color=(30, 30, 30)):
    draw.line([(x, y0), (x, y1 + 16)], fill=color, width=width)
    draw.polygon([(x, y1), (x - 13, y1 + 20), (x + 13, y1 + 20)], fill=color)


# Fan power drops + bottom rail under fans only
fill((210, 888, 455, h - 2))
fill((250, 868, 285, 895))
fill((360, 868, 395, 895))

# Peltier power drops only (keep module/heatsink visible)
fill((640, 905, 775, h - 2))
fill((668, 872, 682, 908))
fill((728, 872, 742, 908))
fill((655, 845, 770, 872))
# short horizontal stubs into peltier
fill((620, 862, 795, 876))

# Bottom shared rail segment between fans & peltier (not touching far-right buck wires)
fill((200, 948, 820, 968))

# Single up arrows
arrow_up(312, 842, 658)   # fans -> upper boxes
arrow_up(704, 838, 612)   # peltier -> cold zone

img.save(OUT, format="PNG", optimize=True)
print(f"Saved {OUT}")
