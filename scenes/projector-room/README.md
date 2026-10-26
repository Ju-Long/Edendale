# Projector room

A small 1920s picture hall. Rows of wooden chairs on a plank floor, walnut
wainscot under deep-green striped wallpaper, a beamed ceiling, and tall
windows behind closed shutters with moonlight at the seams. A framed screen
stands on a low stage, with an upright piano at the front left. Behind the
viewer, on a raised platform, a silent-era projector on a cast-iron pedestal
throws its beam over their head. Brass sconces with tulip shades and the
lobby's light through the door glass keep the room warm and dim.

| | |
|---|---|
| Viewer | `Seat`, on the floor under the centre chair of row 4 of 6 (nine chairs a row) |
| Screen | `Screen`, 5.6 × 3.15 m, 7.4 m ahead, its centre 2.725 m up: 42° wide from the seat |
| Room | 9 m wide, 13.6 m deep, 4.9 m to the ceiling; a flat floor |
| Projector | lens 1.62 m up, 3.1 m behind the seat; the beam's lower edge clears a seated head by about 0.2 m |
| Exported meshes | `Room` (7,306 triangles, `textures/room.jpg`, 4096²), `Chairs` (23,976, `textures/chairs.jpg`, 2048²), `Props` (projector, platform, film cans, piano: 3,928, `textures/props.jpg`, 2048²), `Projector beam` (8, `textures/beam.png`) |

## The beam

`Projector beam` is not baked. It is a pyramid of four quads from the lens to
the picture's edges, double-sided and alpha-blended: black base colour with
the opacity in `textures/beam.png`'s alpha, and a constant warm emission. Its
opacity falls off away from the lens, about as a beam seen through haze
does, and is gone before it reaches the picture. In glTF it is
`alphaMode: BLEND`, `doubleSided: true`; in USD its `opacity` reads the
texture's alpha. Draw it after the opaque meshes; it looks best without
depth writes.

The chairs are placed by hand, slightly crooked, except the viewer's and its
neighbours. The reels sit in the beam's vertical plane, as on a real
projector, so from the seat they are seen edge-on.

## Source

`projector-room.blend` keeps the editable scene in the `Source` collection,
excluded from the view layer so the exporters skip it: the modelled pieces
with procedural materials (each tagged with a `bake_group`) and the lights
under `Bake Lights`. The `Theater` collection holds what is exported: `Seat`,
`Screen`, the beam, and the three baked meshes, which `tools/bake_lighting.py`
rebuilds from `Source` (see the branch README). The bake settings are the
scene's `bake_settings` property.

## Art and licence

Everything in the scene was made in house for Edendale, in Blender 5.2, from
modelled geometry and procedural materials; the beam's texture is generated.
No models, textures, or images come from elsewhere.

The scene, its baked textures, and its exports are licensed under the
[Mozilla Public License 2.0](../../LICENSE).
