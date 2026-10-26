# SPDX-License-Identifier: MPL-2.0
"""
Bake a theater's lighting into textures and rebuild its exported meshes.

From the repository root::

    <Blender> --background scenes/<name>/<name>.blend \
        --python tools/bake_lighting.py [-- <group> ...]

A scene keeps two collections:

- ``Source``: what an artist edits. Meshes with procedural materials, and
  the lights under it. A mesh takes part in the bake when it has a
  ``bake_group`` custom property; ``lightmap_weight`` (optional) scales its
  share of the texture. The collection is excluded from the view layer, so
  neither exporter sees it.
- ``Theater``: what the exporters write. ``Screen``, ``Seat``, anything
  unbaked the artist put there, and one mesh per bake group, which this
  script replaces on every run.

For each group, every member is copied with its modifiers and scale
applied, given a ``Lightmap`` UV packed into one square atlas (nearer the
seat gets more texels), and baked with Cycles three times: diffuse colour,
diffuse light, and emission. The light is denoised, the three are combined
as ``colour * light + emission``, exposed, and written to
``textures/<group>.jpg`` (or ``.png``). The copies are joined into one mesh
with a single emission-only material showing that texture, so the apps draw
the room unlit, one draw call per group.

Settings come from the scene's ``bake_settings`` custom property, a dict::

    {"groups": {"room": {"name": "Room", "size": 4096}, ...},
     "samples": 1024, "exposure": 1.0, "format": "JPEG",
     "near": 3.0, "min_weight": 0.35, "max_weight": 2.5, "margin": 6}

A group may override "size", "name", "format", "exposure", "samples" and
"margin" (in pixels).

Naming groups after ``--`` bakes only those; the other groups' meshes are
kept. The .blend is saved in place.
"""

import math
import os
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import bpy  # noqa: E402
import numpy as np  # noqa: E402
from mathutils import Matrix, Vector  # noqa: E402

import _theater_scene as theater  # noqa: E402

SOURCE = "Source"
THEATER = "Theater"
WORK = "Bake Work"
GENERATED = "baked_from_group"
BAKE_NODE = "BakeTarget"
EYE_HEIGHT = 1.2

DEFAULTS = {
    "groups": {},
    "samples": 1024,
    "exposure": 1.0,
    "format": "JPEG",
    "near": 3.0,
    "min_weight": 0.35,
    "max_weight": 2.5,
    "margin": 6,
    "denoise": True,
}


def settings(scene):
    raw = scene.get("bake_settings")
    if raw is None:
        raise theater.SceneError("scene {!r} has no 'bake_settings' property".format(scene.name))
    cfg = dict(DEFAULTS)
    cfg.update(raw.to_dict() if hasattr(raw, "to_dict") else dict(raw))
    if not cfg["groups"]:
        raise theater.SceneError("bake_settings has no groups")
    return cfg


def requested_groups(cfg):
    argv = sys.argv
    names = argv[argv.index("--") + 1:] if "--" in argv else []
    unknown = [n for n in names if n not in cfg["groups"]]
    if unknown:
        raise theater.SceneError("unknown bake group(s) {}; the scene has {}".format(
            ", ".join(unknown), ", ".join(sorted(cfg["groups"]))))
    return names or list(cfg["groups"])


def layer_collection(name, root=None):
    root = root or bpy.context.view_layer.layer_collection
    if root.name == name:
        return root
    for child in root.children:
        found = layer_collection(name, child)
        if found:
            return found
    return None


def select_only(objects, active):
    for ob in bpy.context.view_layer.objects:
        ob.select_set(False)
    for ob in objects:
        ob.select_set(True)
    bpy.context.view_layer.objects.active = active


def eye_point():
    seat = bpy.context.scene.objects.get(theater.SEAT)
    origin = seat.matrix_world.translation if seat else Vector((0, 0, 0))
    return origin + Vector((0, 0, EYE_HEIGHT))


def distance_to_box(ob, point):
    corners = [ob.matrix_world @ Vector(c) for c in ob.bound_box]
    lo = Vector([min(c[i] for c in corners) for i in range(3)])
    hi = Vector([max(c[i] for c in corners) for i in range(3)])
    nearest = Vector([min(max(point[i], lo[i]), hi[i]) for i in range(3)])
    return (nearest - point).length


def weight_for(ob, cfg, eye):
    if "lightmap_weight" in ob:
        return float(ob["lightmap_weight"])
    d = max(distance_to_box(ob, eye), 0.05)
    return min(max(cfg["near"] / d, cfg["min_weight"]), cfg["max_weight"])


# ---------------------------------------------------------------- copies

def working_copies(objects, work, cfg, eye, fallback):
    depsgraph = bpy.context.evaluated_depsgraph_get()
    copies = []
    for ob in objects:
        me = bpy.data.meshes.new_from_object(ob.evaluated_get(depsgraph), preserve_all_data_layers=True,
                                             depsgraph=depsgraph)
        if not me.polygons:
            bpy.data.meshes.remove(me)
            continue
        loc, rot, scale = ob.matrix_world.decompose()
        me.transform(Matrix.Diagonal(scale).to_4x4())
        if scale.x * scale.y * scale.z < 0:
            me.flip_normals()
        if not me.materials:
            me.materials.append(fallback)
        copy = bpy.data.objects.new(ob.name + ".bake", me)
        copy.matrix_world = Matrix.LocRotScale(loc, rot, None)
        copy["bake_group"] = ob["bake_group"]
        copy["lightmap_weight"] = weight_for(ob, cfg, eye)
        work.objects.link(copy)
        copies.append(copy)
    return copies


def uv_areas(me, layer):
    """Per-object (3D area, UV area) of a mesh's polygons."""
    n_loops = len(me.loops)
    uv = np.empty(n_loops * 2, dtype=np.float64)
    me.uv_layers[layer].data.foreach_get("uv", uv)
    uv = uv.reshape(-1, 2)
    starts = np.empty(len(me.polygons), dtype=np.int64)
    totals = np.empty(len(me.polygons), dtype=np.int64)
    me.polygons.foreach_get("loop_start", starts)
    me.polygons.foreach_get("loop_total", totals)
    nxt = np.arange(n_loops) + 1
    ends = starts + totals
    nxt[ends - 1] = starts
    cross = uv[:, 0] * uv[nxt, 1] - uv[nxt, 0] * uv[:, 1]
    uv_area = np.abs(np.add.reduceat(cross, starts)).sum() * 0.5
    areas = np.empty(len(me.polygons), dtype=np.float64)
    me.polygons.foreach_get("area", areas)
    return float(areas.sum()), float(uv_area), uv


def unwrap_and_join(copies, size, margin_px, name):
    """One mesh for the group, with a ``Lightmap`` UV atlas.

    Island area is proportional to 3D area x weight², so what is near the
    seat gets more texels. Cycles bakes object by object, so the copies are
    joined first: one bake job per group instead of one per object.
    """
    for copy in copies:
        layer = copy.data.uv_layers.new(name="Lightmap")
        copy.data.uv_layers.active = layer

    scene = bpy.context.scene
    scene.tool_settings.use_uv_select_sync = True
    select_only(copies, copies[0])
    bpy.ops.object.mode_set(mode='EDIT')
    bpy.ops.mesh.select_mode(type='FACE')
    bpy.ops.mesh.select_all(action='SELECT')
    bpy.ops.uv.smart_project(angle_limit=math.radians(66), island_margin=0.0, area_weight=0.0,
                             correct_aspect=True, scale_to_bounds=False)
    bpy.ops.object.mode_set(mode='OBJECT')

    weighted = 0.0
    for copy in copies:
        area, uv_area, uv = uv_areas(copy.data, "Lightmap")
        if uv_area <= 0 or area <= 0:
            continue
        weight = copy["lightmap_weight"]
        weighted += area * weight * weight
        copy.data.uv_layers["Lightmap"].data.foreach_set("uv", (uv * weight * math.sqrt(area / uv_area)).ravel())

    select_only(copies, copies[0])
    bpy.ops.object.join()
    joined = copies[0]
    joined.data.transform(joined.matrix_world)
    joined.matrix_world = Matrix()
    joined.name = name
    joined.data.uv_layers.active = joined.data.uv_layers["Lightmap"]

    select_only([joined], joined)
    bpy.ops.object.mode_set(mode='EDIT')
    bpy.ops.mesh.select_all(action='SELECT')
    bpy.ops.uv.pack_islands(udim_source='CLOSEST_UDIM', rotate=True, rotate_method='ANY', scale=True,
                            merge_overlap=False, margin_method='FRACTION', margin=margin_px / size,
                            shape_method='CONCAVE')
    bpy.ops.object.mode_set(mode='OBJECT')

    _, uv_area, _ = uv_areas(joined.data, "Lightmap")
    print("  lightmap: {:.0f} texels/m at weight 1, {:.0%} of the atlas used, {} objects".format(
        math.sqrt(uv_area / weighted) * size if weighted else 0.0, uv_area, len(copies)))
    return joined


# ---------------------------------------------------------------- bake

def float_image(name, size):
    img = bpy.data.images.new(name, size, size, alpha=False, float_buffer=True)
    return img


def pixels(img):
    arr = np.empty(img.size[0] * img.size[1] * 4, dtype=np.float32)
    img.pixels.foreach_get(arr)
    return arr.reshape(img.size[1], img.size[0], 4)


def point_targets(materials, img):
    for mat in materials:
        nt = mat.node_tree
        node = nt.nodes.get(BAKE_NODE)
        if node is None:
            node = nt.nodes.new("ShaderNodeTexImage")
            node.name = BAKE_NODE
            node.label = "Bake target (tools/bake_lighting.py)"
            node.location = (-600, -500)
        node.image = img
        for other in nt.nodes:
            other.select = False
        node.select = True
        nt.nodes.active = node


def remove_targets(materials):
    for mat in materials:
        node = mat.node_tree.nodes.get(BAKE_NODE)
        if node:
            mat.node_tree.nodes.remove(node)


def run_bake(kind, passes, samples, margin_px):
    scene = bpy.context.scene
    scene.cycles.samples = samples
    started = time.time()
    bpy.ops.object.bake(type=kind, pass_filter=passes, margin=margin_px, margin_type='EXTEND',
                        use_clear=True, uv_layer="Lightmap", target='IMAGE_TEXTURES',
                        use_selected_to_active=False)
    print("  baked {} {} at {} samples in {:.0f} s".format(kind, "+".join(sorted(passes)), samples,
                                                          time.time() - started))


def denoise(light):
    """OpenImageDenoise on a baked light image, through a throwaway compositor scene."""
    h, w, _ = light.shape
    tmp_dir = tempfile.mkdtemp(prefix="edendale-bake-")
    out_path = os.path.join(tmp_dir, "denoised.exr")
    scene = bpy.data.scenes.new("Denoise (temporary)")
    camera = bpy.data.objects.new("Denoise camera", bpy.data.cameras.new("Denoise camera"))
    scene.collection.objects.link(camera)
    scene.camera = camera
    scene.render.engine = 'BLENDER_WORKBENCH'
    scene.render.resolution_x, scene.render.resolution_y = w, h
    scene.render.resolution_percentage = 100
    scene.render.use_compositing = True
    scene.render.image_settings.file_format = 'OPEN_EXR'
    scene.render.image_settings.color_depth = '32'
    scene.view_settings.view_transform = 'Standard'

    source = bpy.data.images.new("Denoise input", w, h, alpha=False, float_buffer=True)
    source.pixels.foreach_set(light.ravel())
    tree = bpy.data.node_groups.new("Denoise", "CompositorNodeTree")
    tree.interface.new_socket("Image", in_out='OUTPUT', socket_type='NodeSocketColor')
    image_node = tree.nodes.new("CompositorNodeImage")
    image_node.image = source
    denoise_node = tree.nodes.new("CompositorNodeDenoise")
    denoise_node.inputs["HDR"].default_value = True
    output = tree.nodes.new("NodeGroupOutput")
    tree.links.new(image_node.outputs["Image"], denoise_node.inputs["Image"])
    tree.links.new(denoise_node.outputs["Image"], output.inputs[0])
    scene.compositing_node_group = tree

    started = time.time()
    bpy.ops.render.render(scene=scene.name)
    bpy.data.images["Render Result"].save_render(out_path, scene=scene)
    result = bpy.data.images.load(out_path)
    clean = pixels(result).copy()
    if clean.shape != light.shape:
        raise theater.SceneError("the denoiser returned {} for a {} image".format(clean.shape, light.shape))
    print("  denoised in {:.0f} s (mean change {:.1%})".format(
        time.time() - started,
        float(np.abs(clean[..., :3] - light[..., :3]).mean() / max(float(light[..., :3].mean()), 1e-9))))

    for img in (result, source):
        bpy.data.images.remove(img)
    bpy.data.node_groups.remove(tree)
    bpy.data.objects.remove(camera)
    bpy.data.scenes.remove(scene)
    os.remove(out_path)
    os.rmdir(tmp_dir)
    return clean


def combine(albedo, light, emit, exposure):
    """colour * light + emission, exposed, with a soft shoulder, as sRGB bytes."""
    lin = (albedo[..., :3] * np.maximum(light[..., :3], 0.0) + np.maximum(emit[..., :3], 0.0)) * exposure
    knee = 0.8
    over = np.maximum(lin - knee, 0.0)
    lin = np.where(lin > knee, knee + (1.0 - knee) * (1.0 - np.exp(-over / (1.0 - knee))), lin)
    lin = np.clip(lin, 0.0, 1.0)
    srgb = np.where(lin <= 0.0031308, lin * 12.92, 1.055 * np.power(lin, 1.0 / 2.4) - 0.055)
    rng = np.random.default_rng(7)
    srgb = srgb + (rng.random(srgb.shape, dtype=np.float32) - 0.5) / 255.0
    out = np.ones(albedo.shape, dtype=np.float32)
    out[..., :3] = np.clip(np.round(srgb * 255.0) / 255.0, 0.0, 1.0)
    return out


def write_texture(name, data, path, fmt):
    h, w, _ = data.shape
    img = bpy.data.images.get(name)
    if img is not None:
        bpy.data.images.remove(img)
    img = bpy.data.images.new(name, w, h, alpha=False)
    img.pixels.foreach_set(data.ravel())
    img.filepath_raw = path
    img.file_format = fmt
    img.save(filepath=path, quality=92)
    img.filepath = bpy.path.relpath(path)
    img.source = 'FILE'
    img.reload()
    img.colorspace_settings.name = 'sRGB'
    return img


def baked_material(name, image):
    mat = bpy.data.materials.get(name)
    if mat is not None:
        bpy.data.materials.remove(mat)
    mat = bpy.data.materials.new(name)
    mat.use_backface_culling = True  # a room is seen from inside; glTF gets doubleSided false
    nt = mat.node_tree
    for node in list(nt.nodes):
        nt.nodes.remove(node)
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    out.location = (300, 0)
    bsdf = nt.nodes.new("ShaderNodeBsdfPrincipled")
    bsdf.inputs["Base Color"].default_value = (0, 0, 0, 1)
    bsdf.inputs["Roughness"].default_value = 1.0
    bsdf.inputs["Emission Strength"].default_value = 1.0
    tex = nt.nodes.new("ShaderNodeTexImage")
    tex.image = image
    tex.interpolation = 'Linear'
    tex.location = (-400, 0)
    nt.links.new(tex.outputs["Color"], bsdf.inputs["Emission Color"])
    nt.links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])
    return mat


def finish(ob, name, material, collection, group):
    """The baked group as the exporters will see it: one UV map, one material."""
    me = ob.data
    for layer in [l for l in me.uv_layers if l.name != "Lightmap"]:
        me.uv_layers.remove(layer)
    me.uv_layers["Lightmap"].name = "UVMap"
    me.materials.clear()
    me.materials.append(material)
    me.polygons.foreach_set("material_index", np.zeros(len(me.polygons), dtype=np.int32))
    for key in list(ob.keys()):
        del ob[key]
    ob[GENERATED] = group
    ob.name = name
    me.name = name
    for coll in list(ob.users_collection):
        coll.objects.unlink(ob)
    collection.objects.link(ob)
    print("  {}: {} triangles".format(name, sum(len(p.vertices) - 2 for p in me.polygons)))
    return ob


# ---------------------------------------------------------------- main

def main():
    scene = bpy.context.scene
    cfg = settings(scene)
    groups = requested_groups(cfg)
    if not bpy.data.filepath:
        raise theater.SceneError("open a saved .blend; textures go next to it")
    source = bpy.data.collections.get(SOURCE)
    target = bpy.data.collections.get(THEATER)
    if source is None or target is None:
        raise theater.SceneError("the scene needs a {!r} and a {!r} collection".format(SOURCE, THEATER))
    theater.check_scene()
    textures = os.path.join(os.path.dirname(bpy.data.filepath), "textures")
    os.makedirs(textures, exist_ok=True)

    scene.render.engine = 'CYCLES'
    scene.cycles.device = 'GPU'
    scene.cycles.use_denoising = False
    scene.cycles.max_bounces = 8
    scene.cycles.diffuse_bounces = 5
    scene.cycles.glossy_bounces = 2
    scene.cycles.transparent_max_bounces = 8
    scene.cycles.sample_clamp_indirect = 4.0
    scene.cycles.use_light_tree = True

    layer_collection(SOURCE).exclude = False
    layer_collection(THEATER).exclude = True
    work = bpy.data.collections.new(WORK)
    scene.collection.children.link(work)

    for ob in [o for o in target.objects if GENERATED in o and o[GENERATED] in groups]:
        me = ob.data
        bpy.data.objects.remove(ob)
        if me.users == 0:
            bpy.data.meshes.remove(me)

    members = [o for o in source.all_objects if o.type == 'MESH' and "bake_group" in o]
    hidden = [o for o in members if o.hide_render]
    unknown = sorted({o["bake_group"] for o in members} - set(cfg["groups"]))
    if unknown:
        raise theater.SceneError("objects name bake group(s) not in bake_settings: {}".format(", ".join(unknown)))

    fallback = bpy.data.materials.get("Bake fallback") or bpy.data.materials.new("Bake fallback")
    eye = eye_point()
    live = [o for o in members if not o.hide_render]
    copies = working_copies(live, work, cfg, eye, fallback)
    for ob in live:
        ob.hide_render = True  # the copies stand in for them while baking

    joined = {}
    baked = []  # finished only after every group is baked: each bake sees all the geometry
    by_group = {group: [c for c in copies if c["bake_group"] == group] for group in groups}
    try:
        for group in groups:
            gcfg = cfg["groups"][group]
            mine = by_group[group]
            if not mine:
                print("group {}: no objects, skipped".format(group))
                continue
            size = int(gcfg.get("size", 2048))
            print("group {}: unwrapping {} objects into {}x{}".format(group, len(mine), size, size))
            margin = int(gcfg.get("margin", cfg["margin"]))
            joined[group] = unwrap_and_join(mine, size, margin, "{} (baking)".format(group))

        for group, ob in joined.items():
            gcfg = cfg["groups"][group]
            size = int(gcfg.get("size", 2048))
            name = gcfg.get("name", group.title())
            fmt = gcfg.get("format", cfg["format"]).upper()
            exposure = float(gcfg.get("exposure", cfg["exposure"]))
            samples = int(gcfg.get("samples", cfg["samples"]))
            print("group {} -> {}".format(group, name))
            started = time.time()
            materials = {slot.material for slot in ob.material_slots if slot.material}

            results = {}
            for key, kind, passes, n in (
                    ("albedo", 'DIFFUSE', {'COLOR'}, 16),
                    ("light", 'DIFFUSE', {'DIRECT', 'INDIRECT'}, samples),
                    ("emit", 'EMIT', {'EMIT'}, 16)):
                img = float_image("bake_{}_{}".format(group, key), size)
                point_targets(materials, img)
                select_only([ob], ob)
                run_bake(kind, passes, n, int(gcfg.get("margin", cfg["margin"])))
                results[key] = pixels(img).copy()
                bpy.data.images.remove(img)
            remove_targets(materials)

            light = denoise(results["light"]) if cfg["denoise"] else results["light"]
            final = combine(results["albedo"], light, results["emit"], exposure)
            ext = ".jpg" if fmt == "JPEG" else ".png"
            path = os.path.join(textures, group + ext)
            for stale in (os.path.join(textures, group + e) for e in (".png", ".jpg")):
                if stale != path and os.path.exists(stale):
                    os.remove(stale)
            image = write_texture(group + ext, final, path, fmt)
            baked.append((ob, name, baked_material(name + "Baked", image), group))
            print("  wrote {} ({:.1f} MB) in {:.0f} s".format(
                bpy.path.relpath(path), os.path.getsize(path) / 1e6, time.time() - started))
        for ob, name, material, group in baked:
            finish(ob, name, material, target, group)
    finally:
        for ob in live:
            ob.hide_render = ob in hidden
        for ob in list(work.objects):
            me = ob.data
            bpy.data.objects.remove(ob)
            if me and me.users == 0:
                bpy.data.meshes.remove(me)
        bpy.data.collections.remove(work)
        layer_collection(SOURCE).exclude = True
        layer_collection(THEATER).exclude = False

    bpy.ops.wm.save_mainfile()
    print("saved {}".format(bpy.data.filepath))


theater.run(main)
