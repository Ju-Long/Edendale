# SPDX-License-Identifier: MPL-2.0
"""
Export the open .blend as USD for the visionOS theater (``apple``).

From the repository root::

    <Blender> --background scenes/<name>/<name>.blend \
        --python tools/export_usd.py -- out/<name>.usdz

``.usdz`` is what the app takes; ``.usda`` (text) and ``.usdc`` are
accepted for inspection. The scene is converted to Y-up with -Z ahead, the
same frame as the GLB, so ``Screen`` and ``Seat`` land in the same place on
both headsets. Modifiers are applied, subdivision surfaces are tessellated,
meshes are triangulated, and no lights or cameras are exported; the export
fails unless ``Screen`` and ``Seat`` are present.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import bpy  # noqa: E402

import _theater_scene as theater  # noqa: E402


def main():
    out = theater.output_path((".usdz", ".usdc", ".usda"))
    theater.check_scene()

    options = dict(
        filepath=out,
        selected_objects_only=False,
        export_animation=False,
        export_hair=False,
        export_uvmaps=True,
        export_normals=True,
        export_materials=True,
        export_textures_mode='NEW',
        overwrite_textures=True,
        relative_paths=True,
        generate_preview_surface=True,
        generate_materialx_network=False,
        convert_orientation=True,
        export_global_forward_selection='NEGATIVE_Z',
        export_global_up_selection='Y',
        evaluation_mode='RENDER',
        export_subdivision='TESSELLATE',
        export_meshes=True,
        export_lights=False,
        export_cameras=False,
        export_curves=False,
        export_points=False,
        export_volumes=False,
        export_armatures=False,
        export_shapekeys=False,
        export_custom_properties=True,
        triangulate_meshes=True,
    )
    bpy.ops.wm.usd_export(**theater.filter_operator_kwargs(bpy.ops.wm.usd_export, options))

    theater.verify_usd(out)
    theater.report_file(out)


theater.run(main)
