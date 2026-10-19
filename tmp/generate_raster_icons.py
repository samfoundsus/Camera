import os
import subprocess
from PIL import Image, ImageDraw

def render_icon(size, output_png, is_round=False):
    # 1. Create dark background canvas
    img = Image.new("RGBA", (size, size), (20, 22, 27, 255))
    
    # Fill subtle dark linear gradient
    draw = ImageDraw.Draw(img)
    for y in range(size):
        factor = y / float(size)
        r = int(20 * (1 - factor) + 9 * factor)
        g = int(22 * (1 - factor) + 10 * factor)
        b = int(27 * (1 - factor) + 12 * factor)
        draw.line([(0, y), (size, y)], fill=(r, g, b, 255))

    # Scale factors relative to 108dp viewport
    scale = size / 108.0

    # Draw camera body & top notch (white/light slate #F1F3F4)
    # Notch: (48, 33) to (60, 37) with corner rx=2
    # Body: (31, 37) to (77, 71) with corner rx=6
    body_color = (241, 243, 244, 255)
    
    # Top notch
    notch_left = 48 * scale
    notch_top = 33 * scale
    notch_right = 60 * scale
    notch_bottom = 37 * scale
    draw.rounded_rectangle([notch_left, notch_top, notch_right, notch_bottom], radius=2*scale, fill=body_color)

    # Main camera body
    body_left = 31 * scale
    body_top = 37 * scale
    body_right = 77 * scale
    body_bottom = 71 * scale
    draw.rounded_rectangle([body_left, body_top, body_right, body_bottom], radius=6*scale, fill=body_color)

    # Outer lens ring (Dark graphite #1F2024)
    # Center (54, 52), radius 11
    cx, cy = 54 * scale, 52 * scale
    r_outer = 11 * scale
    draw.ellipse([cx - r_outer, cy - r_outer, cx + r_outer, cy + r_outer], fill=(31, 32, 36, 255))

    # Inner lens aperture (Deep black #111316)
    # Center (54, 52), radius 5.5
    r_inner = 5.5 * scale
    draw.ellipse([cx - r_inner, cy - r_inner, cx + r_inner, cy + r_inner], fill=(17, 19, 22, 255))

    # Specular lens highlight (#E8EAED) at (51.5, 49.5), radius 1.5
    sx, sy = 51.5 * scale, 49.5 * scale
    sr = 1.5 * scale
    draw.ellipse([sx - sr, sy - sr, sx + sr, sy + sr], fill=(232, 234, 237, 255))

    # Flash dot (#3C4043) at (69, 42), radius 1.75
    fx, fy = 69 * scale, 42 * scale
    fr = 1.75 * scale
    draw.ellipse([fx - fr, fy - fr, fx + fr, fy + fr], fill=(60, 64, 67, 255))

    if is_round:
        # Mask to inscribed circle
        mask = Image.new("L", (size, size), 0)
        mask_draw = ImageDraw.Draw(mask)
        mask_draw.ellipse([0, 0, size, size], fill=255)
        img.putalpha(mask)

    img.save(output_png, "PNG")

densities = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192
}

base_dir = "/app/src/main/res"

for folder, size in densities.items():
    dir_path = os.path.join(base_dir, folder)
    os.makedirs(dir_path, exist_ok=True)
    
    # 1. Square icon
    square_png = os.path.join(dir_path, "ic_launcher.png")
    render_icon(size, square_png, is_round=False)
    
    # 2. Round icon
    round_png = os.path.join(dir_path, "ic_launcher_round.png")
    render_icon(size, round_png, is_round=True)

    # 3. Clean up template .webp files if they exist
    for webp in ["ic_launcher.webp", "ic_launcher_round.webp"]:
        webp_path = os.path.join(dir_path, webp)
        if os.path.exists(webp_path):
            os.remove(webp_path)
            print(f"Removed template webp: {webp_path}")

print("Raster PNG icons successfully generated across all densities!")
