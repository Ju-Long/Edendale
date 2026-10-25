#!/usr/bin/env python3
"""
Generates the theater's fallback sky (HORIZON.md G.7): an equirectangular
starfield, made in house so it carries the repository's licence, for the
`mesh://skybox` the theater shows when no scene GLB ships.

    python3 tools/horizon/make_skybox.py [out.png]

Writes src/horizon/res/drawable-nodpi/theater_sky.png by default: 2048×1024,
deterministic (seed 20261025), a near-black sky with a faint band of
galaxy and a dark floor below the horizon so nothing washes out the
picture. Needs numpy and Pillow.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

WIDTH, HEIGHT = 2048, 1024
SEED = 20261025


def main(out: Path) -> None:
    rng = np.random.default_rng(SEED)
    v = np.linspace(0.0, 1.0, HEIGHT, dtype=np.float32)[:, None]      # 0 = zenith, 1 = nadir
    u = np.linspace(0.0, 1.0, WIDTH, dtype=np.float32)[None, :]

    # Sky: a very dark blue that fades to black at the zenith; the floor half is
    # darker still, with a faint horizon glow so the room has a sense of ground.
    sky = np.zeros((HEIGHT, WIDTH, 3), dtype=np.float32)
    above = np.clip(0.5 - v, 0.0, 0.5) / 0.5                               # 1 at zenith, 0 at horizon
    below = np.clip(v - 0.5, 0.0, 0.5) / 0.5                               # 1 at nadir
    sky[..., 0] = 0.010 + 0.010 * (1 - above) * (v < 0.5)
    sky[..., 1] = 0.012 + 0.014 * (1 - above) * (v < 0.5)
    sky[..., 2] = 0.020 + 0.030 * (1 - above) * (v < 0.5)
    horizon = np.exp(-((v - 0.5) ** 2) / (2 * 0.012 ** 2))
    sky += horizon[..., None] * np.array([0.045, 0.040, 0.050], dtype=np.float32)
    sky *= (1 - 0.85 * below)[..., None]                                   # the floor

    # A faint, tilted band of galaxy: noise multiplied by a wide gaussian.
    band_center = 0.42 + 0.06 * np.sin(2 * np.pi * u)
    band = np.exp(-((v - band_center) ** 2) / (2 * 0.05 ** 2))
    coarse = (rng.random((HEIGHT // 16, WIDTH // 16)) * 255).astype(np.uint8)
    noise = np.asarray(Image.fromarray(coarse, "L").resize((WIDTH, HEIGHT), Image.BICUBIC), dtype=np.float32) / 255
    sky += (band * (0.015 + 0.030 * noise))[..., None] * np.array([0.9, 0.9, 1.0], dtype=np.float32)

    # Stars, denser in the band, with a little colour temperature spread.
    count = 2600
    xs = rng.random(count) * WIDTH
    ys = rng.normal(0.38, 0.14, count)
    while True:                                                            # resample rather than clip, so no row of stars forms
        outside = (ys < 0.02) | (ys > 0.49)
        if not outside.any():
            break
        ys[outside] = rng.normal(0.38, 0.14, int(outside.sum()))
    ys = ys * HEIGHT
    brightness = rng.power(0.35, count) ** 2
    temperature = rng.random(count)
    for x, y, b, t in zip(xs, ys, brightness, temperature):
        radius = 1.0 + 1.6 * b
        x0, y0 = int(x), int(y)
        span = int(radius * 3) + 1
        yy, xx = np.mgrid[y0 - span : y0 + span + 1, x0 - span : x0 + span + 1]
        yy = np.clip(yy, 0, HEIGHT - 1)
        xx = xx % WIDTH
        blob = np.exp(-(((xx - x) ** 2 + (yy - y) ** 2) / (2 * radius ** 2))) * (0.25 + 0.75 * b)
        colour = np.array([1.0, 0.92 + 0.08 * t, 0.85 + 0.15 * t], dtype=np.float32) if t < 0.5 \
            else np.array([0.85 + 0.15 * (1 - t), 0.92, 1.0], dtype=np.float32)
        sky[yy, xx] += blob[..., None] * colour

    image = (np.clip(sky, 0.0, 1.0) ** (1 / 2.2) * 255).astype(np.uint8)
    out.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(image, "RGB").save(out, optimize=True)
    print(f"wrote {out} ({out.stat().st_size / 1e6:.2f} MB)")


if __name__ == "__main__":
    default = Path(__file__).resolve().parents[2] / "src/horizon/res/drawable-nodpi/theater_sky.png"
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else default)
