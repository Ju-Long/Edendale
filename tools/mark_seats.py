# SPDX-License-Identifier: MPL-2.0
"""
Mark every seat of the open .blend for the apps' seat map, and save it.

From the repository root::

    <Blender> --background scenes/<name>/<name>.blend \
        --python tools/mark_seats.py

Writes an empty ``SeatMap`` at the origin, in the collections that hold
``Seat``, with one child empty per seat named ``Seat_<row>_<number>`` (two
digits each) on the floor under it. Rows count from the front, seats from
the left as the viewer faces the screen. The seats are the ``Seat <row>-...``
and ``Chair <row>-...`` objects in ``Source``. An earlier ``SeatMap`` and the
single-seat markers (``SeatFront`` ...) are replaced. Export the scene again
afterwards.
"""
import bpy, re
from collections import defaultdict

def world(o):
    m = o.matrix_basis.copy()
    p = o.parent
    while p:
        m = p.matrix_basis @ m
        p = p.parent
    return m

for name in ("SeatFront", "SeatMiddle", "SeatBack", "SeatLeft", "SeatRight"):
    ob = bpy.data.objects.get(name)
    if ob:
        bpy.data.objects.remove(ob, do_unlink=True)
old = bpy.data.objects.get("SeatMap")
if old:
    for child in list(old.children):
        bpy.data.objects.remove(child, do_unlink=True)
    bpy.data.objects.remove(old, do_unlink=True)

rows = defaultdict(list)
for o in bpy.data.objects:
    if o.type != 'MESH':
        continue
    m = re.match(r'^(?:Seat|Chair) (\d+)-(.+)$', o.name)
    if m:
        rows[int(m.group(1))].append(tuple(world(o).translation))

seat = bpy.data.objects["Seat"]
seat_map = bpy.data.objects.new("SeatMap", None)
seat_map.empty_display_type = 'PLAIN_AXES'
seat_map.empty_display_size = 0.2
for collection in seat.users_collection:
    collection.objects.link(seat_map)
count = 0
for row in sorted(rows):
    for number, (x, y, z) in enumerate(sorted(rows[row], key=lambda p: p[0]), start=1):
        ob = bpy.data.objects.new("Seat_%02d_%02d" % (row, number), None)
        ob.empty_display_type = 'PLAIN_AXES'
        ob.empty_display_size = 0.1
        for collection in seat.users_collection:
            collection.objects.link(ob)
        ob.parent = seat_map
        ob.location = (round(x, 4), round(y, 4), round(z, 4))
        count += 1
print("SEATMAP rows", len(rows), "seats", count, "per row", [len(rows[r]) for r in sorted(rows)])
bpy.ops.wm.save_mainfile()
print("SAVED", bpy.data.filepath)
