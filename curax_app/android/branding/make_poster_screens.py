from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

out_dir = Path(__file__).resolve().parent
assets = Path(
    r"C:\Users\PMYLS\.cursor\projects\c-Work\assets"
)

# Full-res phone screenshots
DOSE_DASHBOARD = assets / (
    "c__Users_PMYLS_AppData_Roaming_Cursor_User_workspaceStorage_049d08ff9bf7c7ef6e93def8d2319233_"
    "images_image-7f6df7b9-cac0-4b1b-927c-552b1f1cde03.png"
)
ADMIN_DASHBOARD = assets / (
    "c__Users_PMYLS_AppData_Roaming_Cursor_User_workspaceStorage_049d08ff9bf7c7ef6e93def8d2319233_"
    "images_AIEnhancer_WhatsApp_Image_2026-05-15_at_9__1_-c6e6433c-babc-4cf6-8d4e-81523dda8a23.png"
)


def load_screen(path: Path) -> Image.Image:
    return Image.open(path).convert("RGB").filter(
        ImageFilter.UnsharpMask(radius=0.8, percent=110, threshold=2)
    )


def phone_with_screen(
    screenshot: Image.Image,
    fw: int = 480,
    fh: int = 960,
    bezel=(28, 28, 28),
    accent=(201, 162, 39),
) -> Image.Image:
    canvas = Image.new("RGBA", (fw, fh), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)

    shadow = Image.new("RGBA", (fw, fh), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sd.rounded_rectangle((14, 18, fw - 6, fh - 4), radius=38, fill=(0, 0, 0, 65))
    shadow = shadow.filter(ImageFilter.GaussianBlur(7))
    canvas.alpha_composite(shadow)

    draw.rounded_rectangle((8, 6, fw - 8, fh - 6), radius=36, fill=accent + (255,))
    draw.rounded_rectangle((14, 12, fw - 14, fh - 12), radius=30, fill=bezel + (255,))

    screen_x, screen_y = 20, 32
    screen_w, screen_h = fw - 40, fh - 50

    ss = screenshot.copy()
    scale = max(screen_w / ss.width, screen_h / ss.height)
    nw, nh = int(ss.width * scale), int(ss.height * scale)
    ss = ss.resize((nw, nh), Image.LANCZOS)
    cx = (nw - screen_w) // 2
    cy = (nh - screen_h) // 2
    ss = ss.crop((cx, cy, cx + screen_w, cy + screen_h))

    mask = Image.new("L", (screen_w, screen_h), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, screen_w, screen_h), radius=6, fill=255)
    screen = Image.new("RGBA", (screen_w, screen_h), (255, 255, 255, 255))
    screen.paste(ss, (0, 0))
    screen.putalpha(mask)
    canvas.paste(screen, (screen_x, screen_y), screen)

    draw.ellipse((fw // 2 - 9, 16, fw // 2 + 9, 34), fill=(12, 12, 12, 255))
    return canvas


def save_single(screenshot: Image.Image, filename: str, scale: int = 2):
    big = phone_with_screen(screenshot, fw=480 * scale, fh=960 * scale)
    w, h = big.size
    out = big.resize((w // scale, h // scale), Image.LANCZOS)
    path = out_dir / filename
    out.convert("RGB").save(path, format="PNG", optimize=True)
    print(f"Saved {path} ({out.size[0]}x{out.size[1]})")


# Best for Proposed Method: dose dashboard (boxes + adherence)
save_single(load_screen(DOSE_DASHBOARD), "poster_single_screen.png")

# Optional: admin dashboard for Results / caregiver section
save_single(load_screen(ADMIN_DASHBOARD), "poster_single_admin.png")

print("Done")
