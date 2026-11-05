#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""
Prepares the `scenes` branch's GLB exports for the Quest theater (HORIZON.md
G.7, G.14): every material becomes unlit, so the Spatial SDK draws each baked
texture as it is and spends no lighting on the room.

    python3 -I tools/horizon/prepare_theater_glb.py out/movie-theatre.glb ... \
        [--out-dir src/horizonTheater/assets/theater]

`tools/bake_lighting.py` on `scenes` writes emission-only materials: a black
base colour and the baked image as emission. An unlit shader draws the base
colour, so that would be black. This rewrites each material, and nothing
else, to the glTF-standard unlit form (`KHR_materials_unlit`), which the
Spatial SDK's glTF loader reads into its PBR shader's unlit variant:

- a baked material: the emission texture becomes the base colour texture,
  and the emission factor (times `KHR_materials_emissive_strength`) the base
  colour factor;
- a glow masked by a texture's alpha (the projector beam: a black PNG whose
  alpha shapes the haze, tinted by the emission colour): the PNG's colour is
  made white, keeping its alpha, and the emission colour becomes the base
  colour factor; blending and double-sidedness stay as authored;
- a flat glow (the screen's dark surface): the emission colour becomes the
  base colour.

Geometry, node names and transforms, samplers, and the other images are
copied byte for byte, so `Screen` and `Seat` are where the export put them.
Standard library only, and deterministic: the same input gives the same
bytes. Fails (exit 1, nothing written) on a GLB it doesn't understand.
"""

import argparse
import json
import struct
import sys
import zlib
from pathlib import Path

GLB_MAGIC = b"glTF"
CHUNK_JSON = b"JSON"
CHUNK_BIN = b"BIN\x00"
UNLIT = "KHR_materials_unlit"
EMISSIVE_STRENGTH = "KHR_materials_emissive_strength"
REQUIRED_NODES = ("Screen", "Seat")
DEFAULT_OUT_DIR = Path(__file__).resolve().parents[2] / "src" / "horizonTheater" / "assets" / "theater"


class PrepareError(Exception):
    """The GLB isn't one this tool knows how to prepare."""


# ----------------------------------------------------------------------
# GLB container
# ----------------------------------------------------------------------

def read_glb(data: bytes):
    if len(data) < 20 or data[:4] != GLB_MAGIC:
        raise PrepareError("not a GLB")
    version, length = struct.unpack_from("<II", data, 4)
    if version != 2 or length != len(data):
        raise PrepareError("GLB version {} with length {} (file is {} bytes)".format(version, length, len(data)))
    offset = 12
    document = None
    binary = b""
    while offset < len(data):
        chunk_length, chunk_type = struct.unpack_from("<I4s", data, offset)
        body = data[offset + 8:offset + 8 + chunk_length]
        if chunk_type == CHUNK_JSON and document is None:
            document = json.loads(body.decode("utf-8"))
        elif chunk_type == CHUNK_BIN and not binary:
            binary = body
        offset += 8 + chunk_length
    if document is None:
        raise PrepareError("no JSON chunk")
    return document, binary


def write_glb(document, binary: bytes) -> bytes:
    text = json.dumps(document, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    text += b" " * (-len(text) % 4)
    binary += b"\x00" * (-len(binary) % 4)
    chunks = struct.pack("<I4s", len(text), CHUNK_JSON) + text
    if binary:
        chunks += struct.pack("<I4s", len(binary), CHUNK_BIN) + binary
    return GLB_MAGIC + struct.pack("<II", 2, 12 + len(chunks)) + chunks


def view_bytes(document, binary: bytes, index: int) -> bytes:
    view = document["bufferViews"][index]
    if view.get("buffer", 0) != 0:
        raise PrepareError("bufferView {} is outside the GLB's own buffer".format(index))
    start = view.get("byteOffset", 0)
    return binary[start:start + view["byteLength"]]


def repack(document, binary: bytes, replaced: dict) -> bytes:
    """The BIN chunk again, with some buffer views' bytes replaced; views keep their order, 4-byte aligned."""
    out = bytearray()
    for index, view in enumerate(document["bufferViews"]):
        out += b"\x00" * (-len(out) % 4)
        data = replaced.get(index, view_bytes(document, binary, index))
        view["byteOffset"] = len(out)
        view["byteLength"] = len(data)
        out += data
    document["buffers"][0]["byteLength"] = len(out)
    return bytes(out)


# ----------------------------------------------------------------------
# PNG: an RGBA image's colour made white, its alpha kept
# ----------------------------------------------------------------------

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def _png_chunks(data: bytes):
    if not data.startswith(PNG_SIGNATURE):
        raise PrepareError("a masked glow's texture isn't a PNG")
    offset = len(PNG_SIGNATURE)
    while offset < len(data):
        length, kind = struct.unpack_from(">I4s", data, offset)
        yield kind, data[offset + 8:offset + 8 + length]
        offset += 12 + length


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def whiten_png(data: bytes) -> bytes:
    header = None
    compressed = bytearray()
    for kind, body in _png_chunks(data):
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            compressed += body
    if header is None:
        raise PrepareError("PNG without IHDR")
    width, height, depth, colour, _compression, _filter, interlace = header
    if depth != 8 or colour != 6 or interlace != 0:
        raise PrepareError("a masked glow's PNG must be 8-bit RGBA, not interlaced (depth {}, colour type {})".format(depth, colour))
    raw = zlib.decompress(bytes(compressed))
    stride = width * 4
    previous = bytearray(stride)
    rows = bytearray()
    position = 0
    for _ in range(height):
        kind = raw[position]
        line = bytearray(raw[position + 1:position + 1 + stride])
        position += 1 + stride
        for x in range(stride):
            left = line[x - 4] if x >= 4 else 0
            up = previous[x]
            corner = previous[x - 4] if x >= 4 else 0
            if kind == 1:
                line[x] = (line[x] + left) & 0xFF
            elif kind == 2:
                line[x] = (line[x] + up) & 0xFF
            elif kind == 3:
                line[x] = (line[x] + (left + up) // 2) & 0xFF
            elif kind == 4:
                line[x] = (line[x] + _paeth(left, up, corner)) & 0xFF
            elif kind != 0:
                raise PrepareError("PNG filter {} is unknown".format(kind))
        previous = line
        white = bytearray(line)
        white[0::4] = b"\xff" * width
        white[1::4] = b"\xff" * width
        white[2::4] = b"\xff" * width
        rows += b"\x00" + white

    def chunk(kind: bytes, body: bytes) -> bytes:
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    return (
        PNG_SIGNATURE
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(bytes(rows), 9))
        + chunk(b"IEND", b"")
    )


# ----------------------------------------------------------------------
# Materials
# ----------------------------------------------------------------------

def emission_colour(material):
    factor = list(material.get("emissiveFactor", [0.0, 0.0, 0.0]))
    strength = material.get("extensions", {}).get(EMISSIVE_STRENGTH, {}).get("emissiveStrength", 1.0)
    colour = [component * strength for component in factor]
    if any(component > 1.0 for component in colour):
        print("  note: {!r} glows brighter than 1.0 ({}); unlit output clips it".format(
            material.get("name"), [round(c, 3) for c in colour]))
    return [min(1.0, max(0.0, component)) for component in colour]


def prepare_material(material, document):
    """Rewrites one material in place; returns (kind, texture index whose image must turn white, or None)."""
    name = material.get("name", "?")
    pbr = material.setdefault("pbrMetallicRoughness", {})
    alpha = pbr.get("baseColorFactor", [1.0, 1.0, 1.0, 1.0])[3]
    base_texture = pbr.get("baseColorTexture")
    emissive_texture = material.get("emissiveTexture")
    glow = emission_colour(material)
    whiten = None

    if emissive_texture is not None:
        if base_texture is not None:
            raise PrepareError("{!r} has both a base colour and an emission texture".format(name))
        pbr["baseColorTexture"] = emissive_texture
        pbr["baseColorFactor"] = glow + [alpha]
        kind = "baked"
    elif base_texture is not None and any(glow):
        pbr["baseColorFactor"] = glow + [alpha]
        whiten = base_texture["index"]
        kind = "masked glow"
    elif any(glow):
        pbr["baseColorFactor"] = glow + [alpha]
        kind = "flat glow"
    else:
        kind = "base colour"

    for key in ("emissiveTexture", "emissiveFactor", "normalTexture", "occlusionTexture"):
        material.pop(key, None)
    pbr.pop("metallicRoughnessTexture", None)
    pbr["metallicFactor"] = 0.0
    pbr["roughnessFactor"] = 1.0
    extensions = material.setdefault("extensions", {})
    extensions.pop(EMISSIVE_STRENGTH, None)
    extensions[UNLIT] = {}
    return kind, whiten


def prepare(data: bytes):
    document, binary = read_glb(data)
    names = {node.get("name") for node in document.get("nodes", [])}
    missing = [name for name in REQUIRED_NODES if name not in names]
    if missing:
        raise PrepareError("no node named {}".format(" or ".join(missing)))
    if document.get("extensionsRequired"):
        raise PrepareError("requires extensions {}".format(document["extensionsRequired"]))

    textures = document.get("textures", [])
    whiten_textures = set()
    report = []
    for material in document.get("materials", []):
        kind, whiten = prepare_material(material, document)
        if whiten is not None:
            whiten_textures.add(whiten)
        report.append("{} ({}{})".format(material.get("name", "?"), kind, ", " + material["alphaMode"].lower() if "alphaMode" in material else ""))

    replaced = {}
    for texture_index in sorted(whiten_textures):
        image_index = textures[texture_index]["source"]
        users = [
            m.get("name") for m in document["materials"]
            for slot in [m.get("pbrMetallicRoughness", {}).get("baseColorTexture")]
            if slot is not None and textures[slot["index"]]["source"] == image_index
        ]
        if len(users) != 1:
            raise PrepareError("a masked glow's image is shared by {}".format(users))
        image = document["images"][image_index]
        if image.get("mimeType") != "image/png" or "bufferView" not in image:
            raise PrepareError("a masked glow's image must be an embedded PNG")
        replaced[image["bufferView"]] = whiten_png(view_bytes(document, binary, image["bufferView"]))

    if replaced:
        binary = repack(document, binary, replaced)
    used = document.setdefault("extensionsUsed", [])
    for extension in (EMISSIVE_STRENGTH,):
        if extension in used:
            used.remove(extension)
    if UNLIT not in used:
        used.append(UNLIT)
    return write_glb(document, binary), report


def main(argv) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("inputs", nargs="+", type=Path, help="GLBs exported by tools/export_gltf.py on `scenes`")
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR)
    args = parser.parse_args(argv)
    results = []
    try:
        for path in args.inputs:
            prepared, report = prepare(path.read_bytes())
            results.append((path, prepared, report))
    except (PrepareError, OSError, ValueError) as error:
        print("error: {}: {}".format(path, error), file=sys.stderr)
        return 1
    args.out_dir.mkdir(parents=True, exist_ok=True)
    for path, prepared, report in results:
        out = args.out_dir / path.name
        out.write_bytes(prepared)
        print("{} -> {} ({:.1f} MB)".format(path, out, len(prepared) / 1e6))
        for line in report:
            print("  " + line)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
