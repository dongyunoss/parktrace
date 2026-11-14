"""
Kenney Car Kit (CC0, www.kenney.nl) OBJ -> compact triangle files for the 3D parking view (SceneView).

    python tools/convert_car_models.py <Car Kit "Models/OBJ format" folder> app/src/main/assets/cars

Output per model (little endian):
    b"PTCM", int32 triangleCount,
    per triangle: 9 x float32 (f, r, z of 3 vertices, meters; f = forward, r = right, z = up, CCW = outside),
                  int32 ARGB colour, uint8 flags (1 = body paint)

Each face gets the colour of the texture palette (colormap.png, 8 x 4 gradient swatches) at its UV centroid.
Body paint faces (the swatch covering most of the body) store only their brightness (grey, 128 = average)
so the app can recolour every car. The car is scaled to 4.5 m, centred, wheels on z = 0, front = +f.
"""
import os
import struct
import sys
import zlib

MODELS = ["sedan", "suv", "hatchback-sports", "van", "suv-luxury", "sedan-sports"]
TARGET_LENGTH = 4.5


def read_png(path):
    """Minimal PNG reader: 8-bit RGB/RGBA, not interlaced (enough for Kenney's colormap)."""
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    pos, idat = 8, b""
    w = h = bpp = 0
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos + 4])[0]
        typ = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            w, h, depth, ctype, _, _, inter = struct.unpack(">IIBBBBB", body)
            assert depth == 8 and ctype in (2, 6) and inter == 0, (depth, ctype, inter)
            bpp = 3 if ctype == 2 else 4
        elif typ == b"IDAT":
            idat += body
        pos += 12 + ln
    raw = zlib.decompress(idat)
    stride = w * bpp
    rows, prev, i = [], bytearray(stride), 0
    for _ in range(h):
        ftype = raw[i]; i += 1
        line = bytearray(raw[i:i + stride]); i += stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if ftype == 1: line[x] = (line[x] + a) & 255
            elif ftype == 2: line[x] = (line[x] + b) & 255
            elif ftype == 3: line[x] = (line[x] + (a + b) // 2) & 255
            elif ftype == 4:
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line); prev = line
    return w, h, bpp, rows


def lum(col):
    return 0.299 * ((col >> 16) & 255) + 0.587 * ((col >> 8) & 255) + 0.114 * (col & 255)


def convert(obj_path, png, out_path):
    w, h, bpp, rows = png
    vs, vts, faces = [], [], []
    group = ""
    for line in open(obj_path, encoding="utf-8"):
        p = line.split()
        if not p:
            continue
        if p[0] == "v": vs.append(tuple(map(float, p[1:4])))
        elif p[0] == "vt": vts.append(tuple(map(float, p[1:3])))
        elif p[0] == "g": group = p[1] if len(p) > 1 else ""
        elif p[0] == "f":
            idx = []
            for t in p[1:]:
                parts = t.split("/")
                vi = int(parts[0])
                ti = int(parts[1]) if len(parts) > 1 and parts[1] else 0
                idx.append((vi - 1 if vi > 0 else len(vs) + vi, (ti - 1 if ti > 0 else len(vts) + ti) if ti else None))
            for k in range(1, len(idx) - 1):  # fan triangulation
                faces.append((group, idx[0], idx[k], idx[k + 1]))

    xs = [v[0] for v in vs]; ys = [v[1] for v in vs]; zs = [v[2] for v in vs]

    def centroid_z(prefix):
        pts = [vs[i[0]][2] for g, *tri in faces if g.startswith(prefix) for i in tri]
        return sum(pts) / len(pts) if pts else 0.0

    front_sign = 1.0 if centroid_z("wheel-front") > centroid_z("wheel-back") else -1.0
    scale = TARGET_LENGTH / (max(zs) - min(zs))
    cx, cz, y0 = (max(xs) + min(xs)) / 2, (max(zs) + min(zs)) / 2, min(ys)

    def to_local(v):
        # Kenney: Y up. A proper rotation (no mirror) keeps CCW = outside:
        # front +Z -> f = z, r = -x ; front -Z -> f = -z, r = x
        f = (v[2] - cz) * front_sign
        r = -(v[0] - cx) * front_sign
        return f * scale, r * scale, (v[1] - y0) * scale

    def uv(tri):
        uvs = [vts[t[1]] for t in tri if t[1] is not None]
        if not uvs:
            return None
        return sum(a for a, _ in uvs) / len(uvs), sum(b for _, b in uvs) / len(uvs)

    def colour(c_uv):
        if c_uv is None:
            return 0xFF808080
        u, v = c_uv
        # Kenney's OBJ UVs index the palette top-down (no V flip)
        px = min(w - 1, max(0, int(u * w))); py = min(h - 1, max(0, int(v * h)))
        row = rows[py]
        r, g, b = row[px * bpp], row[px * bpp + 1], row[px * bpp + 2]
        return 0xFF000000 | (r << 16) | (g << 8) | b

    out, area_by_cell = [], {}
    for g, a, b, c in faces:
        pa, pb, pc = to_local(vs[a[0]]), to_local(vs[b[0]]), to_local(vs[c[0]])
        c_uv = uv((a, b, c))
        col = colour(c_uv)
        cell = (int(c_uv[0] * 8), int(c_uv[1] * 4)) if c_uv else None
        ux, uy, uz = (pb[i] - pa[i] for i in range(3)); vx, vy, vz = (pc[i] - pa[i] for i in range(3))
        area = ((uy * vz - uz * vy) ** 2 + (uz * vx - ux * vz) ** 2 + (ux * vy - uy * vx) ** 2) ** 0.5 / 2
        if g == "body" and cell is not None:
            area_by_cell[cell] = area_by_cell.get(cell, 0.0) + area
        out.append((pa, pb, pc, col, g, cell))

    paint_cell = max(area_by_cell, key=area_by_cell.get) if area_by_cell else None
    paint_l = [lum(o[3]) for o in out if o[4] == "body" and o[5] == paint_cell]
    avg_l = (sum(paint_l) / len(paint_l)) if paint_l else 1.0

    def realistic(col, g):
        """The palette is cartoon-coloured: make wheels and trim look like a real car in a garage."""
        r, gg, b = (col >> 16) & 255, (col >> 8) & 255, col & 255
        l = lum(col)
        if g.startswith("wheel"):
            return 0xFF9AA1AB if l > 150 else 0xFF1E2126           # rim / tyre
        if r > 170 and r > gg + 60 and r > b + 60:
            return 0xFFE5393B                                       # tail / brake lights
        if l > 200:
            return 0xFFF2F6FF                                       # head lights
        if l > 140:
            return 0xFF9FB0C4                                       # glass
        k = int(max(25, min(120, l * 0.55)))
        return 0xFF000000 | (k << 16) | (k << 8) | k                # trim, bumpers, interior

    n_paint = 0
    with open(out_path, "wb") as fh:
        fh.write(b"PTCM"); fh.write(struct.pack("<i", len(out)))
        for pa, pb, pc, col, g, cell in out:
            is_paint = g == "body" and cell == paint_cell
            if not is_paint:
                col = realistic(col, g)
            if is_paint:
                k = max(0, min(255, int(round(128 * lum(col) / avg_l))))
                col = 0xFF000000 | (k << 16) | (k << 8) | k
                n_paint += 1
            fh.write(struct.pack("<9f", *pa, *pb, *pc))
            fh.write(struct.pack("<I", col))
            fh.write(struct.pack("<B", 1 if is_paint else 0))
    return len(out), front_sign, n_paint, paint_cell


def main():
    src, dst = sys.argv[1], sys.argv[2]
    os.makedirs(dst, exist_ok=True)
    png = read_png(os.path.join(src, "Textures", "colormap.png"))
    for name in MODELS:
        n, fs, n_paint, cell = convert(os.path.join(src, name + ".obj"), png, os.path.join(dst, name + ".bin"))
        print(f"{name}: {n} triangles, front {'+Z' if fs > 0 else '-Z'}, paint {n_paint} faces in swatch {cell}")
    with open(os.path.join(dst, "LICENSE.txt"), "w", encoding="utf-8") as fh:
        fh.write("Car models: Kenney Car Kit 3.1 (www.kenney.nl), Creative Commons Zero (CC0).\n"
                 "Converted with tools/convert_car_models.py.\n")


if __name__ == "__main__":
    main()
