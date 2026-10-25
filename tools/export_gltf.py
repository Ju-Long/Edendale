# SPDX-License-Identifier: MPL-2.0
"""
Export the open .blend as a GLB for the Meta Quest theater (``meta-horizon``).

From the repository root::

    <Blender> --background scenes/<name>/<name>.blend \
        --python tools/export_gltf.py -- out/<name>.glb

Modifiers are applied, lighting is whatever the baked textures hold (no
lights or cameras are exported), and the export fails unless ``Screen``
and ``Seat`` are present and survive the export under those names.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import bpy  # noqa: E402

import _theater_scene as theater  # noqa: E402


def main():
    out = theater.output_path((".glb",))
    theater.check_scene()

    options = dict(
        filepath=out,
        export_format='GLB',
        export_apply=True,
        export_yup=True,
        export_texcoords=True,
        export_normals=True,
        export_materials='EXPORT',
        export_image_format='AUTO',
        export_cameras=False,
        export_lights=False,
        export_animations=False,
        export_skins=False,
        export_morph=False,
        export_extras=True,
        use_selection=False,
        use_visible=True,
    )
    bpy.ops.export_scene.gltf(**theater.filter_operator_kwargs(bpy.ops.export_scene.gltf, options))

    theater.verify_glb(out)
    theater.report_file(out)


theater.run(main)
