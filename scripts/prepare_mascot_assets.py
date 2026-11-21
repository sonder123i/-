#!/usr/bin/env python3
"""生成「鲸鲸」伴侣的透明素材。

用法：
  python scripts/prepare_mascot_assets.py --src <目录> --out app/src/main/res/drawable-nodpi

--src 目录里需要这些文件（1024x1024 白底 WebP/PNG）：
  peek.webp   run1.webp   run2.webp   cheer.webp
  stare.webp  sleep.webp  avatar.webp  avatar_stare.webp

处理：
- 只去掉与图像边缘连通的近白背景，保留帽子/衣服内部的白色
- peek/stare/sleep 裁掉底部横线，让手正好落在卡片边缘
- 常规 UI 资源最大 256px 透明 WebP；圆形徽章居中补成 256 方图
- 奔跑双帧另导出 96x96 透明 PNG，作为地图小人
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageOps
from scipy import ndimage

WHITE_THRESHOLD = 242
WHITE_CHROMA = 14
MASCOT_MAX = 256
MAP_SIZE = 96


def background_mask(rgb: np.ndarray) -> np.ndarray:
    """与边缘连通的近白区域；内部白色不受影响。"""
    r = rgb[..., 0].astype(np.int16)
    g = rgb[..., 1].astype(np.int16)
    b = rgb[..., 2].astype(np.int16)
    high = np.maximum(np.maximum(r, g), b)
    low = np.minimum(np.minimum(r, g), b)
    near_white = (r >= WHITE_THRESHOLD) & (g >= WHITE_THRESHOLD) & (
        b >= WHITE_THRESHOLD
    ) & ((high - low) <= WHITE_CHROMA)

    labels, _ = ndimage.label(near_white)
    border = np.concatenate([labels[0, :], labels[-1, :], labels[:, 0], labels[:, -1]])
    border_labels = np.unique(border[border > 0])
    return np.isin(labels, border_labels)


def cut_white(path: Path, *, crop_bar: bool = False) -> Image.Image:
    source = Image.open(path).convert("RGBA")
    rgb = np.asarray(source.convert("RGB"))
    background = background_mask(rgb)

    alpha = np.where(background, 0, 255).astype(np.uint8)
    alpha = np.asarray(
        Image.fromarray(alpha, "L").filter(ImageFilter.GaussianBlur(1.0))
    )
    result = source.copy()
    result.putalpha(Image.fromarray(alpha, "L"))

    if crop_bar:
        dark = (rgb.max(axis=2) < 90) & ~background
        counts = dark.sum(axis=1)
        height, width = rgb.shape[:2]
        rows = np.where(counts > width * 0.55)[0]
        rows = rows[rows > height * 0.55]
        if rows.size:
            cut = max(1, int(rows.min()) - 2)
            result = result.crop((0, 0, width, cut))

    bbox = result.getbbox()
    if bbox:
        result = result.crop(bbox)

    if crop_bar:
        dark = (rgb.max(axis=2) < 90) & ~background
        counts = dark.sum(axis=1)
        height, width = rgb.shape[:2]
        rows = np.where(counts > width * 0.55)[0]
        rows = rows[rows > height * 0.55]
        if rows.size:
            # 取最底部的暗色满宽行（横杠），而不是最上面的深色裙摆。
            bottom = int(rows.max())
            top = bottom
            while top > 0 and counts[top - 1] > width * 0.55:
                top -= 1
            result = result.crop((0, 0, width, max(1, top - 1)))

        # 灰色/浅色横杠兜底：只接受高度不超过 6 行的满宽不透明条。
        arr = np.asarray(result)
        alpha = arr[..., 3]
        height, width = alpha.shape
        for y in range(int(height * 0.80), height):
            if (alpha[y] > 32).mean() > 0.95:
                top = y
                while top > 0 and (alpha[top - 1] > 32).mean() > 0.95:
                    top -= 1
                bottom = y
                while bottom < height - 1 and (alpha[bottom + 1] > 32).mean() > 0.95:
                    bottom += 1
                if bottom - top + 1 <= 6:
                    result = result.crop((0, 0, width, top))
                    break
    return result


def fit_square(image: Image.Image, size: int) -> Image.Image:
    return ImageOps.pad(image, (size, size), method=Image.LANCZOS, color=(0, 0, 0, 0))


def fit_map_marker(image: Image.Image, size: int) -> Image.Image:
    """脚底贴底边，锚点用 bottom-center。"""
    copy = image.copy()
    copy.thumbnail((size, size), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(copy, ((size - copy.width) // 2, size - copy.height))
    return canvas


def save_webp(image: Image.Image, out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    image.save(out, "WEBP", lossless=True, quality=100, method=6)


def make_contact_sheet(
    images: dict[str, Image.Image], out: Path, background: str
) -> None:
    tile = 300
    label = 26
    cols = 3
    rows = (len(images) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * tile, rows * (tile + label)), background)
    draw = ImageDraw.Draw(sheet)
    for index, (name, image) in enumerate(images.items()):
        thumb = image.copy()
        thumb.thumbnail((tile - 24, tile - 24), Image.LANCZOS)
        x = (index % cols) * tile
        y = (index // cols) * (tile + label)
        sheet.paste(thumb, (x + (tile - thumb.width) // 2,
                            y + label + (tile - thumb.height) // 2),
                    thumb if thumb.mode == "RGBA" else None)
        draw.text((x + 8, y + 6), name, fill="#888888")
    sheet.save(out)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--src", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--qa", type=Path, default=Path("build/mascot-assets"))
    args = parser.parse_args()

    peek = cut_white(args.src / "peek.webp", crop_bar=False)
    run1 = cut_white(args.src / "run1.webp")
    run2 = cut_white(args.src / "run2.webp")
    cheer = cut_white(args.src / "cheer.webp")
    stare = cut_white(args.src / "stare.webp", crop_bar=False)
    sleep = cut_white(args.src / "sleep.webp", crop_bar=False)
    avatar = cut_white(args.src / "avatar.webp")
    avatar_stare = cut_white(args.src / "avatar_stare.webp")

    normal: dict[str, Image.Image] = {
        "jing_peek_idle": peek,
        "jing_run_frame1": run1,
        "jing_run_frame2": run2,
        "jing_cheer": cheer,
        "jing_stare": stare,
        "jing_sleep": sleep,
    }
    for name, image in normal.items():
        copy = image.copy()
        copy.thumbnail((MASCOT_MAX, MASCOT_MAX), Image.LANCZOS)
        save_webp(copy, args.out / f"{name}.webp")
        normal[name] = copy

    avatar_square = fit_square(avatar, MASCOT_MAX)
    save_webp(avatar_square, args.out / "jing_avatar_badge.webp")
    avatar_stare_square = fit_square(avatar_stare, MASCOT_MAX)
    save_webp(avatar_stare_square, args.out / "jing_avatar_stare.webp")

    marker1 = fit_map_marker(run1, MAP_SIZE)
    marker2 = fit_map_marker(run2, MAP_SIZE)
    args.out.mkdir(parents=True, exist_ok=True)
    marker1.save(args.out / "map_jing_run1.png", "PNG")
    marker2.save(args.out / "map_jing_run2.png", "PNG")

    qa_images = dict(normal)
    qa_images["jing_avatar_badge"] = avatar_square
    qa_images["jing_avatar_stare"] = avatar_stare_square
    qa_images["map_jing_run1"] = marker1
    qa_images["map_jing_run2"] = marker2
    args.qa.mkdir(parents=True, exist_ok=True)
    make_contact_sheet(qa_images, args.qa / "mascot_contact_light.png", "#FFFFFF")
    make_contact_sheet(qa_images, args.qa / "mascot_contact_dark.png", "#161618")

    for name, image in qa_images.items():
        path = args.out / f"{name}.webp"
        if path.exists():
            print(f"{path.name}: {image.width}x{image.height} {path.stat().st_size} B")
    for marker_name, marker in (("map_jing_run1", marker1), ("map_jing_run2", marker2)):
        path = args.out / f"{marker_name}.png"
        print(f"{path.name}: {marker.width}x{marker.height} {path.stat().st_size} B")
    print(f"QA dir: {args.qa}")


if __name__ == "__main__":
    main()