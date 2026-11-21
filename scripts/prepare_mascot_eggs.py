#!/usr/bin/env python3
"""生成「鲸鲸」彩蛋拍立得小卡资源。

用法：
  python scripts/prepare_mascot_eggs.py --src <目录> --out app/src/main/res/drawable-nodpi

--src 里放彩蛋原图，文件名决定资源名：
  welcome_arms.png -> egg_welcome_arms.webp
  poke_pressure.jpg -> egg_poke_pressure.webp
  slacking_whale.jpg -> egg_slacking_whale.webp
  ...

处理原则：
- **不做抠图**，保留原图背景与梗；
- 宽度压到 512px 以内，lossy WebP quality=80；
- PNG 的透明通道保留。
"""
from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image

MAX_WIDTH = 512
QUALITY = 80
EXTS = {".png", ".jpg", ".jpeg", ".webp"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--src", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    total = 0
    for path in sorted(args.src.iterdir()):
        if not path.is_file() or path.suffix.lower() not in EXTS:
            continue
        image = Image.open(path)
        if image.width > MAX_WIDTH:
            height = max(1, round(image.height * MAX_WIDTH / image.width))
            image = image.resize((MAX_WIDTH, height), Image.LANCZOS)
        if image.mode not in ("RGB", "RGBA"):
            image = image.convert("RGBA" if image.has_transparency_data else "RGB")
        target = args.out / f"egg_{path.stem}.webp"
        image.save(target, "WEBP", quality=QUALITY, method=6)
        size = target.stat().st_size
        total += size
        print(f"{target.name}: {image.width}x{image.height} {size} B")
    print(f"total: {total} B -> {args.out}")


if __name__ == "__main__":
    main()