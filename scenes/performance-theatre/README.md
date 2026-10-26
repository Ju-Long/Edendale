# Performance theatre

A proscenium playhouse with the screen where the stage is. Red velvet house
curtains are swept open and tied back with gold rope on either side, under a
swagged valance with a gold fringe and tassels. Black masking frames the
picture inside them, and footlights glow along the stage lip. The viewer sits
in the centre seat of the stalls, in curved, gently raked rows of red velvet
seats. Two tiers of opera boxes line the side walls by the stage, a curved
balcony with its lamps hangs behind, and a painted dome with a chandelier
rises overhead.

| | |
|---|---|
| Viewer | `Seat`, on the floor under the centre seat of row 9 of 14 (8 rows in front, 5 behind) |
| Screen | `Screen`, 11.0 × 6.19 m, 14 m ahead, its centre 3.09 m up: 43° wide, 7.7° above eye level |
| Room | 21 m wide; 17.2 m from the back wall to the proscenium, the stage beyond; 12.5 m to the ceiling, the dome 1.8 m higher; rows 0.95 m apart and 0.15 m above the one in front |
| Seating | 384 seats, about 27 a row in an 11-seat centre block and two side blocks, on arcs centred 24 m ahead |
| Around the screen | black legs and border close around the picture, then the red drapes and the gilt proscenium |
| Exported meshes | `Room` (21,218 triangles, `textures/room.jpg`, 4096²), `Drapes` (25,336, `textures/drapes.jpg`, 2048²), `Seats` (102,876, `textures/seats.jpg`, 4096²) |

The heaviest of the three scenes, mostly in the seats. If the headset needs
it, the side blocks are the place to trim.

## Source

`performance-theatre.blend` keeps the editable scene in the `Source`
collection, excluded from the view layer so the exporters skip it: the
modelled pieces with procedural materials (each tagged with a `bake_group`)
and the lights under `Bake Lights`. The `Theater` collection holds what is
exported: `Seat`, `Screen`, and the three baked meshes, which
`tools/bake_lighting.py` rebuilds from `Source` (see the branch README). The
bake settings are the scene's `bake_settings` property. The stalls floor
carries a low `lightmap_weight`, since the seats hide most of it, and the
proscenium, boxes, and stage a higher one.

## Art and licence

Everything in the scene was made in house for Edendale, in Blender 5.2, from
modelled geometry and procedural materials. No models, textures, or images
come from elsewhere.

The scene, its baked textures, and its exports are licensed under the
[Mozilla Public License 2.0](../../LICENSE).
