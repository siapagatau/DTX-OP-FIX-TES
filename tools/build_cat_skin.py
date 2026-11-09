#!/usr/bin/env python3
"""
Bangun skin "Cat Edition" dari skin dasar 12 pad (skin-01) + 12 PNG kucing milik kamu.
Hanya memakai file yang kamu lampirkan. TIDAK menggambar karakter baru.

  python3 build_cat_skin.py --base ./SKIN/skins/skin-01 --cats "./cats pngs pack (cr. meri-bubbly)" --out ./repo

Keluaran:
  <out>/skins/cat-edition/skin.json, skin.svg      (siap commit)
  <out>/events/banners/cat-edition-2026.png         (banner <= 300 KB)
  <out>/index-entry.json                            (tempel ke "skins" di index.json skin repo)
Butuh: Pillow, lxml.
"""
import argparse, base64, glob, hashlib, io, json, os, re, sys
from lxml import etree
from PIL import Image

SKIN_ID = "cat-edition"
SKIN_NAME = "Cat Edition"
SKIN_VERSION = "1.0"
SKIN_AUTHOR = "DTX Skin Project (art: meri-bubbly)"
EVENT_ID = "cat-edition-2026"

# Anggaran (selaras dengan SkinValidator): <=16 gambar, <=450 KB per gambar, <=2 MB total, skin.svg <= 3 MB (hard) / ~1.5 MB (target)
MAX_IMAGES, MAX_IMG_BYTES, MAX_IMG_TOTAL, MAX_SVG_HARD, SVG_TARGET = 16, 450*1024, 2*1024*1024, 3*1024*1024, 1536*1024
PX_PER_UNIT = 3.5          # piksel gambar per 1 satuan viewBox (HP ~2.5-3 px/unit; 3.5 = tajam, tetap kecil karena seni datar 64 warna)
PAD_MARGIN = 10            # jarak stiker ke tepi pad (satuan viewBox)
PADS = {1:(32,55,269,61),2:(317,55,267,61),3:(599,55,269,61),4:(32,127,269,264),5:(317,127,267,264),6:(599,127,269,264),
        7:(32,393,269,247),8:(317,393,267,247),9:(599,393,269,247),10:(32,651,269,64),11:(317,651,267,64),12:(599,651,269,64)}
STRIPS = [1,2,3,10,11,12]  # pad pendek (lebar) -> kucing paling melebar
BIGS   = [4,5,6,7,8,9]     # pad hampir persegi -> kucing paling tegak/persegi

NS = "http://www.w3.org/2000/svg"; XL = "http://www.w3.org/1999/xlink"

def pastel_defs(svg):
    """Ganti warna gradien pad/body/panel jadi pastel agar kucing hitam-putih kontras. ID & struktur TIDAK diubah."""
    def stops(gid, cols):
        g = svg.find(f".//{{{NS}}}linearGradient[@id='{gid}']")
        st = g.findall(f"{{{NS}}}stop")
        for s, c in zip(st, cols): s.set("stop-color", c)
    stops("g-pad-big-top",  ["#f7e3c8","#fbe9d0","#fbe9d0","#f6dfc2","#ecd2b0"])
    stops("g-pad-big-bot",  ["#f6dfd6","#fae6dd","#fae6dd","#f4dacf","#e9cdc0"])
    stops("g-pad-strip-top",["#f4d6e2","#f8dfe9","#f6dae6","#f1d0de","#ebc6d6","#e4bccd"])
    stops("g-pad-strip-bot",["#d9e6f5","#e0ecf8","#d6e3f3","#cddcee","#c3d3e8"])
    stops("g-panel",        ["#8e7fb0","#85769f","#7b6d96"])
    for e in svg.iter(f"{{{NS}}}path"):
        if e.get("id") == "body": e.set("fill", "#a99ac9")
    for e in svg.iter(f"{{{NS}}}rect"):
        if e.get("id") == "bg-canvas": e.set("fill", "#2b2336")
    # tekstur titik pad: lebih halus supaya tidak mengotori stiker
    for c in svg.iter(f"{{{NS}}}circle"):
        if c.getparent() is not None and c.getparent().get("id") == "p-mesh": c.set("fill-opacity", "0.06")

def active_overlay(svg):
    """State aktif = sorotan TRANSLUSEN (bukan pengganti buram), supaya kucing tetap terlihat saat pad dipukul."""
    for g in svg.iter(f"{{{NS}}}g"):
        gid = g.get("id", "")
        if re.fullmatch(r"pad-\d+-active", gid):
            rect = g.findall(f"{{{NS}}}rect")[0]
            x,y,w,h = (rect.get(k) for k in ("x","y","width","height"))
            for ch in list(g): g.remove(ch)
            a = etree.SubElement(g, f"{{{NS}}}rect", x=x, y=y, width=w, height=h, rx="3", fill="#ffd166"); a.set("fill-opacity","0.50")
            b = etree.SubElement(g, f"{{{NS}}}rect", x=str(float(x)+1.5), y=str(float(y)+1.5), width=str(float(w)-3), height=str(float(h)-3), rx="3", fill="none", stroke="#ff7aa8"); b.set("stroke-width","3")

def encode_png(im, max_w, max_h):
    """Skala ke ukuran target, kuantisasi palet 8-bit (seni hitam-putih -> sangat kecil), optimize."""
    im = im.convert("RGBA"); im.thumbnail((max_w, max_h), Image.LANCZOS)
    q = im.quantize(colors=64, method=Image.FASTOCTREE, dither=Image.NONE)
    buf = io.BytesIO(); q.save(buf, "PNG", optimize=True)
    data = buf.getvalue()
    if len(data) > MAX_IMG_BYTES:   # jaga-jaga: turunkan ukuran sampai muat
        return encode_png(im, int(im.width*0.8), int(im.height*0.8))
    return data, im.size

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True); ap.add_argument("--cats", required=True); ap.add_argument("--out", required=True)
    a = ap.parse_args()

    cats = sorted(p for p in glob.glob(os.path.join(a.cats, "*.png")) if re.fullmatch(r"\d{3}\.png", os.path.basename(p)))
    if len(cats) < 12: sys.exit(f"butuh 12 PNG bernomor 001..012, ketemu {len(cats)} (file PREVIA sengaja TIDAK dipakai)")
    cats = cats[:12]
    info = [(p, Image.open(p).size) for p in cats]
    by_ratio = sorted(info, key=lambda t: t[1][0]/t[1][1], reverse=True)
    strip_cats = [p for p,_ in by_ratio[:6]]; big_cats = [p for p,_ in by_ratio[6:]]
    assign = {}
    for pad, p in zip(STRIPS, strip_cats): assign[pad] = p
    for pad, p in zip(BIGS, big_cats): assign[pad] = p

    svg_text = open(os.path.join(a.base, "skin.svg"), encoding="utf-8").read()
    svg_text = svg_text.replace('<svg xmlns="http://www.w3.org/2000/svg"', f'<svg xmlns="{NS}" xmlns:xlink="{XL}"', 1)
    root = etree.fromstring(svg_text.encode("utf-8"))
    root.set("data-skin-name", SKIN_NAME); root.set("data-skin-version", SKIN_VERSION); root.set("data-skin-author", SKIN_AUTHOR)
    for t in root.findall(f"{{{NS}}}title"): t.text = SKIN_NAME
    for d in root.findall(f"{{{NS}}}desc"):
        d.text = "Cat Edition: skin 12 pad, stiker kucing PNG tertanam (data URI). Layout/ID/hit area mengikuti skin dasar. Seni kucing: meri-bubbly."
    pastel_defs(root); active_overlay(root)

    total = 0; n = 0
    for pad, path in sorted(assign.items()):
        x,y,w,h = PADS[pad]
        bw, bh = w-2*PAD_MARGIN, h-2*PAD_MARGIN
        data, (pw, ph) = encode_png(Image.open(path), int(bw*PX_PER_UNIT), int(bh*PX_PER_UNIT))
        total += len(data); n += 1
        # ukuran tampil (satuan viewBox) mempertahankan rasio, di tengah pad
        r = min(bw/pw, bh/ph); dw, dh = pw*r, ph*r
        g = root.find(f".//{{{NS}}}g[@id='pad-{pad}-normal']")
        img = etree.SubElement(g, f"{{{NS}}}image", id=f"cat-{pad}", x=f"{x+(w-dw)/2:.1f}", y=f"{y+(h-dh)/2:.1f}", width=f"{dw:.1f}", height=f"{dh:.1f}", preserveAspectRatio="xMidYMid meet")
        img.set(f"{{{XL}}}href", "data:image/png;base64," + base64.b64encode(data).decode("ascii"))
        print(f"pad-{pad:<2} <- {os.path.basename(path)}  {pw}x{ph}px  {len(data)//1024} KB")
    assert n <= MAX_IMAGES and total <= MAX_IMG_TOTAL, "anggaran gambar terlampaui"

    out_skin = os.path.join(a.out, "skins", SKIN_ID); os.makedirs(out_skin, exist_ok=True)
    svg_out = etree.tostring(root, encoding="unicode")
    svg_bytes = svg_out.encode("utf-8")
    open(os.path.join(out_skin, "skin.svg"), "wb").write(svg_bytes)

    j = json.load(open(os.path.join(a.base, "skin.json"), encoding="utf-8"))
    j["name"], j["version"], j["author"] = SKIN_NAME, SKIN_VERSION, SKIN_AUTHOR
    json_bytes = json.dumps(j, ensure_ascii=False, indent=2).encode("utf-8")
    open(os.path.join(out_skin, "skin.json"), "wb").write(json_bytes)

    # banner (<= 300 KB): kolase kucing di atas latar pastel, TANPA teks pihak lain
    W,H = 960,400
    bn = Image.new("RGBA", (W,H), (169,154,201,255))
    for i,p in enumerate(sorted(cats)[:6]):
        c = Image.open(p).convert("RGBA"); c.thumbnail((140,150), Image.LANCZOS)
        bn.alpha_composite(c, (30+i*152 + (140-c.width)//2, 215 + (150-c.height)//2))
    bn = bn.convert("RGB").quantize(colors=128, method=Image.MEDIANCUT, dither=Image.NONE)
    bpath = os.path.join(a.out, "events", "banners", f"{EVENT_ID}.png"); os.makedirs(os.path.dirname(bpath), exist_ok=True)
    bn.save(bpath, "PNG", optimize=True)

    sha = lambda b: hashlib.sha256(b).hexdigest()
    entry = {"id": SKIN_ID, "name": SKIN_NAME, "version": SKIN_VERSION, "author": SKIN_AUTHOR, "padMin": 12, "padMax": 12,
             "path": f"skins/{SKIN_ID}/",
             "files": {"skin.json": {"sha256": sha(json_bytes), "size": len(json_bytes)}, "skin.svg": {"sha256": sha(svg_bytes), "size": len(svg_bytes)}}}
    json.dump(entry, open(os.path.join(a.out, "index-entry.json"), "w"), indent=2)
    print(f"\n{n} gambar, total PNG {total//1024} KB | skin.svg {len(svg_bytes)//1024} KB (target <= {SVG_TARGET//1024} KB, batas keras {MAX_SVG_HARD//1024} KB)")
    print(f"banner {os.path.getsize(bpath)//1024} KB (batas 300 KB)")
    assert len(svg_bytes) <= MAX_SVG_HARD, "skin.svg melebihi 3 MB"
    assert os.path.getsize(bpath) <= 300*1024, "banner > 300 KB"
    if len(svg_bytes) > SVG_TARGET: print("PERINGATAN: di atas target 1,5 MB -> turunkan PX_PER_UNIT")

if __name__ == "__main__": main()
