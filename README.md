# Edendale — scenes

Edendale is a free, open-source video player and personal watch/review tracker.
It plays a user's own movies and shows, enriches them with TMDB metadata, and
keeps library and watch data under the user's control.

This branch holds the sources of the immersive theater's environments: the
rooms a viewer sits in while a video plays on a large screen, on Meta Quest
headsets (the `meta-horizon` branch) and on Apple Vision Pro (the `apple`
branch). It is data, plus the scripts that export it. There is no application
code, build, test suite, CI, or credential here, and no build or CI of another
branch ever checks this branch out.

The universal documents (`AGENTS.md`, `CLAUDE.md`, `DESIGN.md`, `MODEL.md`) are
`main`'s. This README replaces `main`'s.

## Layout

| Path | What it is |
|---|---|
| `scenes/<name>/<name>.blend` | One scene per folder, saved with Blender 5.2 LTS |
| `scenes/<name>/textures/` | The scene's baked textures, referenced from the `.blend` by relative path |
| `scenes/<name>/README.md` | What the scene is, who made the art, and under which licence |
| `tools/bake_lighting.py` | Headless bake of a scene's lighting into its textures and exported meshes |
| `tools/export_gltf.py` | Headless export to GLB, for `meta-horizon` |
| `tools/export_usd.py` | Headless export to USDZ, for `apple` |
| `tools/_theater_scene.py` | The checks and helpers both scripts share |
| `out/` | Export output; ignored by git |

## Authoring rules

Every scene is made in Blender, not in a game engine, so the apps gain no
second runtime: the Meta Spatial SDK loads GLB directly and RealityKit loads
USDZ directly.

- **Scale and frame.** Real-world metres, Z up, as Blender has it. The viewer
  sits at the world origin and looks along +Y. The exporters turn that into Y
  up with -Z ahead, which is the forward axis of glTF, the Spatial SDK, and
  RealityKit, so the apps rotate nothing.
- **Two named objects.** `Seat`, an empty at the origin, and `Screen`, a mesh
  quad facing the seat and sized for a 16:9 picture. The Quest 3 spike used a
  screen 4 m wide, 5 m ahead, with its centre 1.5 m up. The apps find both by
  name and place the video surface and the viewer from them; an export fails
  if either is missing.
- **Lighting is baked.** Bake with Cycles to textures saved under `textures/`,
  and give each material its baked image as base colour or emission. The
  theaters have no real-time lights; lights and cameras are not exported.
  `tools/bake_lighting.py` does this for a scene laid out as below, and
  writes emission-only materials (black base colour, the baked image as
  emission, back faces culled), so a headset's environment lighting adds
  next to nothing.
- **Source and Theater.** The editable scene lives in a `Source` collection,
  excluded from the view layer so the exporters skip it: meshes with
  procedural materials, each tagged with a `bake_group` custom property, and
  the bake lights. A `Theater` collection holds what is exported: `Screen`,
  `Seat`, one baked mesh per group, and anything deliberately unbaked.
- **Dark around the screen.** Keep the surfaces near the screen dark and
  neutral so they don't wash out the picture.
- **Textures.** Power-of-two sizes, PNG or JPEG. Compressing them for a
  headset (ASTC, KTX2) is the platform branch's business.
- **Keep it cheap.** Few draw calls, modest triangle counts, no avoidable
  overdraw. A baked group is one mesh with one material, so one draw call.
  Transparency only where it earns its place, in its own unbaked mesh. The
  Quest 3 spike spent 2.5 ms of an 11.1 ms frame at 90 fps on a skybox and
  one quad; a room spends the rest. Measure on the device.
- **Licence of the art.** Made in house, or under a licence that fits the
  repository (the scripts are MPL-2.0; see `LICENSE`). Each scene's README
  names the author and the licence of every piece.

## Baking

From the repository root, with Blender 5.2 LTS. On macOS the binary is
`/Applications/Blender.app/Contents/MacOS/Blender`. Use the GPU: run it
without `--factory-startup`, so Cycles keeps the Metal (or CUDA, OptiX)
device from the preferences.

```bash
Blender --background scenes/<name>/<name>.blend --python tools/bake_lighting.py
```

Naming groups after `--` (`-- seats`) rebakes only those. For each group the
script copies the members with modifiers and scale applied, packs them into
one square lightmap atlas (objects nearer the seat get more texels), and
bakes diffuse colour, diffuse light, and emission with Cycles. It denoises
the light with OpenImageDenoise, writes `colour × light + emission`, exposed
and gently compressed above 0.8, to `textures/<group>.jpg` (or `.png`), and
joins the group into one mesh in `Theater`. It saves the `.blend` in place.

The scene's `bake_settings` custom property sets the groups (name, atlas
size, and optionally format, exposure, samples, and margin) and the defaults
(1024 samples, JPEG, exposure 1.0). Its docstring lists every key. On an
Apple M4 Max a scene takes two to three minutes.

## Exporting

From the repository root, with Blender 5.2 LTS.

```bash
Blender --background scenes/<name>/<name>.blend --python tools/export_gltf.py -- out/<name>.glb
```

```bash
Blender --background scenes/<name>/<name>.blend --python tools/export_usd.py -- out/<name>.usdz
```

Both scripts:

- fail with exit code 1 and a message, writing nothing, when `Screen` or
  `Seat` is missing from the scene or the output path has the wrong
  extension;
- apply modifiers (the USD export also tessellates subdivision surfaces and
  triangulates), export the baked textures with the file, and skip lights,
  cameras, and animation;
- print what the export holds: node, image, and triangle counts for the GLB,
  the archive's members for the USDZ.

The GLB has the converted (Y-up) coordinates on its nodes. The USD export
keeps Blender's coordinates on every prim and puts the conversion on the
`/root` prim as a rotation, so an app reads `Screen` and `Seat` through their
world transforms, not their local translation. `tools/export_usd.py` also
writes `.usda` (text) or `.usdc` when the output path says so, for inspection.

Both scripts were checked with Blender 5.2.3 LTS. Options a different Blender
lacks are dropped with a note, and its defaults apply.

## Taking an export into a platform branch

The platform branch commits the export only, in its own tree:

- `meta-horizon`: `src/horizon/assets/<name>.glb`;
- `apple`: wherever its visionOS target keeps USDZ resources.

The commit message names the `scenes` commit the file was exported from
(`git rev-parse --short HEAD` here), so an export can be traced and
reproduced. Nothing in a platform build or CI checks this branch out
(AGENTS.md, hard constraints 1 and 2).

## Size

Commit `.blend` files and textures as plain files. Before adding a file that
passes about 50 MB, raise Git LFS with the owner; GitHub refuses files over
100 MB. Blender's `.blend1` backups are ignored.

## Scenes

Each scene's folder carries a README describing it and naming its art and
licence. All three were made in house and are MPL-2.0.

| Scene | What it is | Screen from the seat | Triangles | GLB |
|---|---|---|---|---|
| [`movie-theatre`](scenes/movie-theatre/README.md) | A stadium-seated cinema, the viewer in its centre seat | 12 m wide, 14 m ahead (46°) | 93,322 | 7.2 MB |
| [`projector-room`](scenes/projector-room/README.md) | A 1920s picture hall: wooden chairs, a reel projector behind, its beam overhead | 5.6 m wide, 7.4 m ahead (42°) | 35,220 | 4.1 MB |
| [`performance-theatre`](scenes/performance-theatre/README.md) | A proscenium playhouse, red curtains tied back around the screen on the stage | 11 m wide, 14 m ahead (43°) | 149,432 | 11.4 MB |

All three are lit as if the film has started. Every baked material carries
its light as emission, so an app can dim a room further by scaling the
emission down.

## License

Edendale is licensed under the [Mozilla Public License 2.0](LICENSE). The
licence of each scene's art is stated in that scene's README.
