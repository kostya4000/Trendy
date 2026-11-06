#!/usr/bin/env python3
"""Собирает иконки и ТВ-баннер из исходного логотипа.

Использование:
    tools/make-icons.py путь/к/trendy-logo.png

Нужен Pillow (python3 -m pip install --user pillow). Скрипт нужен только при
смене логотипа: готовые файлы лежат в репозитории и коммитятся.

Из исходной картинки убираются чёрные поля и светлая окантовка скруглённого
квадрата, поэтому иконка не тащит за собой рамку. Адаптивная иконка — это один
холст 108dp: размытый фон во весь размер и резкий логотип 100dp по центру, так
края логотипа не видны ни на круглой, ни на квадратной маске лаунчера.

Иконки хранятся в WebP (поддерживается с API 18, у нас minSdk 23) и только в
плотности xxxhdpi: система сама уменьшает их до нужного размера, а APK не
толстеет на сотни килобайт.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
ICON_DIR = "mipmap-xxxhdpi"

# Радиус скругления исходной картинки относительно её стороны.
CORNER_RATIO = 0.2325
# Логотип занимает 78dp из 108dp: лаунчер показывает только центральную
# часть холста, и надпись Trendy не должна упираться в круглую маску.
ART_RATIO = 78.0 / 108.0
# Сколько пикселей исходника срезать по краю: убирает светлую окантовку.
EDGE_INSET = 30

LAUNCHER_SIZE = 192        # 48dp при xxxhdpi
ADAPTIVE_SIZE = 432        # 108dp при xxxhdpi
WEBP_QUALITY = 92


def crop_art(image):
    """Обрезает чёрные поля и окантовку вокруг скруглённого квадрата."""
    rgb = image.convert("RGB")
    width, height = rgb.size
    pixels = rgb.load()
    left, top, right, bottom = width, height, 0, 0
    step = max(1, min(width, height) // 600)
    for y in range(0, height, step):
        for x in range(0, width, step):
            r, g, b = pixels[x, y]
            if r + g + b > 12:
                if x < left:
                    left = x
                if x > right:
                    right = x
                if y < top:
                    top = y
                if y > bottom:
                    bottom = y
    inset = max(0, EDGE_INSET * (right - left + 1) // 1200)
    return image.crop((left + inset, top + inset, right + 1 - inset, bottom + 1 - inset))


def rounded_mask(size, radius, supersample=8):
    mask = Image.new("L", (size * supersample, size * supersample), 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle(
        (0, 0, size * supersample - 1, size * supersample - 1),
        radius=radius * supersample,
        fill=255,
    )
    return mask.resize((size, size), Image.LANCZOS)


def circle_mask(size, supersample=8):
    mask = Image.new("L", (size * supersample, size * supersample), 0)
    ImageDraw.Draw(mask).ellipse(
        (0, 0, size * supersample - 1, size * supersample - 1), fill=255
    )
    return mask.resize((size, size), Image.LANCZOS)


def feather_alpha(size, ratio=0.055):
    """Плавно растворяет края квадрата, чтобы не было видно его границ."""
    width = max(1, int(round(size * ratio)))
    mask = Image.new("L", (size, size), 255)
    pixels = mask.load()
    for y in range(size):
        for x in range(size):
            distance = min(x, y, size - 1 - x, size - 1 - y)
            if distance < width:
                pixels[x, y] = int(255 * distance / width)
    return mask


def save(image, folder, name):
    path = os.path.join(RES, folder, name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path, "WEBP", quality=WEBP_QUALITY, method=6)
    print("  ", os.path.relpath(path, ROOT), image.size, os.path.getsize(path) // 1024, "KB")


def build_icons(art):
    square = art.resize((LAUNCHER_SIZE, LAUNCHER_SIZE), Image.LANCZOS)
    square.putalpha(rounded_mask(LAUNCHER_SIZE, int(round(LAUNCHER_SIZE * CORNER_RATIO))))
    save(square, ICON_DIR, "ic_launcher.webp")

    round_icon = art.resize((LAUNCHER_SIZE, LAUNCHER_SIZE), Image.LANCZOS)
    round_icon.putalpha(circle_mask(LAUNCHER_SIZE))
    save(round_icon, ICON_DIR, "ic_launcher_round.webp")

    content = int(round(ADAPTIVE_SIZE * ART_RATIO))
    logo = art.resize((content, content), Image.LANCZOS).convert("RGBA")
    logo.putalpha(feather_alpha(content))

    # Края логотипа растворены в фоне, поэтому квадрат не видно ни на круглой,
    # ни на квадратной маске лаунчера.
    foreground = Image.new("RGBA", (ADAPTIVE_SIZE, ADAPTIVE_SIZE), (0, 0, 0, 0))
    foreground.alpha_composite(logo, ((ADAPTIVE_SIZE - content) // 2,) * 2)
    save(foreground, ICON_DIR, "ic_launcher_foreground.webp")


def font(name, size):
    for base in ("/usr/share/fonts/truetype/dejavu/", "/usr/share/fonts/TTF/",
                 "/Library/Fonts/", os.path.expanduser("~/Library/Fonts/")):
        path = os.path.join(base, name)
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    raise SystemExit("Не найден шрифт " + name)


def build_banner(art):
    width, height = 320, 180

    # Фон: тёмный, с мягким сине-фиолетовым свечением справа.
    banner = Image.new("RGB", (width, height), (11, 11, 22))
    pixels = banner.load()
    for y in range(height):
        for x in range(width):
            dx = (x - 262) / 150.0
            dy = (y - 62) / 118.0
            k = max(0.0, 1.0 - (dx * dx + dy * dy))
            pixels[x, y] = (
                int(11 + 40 * k),
                int(11 + 16 * k),
                int(22 + 72 * k),
            )

    # Градиентная полоска слева (маджента -> голубой).
    bar_width, bar_top, bar_height = 7, 36, 108
    bar = Image.new("RGB", (bar_width, bar_height))
    bar_pixels = bar.load()
    for y in range(bar_height):
        t = y / float(bar_height - 1)
        color = (
            int(196 + (43 - 196) * t),
            int(59 + (176 - 59) * t),
            255,
        )
        for x in range(bar_width):
            bar_pixels[x, y] = color
    bar_mask = Image.new("L", (bar_width, bar_height), 0)
    ImageDraw.Draw(bar_mask).rounded_rectangle(
        (0, 0, bar_width - 1, bar_height - 1), radius=bar_width / 2.0, fill=255
    )
    banner.paste(bar, (18, bar_top), bar_mask)

    draw = ImageDraw.Draw(banner)
    draw.text((38, 42), "TRENDY", font=font("DejaVuSans-Bold.ttf", 34),
              fill=(245, 245, 250))
    draw.text((39, 92), "promodj.com · музыка сообщества",
              font=font("DejaVuSans.ttf", 13), fill=(142, 142, 166))
    path = os.path.join(RES, "drawable-nodpi", "tv_banner.png")
    banner.save(path, optimize=True)
    print("  ", os.path.relpath(path, ROOT), banner.size,
          os.path.getsize(path) // 1024, "KB")


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Использование: tools/make-icons.py путь/к/логотипу.png")
    art = crop_art(Image.open(sys.argv[1]).convert("RGBA"))
    print("Логотип:", art.size)
    build_icons(art)
    build_banner(art)


if __name__ == "__main__":
    main()
