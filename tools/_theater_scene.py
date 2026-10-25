# SPDX-License-Identifier: MPL-2.0
"""
Checks and helpers shared by the export scripts. Runs inside Blender.

A theater scene has two named objects the apps look up by name:

- ``Screen``: a mesh, the quad the video is drawn on. Its size and pose
  tell the app where the picture goes.
- ``Seat``: an empty at the viewer's position, normally the world origin.

Blender is Z-up with +Y ahead of the seat. Both exporters turn that into
Y-up with -Z ahead, which is the forward axis of glTF, the Meta Spatial
SDK, and RealityKit.
"""

import json
import os
import struct
import sys
import zipfile

import bpy

SCREEN = "Screen"
SEAT = "Seat"


class SceneError(Exception):
    """A problem the person exporting has to fix in the .blend."""


def output_path(allowed_suffixes):
    """The single argument after ``--``: where to write the export."""
    argv = sys.argv
    args = argv[argv.index("--") + 1:] if "--" in argv else []
    if len(args) != 1:
        raise SceneError(
            "expected exactly one argument after '--': the output path "
            "(one of {})".format(", ".join(allowed_suffixes))
        )
    path = os.path.abspath(args[0])
    if not path.lower().endswith(tuple(allowed_suffixes)):
        raise SceneError("the output path must end in {}: {}".format(
            " or ".join(allowed_suffixes), path))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    return path


def check_scene():
    """Fail unless the open scene has the two named objects."""
    scene = bpy.context.scene
    problems = []

    screen = scene.objects.get(SCREEN)
    if screen is None:
        problems.append("no object named {!r} in scene {!r}".format(SCREEN, scene.name))
    elif screen.type != 'MESH':
        problems.append("{!r} must be a mesh, not {}".format(SCREEN, screen.type))

    seat = scene.objects.get(SEAT)
    if seat is None:
        problems.append("no object named {!r} in scene {!r}".format(SEAT, scene.name))
    elif seat.type != 'EMPTY':
        print("warning: {!r} is a {}; an empty is expected".format(SEAT, seat.type))

    if problems:
        raise SceneError("\n".join(problems))

    if any(abs(c) > 1e-6 for c in seat.matrix_world.translation):
        print("warning: {!r} is not at the world origin: {}".format(
            SEAT, tuple(round(c, 3) for c in seat.matrix_world.translation)))

    if bpy.data.filepath and bpy.data.is_dirty:
        print("warning: the .blend has unsaved changes; exporting what is open")

    return screen, seat


def filter_operator_kwargs(operator, kwargs):
    """Drop options this Blender's operator doesn't have, and say so."""
    known = set(operator.get_rna_type().properties.keys())
    kept = {key: value for key, value in kwargs.items() if key in known}
    dropped = sorted(set(kwargs) - known)
    if dropped:
        print("note: this Blender ({}) has no option {}; its defaults apply".format(
            bpy.app.version_string, ", ".join(dropped)))
    return kept


def report_file(path):
    size = os.path.getsize(path)
    print("wrote {} ({:.1f} MB)".format(path, size / 1e6))


def read_glb_json(path):
    """The JSON chunk of a GLB, as a dict."""
    with open(path, "rb") as fh:
        magic, _version, _length = struct.unpack("<4sII", fh.read(12))
        if magic != b"glTF":
            raise SceneError("{} is not a GLB".format(path))
        chunk_length, chunk_type = struct.unpack("<I4s", fh.read(8))
        if chunk_type != b"JSON":
            raise SceneError("{}: first chunk is not JSON".format(path))
        return json.loads(fh.read(chunk_length))


def verify_glb(path):
    """Fail unless the GLB still has the two named nodes; print what's in it."""
    doc = read_glb_json(path)
    names = {node.get("name") for node in doc.get("nodes", [])}
    missing = [name for name in (SCREEN, SEAT) if name not in names]
    if missing:
        raise SceneError("{} lost node(s) {}".format(path, ", ".join(missing)))

    accessors = doc.get("accessors", [])
    triangles = 0
    for mesh in doc.get("meshes", []):
        for primitive in mesh.get("primitives", []):
            mode = primitive.get("mode", 4)
            if mode != 4:
                continue
            if "indices" in primitive:
                triangles += accessors[primitive["indices"]]["count"] // 3
            else:
                triangles += accessors[primitive["attributes"]["POSITION"]]["count"] // 3
    print("nodes {}, meshes {}, materials {}, images {}, triangles {}".format(
        len(doc.get("nodes", [])), len(doc.get("meshes", [])),
        len(doc.get("materials", [])), len(doc.get("images", [])), triangles))
    for node in doc.get("nodes", []):
        if node.get("name") in (SCREEN, SEAT):
            print("  {}: translation {}".format(
                node["name"], node.get("translation", [0.0, 0.0, 0.0])))


def verify_usd(path):
    """Print what a USD export contains (a USDZ's members, or the file)."""
    if path.lower().endswith(".usdz"):
        with zipfile.ZipFile(path) as archive:
            members = archive.namelist()
        print("usdz members: {}".format(", ".join(members)))
        if not any(name.lower().endswith((".usdc", ".usda", ".usd")) for name in members):
            raise SceneError("{} holds no USD layer".format(path))
    elif path.lower().endswith(".usda"):
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        missing = [name for name in (SCREEN, SEAT) if '"{}"'.format(name) not in text]
        if missing:
            raise SceneError("{} lost prim(s) {}".format(path, ", ".join(missing)))


def run(main):
    """Run ``main`` and turn a SceneError into a message and exit code 1."""
    try:
        main()
    except SceneError as error:
        print("error: {}".format(error), file=sys.stderr)
        sys.stdout.flush()
        sys.stderr.flush()
        os._exit(1)
