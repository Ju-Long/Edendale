# Movie theatre

A modern stadium-seated cinema with the viewer in its centre seat: row 6 of
11, seat 10 of 19, so there are as many rows in front as behind and as many
seats to each side. The lights are down as if the film has started: warm wall
sconces wash up the side walls, dim downlights catch the seat backs, aisle
step lights and exit signs glow, and the screen's own light falls on the front
rows.

| | |
|---|---|
| Viewer | `Seat`, on the floor under the centre seat |
| Screen | `Screen`, 12.0 × 6.75 m, 14 m ahead, its centre 2.4 m up: 46° wide from the seat |
| Room | 16 m wide, 21.5 m deep, 7.6 m from the seat's floor to the ceiling; rows 1.15 m apart, each 0.4 m above the one in front |
| Around the screen | black velvet masking and front wall, a black stage; the side walls are black for the last 2.8 m before the screen |
| Exported meshes | `Room` (5,716 triangles, `textures/room.jpg`, 4096²) and `Seats` (87,604 triangles, `textures/seats.jpg`, 4096²) |

## Source

`movie-theatre.blend` keeps the editable scene in the `Source` collection,
excluded from the view layer so the exporters skip it: the modelled pieces
with procedural materials (each tagged with a `bake_group`) and the lights
under `Bake Lights`. The `Theater` collection holds what is exported: `Seat`,
`Screen`, and the two baked meshes, which `tools/bake_lighting.py` rebuilds
from `Source` (see the branch README). The bake settings are the scene's
`bake_settings` property.

## Art and licence

Everything in the scene was made in house for Edendale, in Blender 5.2, from
modelled geometry and procedural materials. No models, textures, or images
come from elsewhere. The EXIT lettering is Blender's built-in text font
(Inter, SIL Open Font License 1.1), converted to mesh outlines.

The scene, its baked textures, and its exports are licensed under the
[Mozilla Public License 2.0](../../LICENSE).
