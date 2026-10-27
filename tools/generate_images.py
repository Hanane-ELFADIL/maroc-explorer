#!/usr/bin/env python3
"""Génère une illustration SVG pour chaque ville / lieu (images/<collection>/<slug>.svg).
Elles servent d'images par défaut. Pour une vraie photo, déposez simplement
images/<collection>/<slug>.jpg (ou .png / .webp) : l'application la préfère automatiquement.
Usage : python tools/generate_images.py   (depuis la racine du projet)"""
import json, os, zlib
from xml.sax.saxutils import escape

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA, OUT = os.path.join(ROOT, "data"), os.path.join(ROOT, "images")
CREAM, INK = "#FBF3E4", "#3B2A1E"
PAL = {"Tanger": ("#6A98A4", "#2F5560"), "Rabat": ("#C09A70", "#6B4A2E"), "Chefchaouen": ("#8DB3D6", "#3C6A98"),
       "Marrakech": ("#E09A72", "#8E3F22"), "Fès": ("#A9B685", "#4E6040"), "Meknès": ("#DDBB78", "#8A6428"),
       "Agadir": ("#7CC0CE", "#2F7384"), "Essaouira": ("#8FB3CE", "#34607E"), "Ouarzazate": ("#E2AE76", "#9A5C30")}
DEFAULT = ("#CFAE86", "#7A5233")
SCENE = {"Monument": "tower", "Site religieux": "tower", "Musée": "museum", "Site archéologique": "museum", "Jardin": "garden", "Plage": "beach",
         "Souk": "souk", "Site artisanal": "souk", "Point de vue": "view", "Site naturel": "nature", "Place": "arches", "Médina": "medina", "Quartier": "houses"}

def arch(cx, w, h, fill, inner):
    top = 500 - h + w / 2
    p = f"M{cx - w / 2} 500V{top}A{w / 2} {w / 2} 0 0 1 {cx + w / 2} {top}V500Z"
    iw = w * .62
    q = f"M{cx - iw / 2} 500V{top + w * .12}A{iw / 2} {iw / 2} 0 0 1 {cx + iw / 2} {top + w * .12}V500Z"
    return f'<path d="{p}" fill="{fill}"/><path d="{q}" fill="{inner}" fill-opacity=".85"/>'

def star(cx, cy, r, fill):
    pts = []
    import math
    for i in range(10):
        a = -math.pi / 2 + i * math.pi / 5; rr = r if i % 2 == 0 else r * .45
        pts.append(f"{cx + rr * math.cos(a):.1f},{cy + rr * math.sin(a):.1f}")
    return f'<polygon points="{" ".join(pts)}" fill="{fill}"/>'

def waves(c, y0=330):
    s = ""
    for i in range(4):
        y = y0 + i * 36
        s += f'<path d="M0 {y}Q50 {y - 18} 100 {y}T200 {y}T300 {y}T400 {y}T500 {y}T600 {y}T700 {y}T800 {y}V500H0Z" fill="{c}" fill-opacity="{.25 + i * .18}"/>'
    return s

def scene(kind, c1, c2, h, doc):
    sx = 120 + (h % 5) * 140
    sun = f'<circle cx="{sx}" cy="105" r="46" fill="{CREAM}" fill-opacity=".85"/>'
    g = f'<rect y="395" width="800" height="105" fill="{c2}" fill-opacity=".55"/>'
    if kind == "tower":
        return sun + g + f'<rect x="355" y="130" width="90" height="370" fill="{CREAM}"/><rect x="340" y="120" width="120" height="22" fill="{CREAM}"/><rect x="375" y="80" width="50" height="45" fill="{CREAM}"/><path d="M375 80Q400 40 425 80Z" fill="{CREAM}"/><circle cx="400" cy="38" r="9" fill="{CREAM}"/>' + \
            arch(400, 46, 80, c2, c2).replace('fill-opacity=".85"', '') + "".join(f'<rect x="{388}" y="{y}" width="24" height="34" rx="12" fill="{c2}"/>' for y in (170, 240)) + arch(230, 110, 190, CREAM, c2) + arch(570, 110, 190, CREAM, c2)
    if kind == "medina":
        return sun + g + f'<rect x="0" y="300" width="800" height="200" fill="{CREAM}"/>' + "".join(f'<rect x="{x}" y="280" width="28" height="30" fill="{CREAM}"/>' for x in range(0, 800, 60)) + arch(160, 120, 170, c2, c1) + arch(400, 140, 200, c2, c1) + arch(640, 120, 170, c2, c1)
    if kind == "arches":
        return sun + g + "".join(arch(x, 130, 230, CREAM, c2) for x in (120, 280, 440, 600, 760)) + f'<rect y="470" width="800" height="30" fill="{c2}"/>'
    if kind == "museum":
        return sun + g + f'<polygon points="140,190 400,80 660,190" fill="{CREAM}"/><rect x="140" y="190" width="520" height="24" fill="{CREAM}"/>' + "".join(f'<rect x="{x}" y="225" width="36" height="215" fill="{CREAM}"/>' for x in range(170, 640, 80)) + f'<rect x="120" y="440" width="560" height="40" fill="{CREAM}"/>'
    if kind == "garden":
        s = sun + g
        for i, x in enumerate((120, 260, 420, 570, 700)):
            hh = 150 + (h + i * 37) % 70
            s += f'<rect x="{x - 9}" y="{500 - hh}" width="18" height="{hh}" fill="{INK}" fill-opacity=".6"/><circle cx="{x}" cy="{500 - hh - 20}" r="{58 + i % 2 * 12}" fill="#7C8F63"/><circle cx="{x + 34}" cy="{500 - hh}" r="40" fill="#6F8256"/>'
        return s + f'<ellipse cx="400" cy="470" rx="140" ry="22" fill="{CREAM}" fill-opacity=".8"/>'
    if kind == "beach":
        return sun + f'<rect y="300" width="800" height="200" fill="{c2}" fill-opacity=".4"/>' + waves(CREAM, 320) + f'<path d="M0 470Q200 430 400 460T800 450V500H0Z" fill="#E6CFA2"/>'
    if kind == "souk":
        s = sun + g + "".join(f'<path d="M{x} 0V{70 + (x // 7) % 60}" stroke="{CREAM}" stroke-width="3"/><path d="M{x - 26} {70 + (x // 7) % 60}h52l-8 56h-36z" fill="{CREAM}" fill-opacity=".92"/><circle cx="{x}" cy="{104 + (x // 7) % 60}" r="9" fill="{c2}"/>' for x in range(90, 800, 130))
        return s + "".join(f'<rect x="{x}" y="330" width="150" height="80" fill="{CREAM}" fill-opacity=".85"/><path d="M{x} 330h150l-20-40H{x + 20}z" fill="{c2}"/>' for x in (80, 330, 580))
    if kind in ("view", "nature"):
        s = sun + f'<polygon points="0,420 170,230 300,350 470,170 640,340 800,250 800,500 0,500" fill="{c2}" fill-opacity=".75"/><polygon points="0,470 220,320 420,430 620,300 800,430 800,500 0,500" fill="{CREAM}" fill-opacity=".35"/>'
        return s + (f'<path d="M470 170Q500 300 440 500" stroke="{CREAM}" stroke-width="16" fill="none" stroke-opacity=".8"/>' if kind == "nature" else "")
    if kind == "houses":
        s = sun + g
        for i, x in enumerate(range(30, 800, 130)):
            hh = 150 + (h + i * 41) % 110
            s += f'<rect x="{x}" y="{500 - hh}" width="110" height="{hh}" fill="{CREAM}"/>' + arch(x + 55, 40, 80, c2, c1) + f'<rect x="{x + 20}" y="{500 - hh + 25}" width="26" height="30" fill="{c2}"/><rect x="{x + 66}" y="{500 - hh + 25}" width="26" height="30" fill="{c2}"/>'
        return s
    if kind == "city":
        s = sun + f'<polygon points="0,380 150,300 300,360 460,280 640,350 800,300 800,500 0,500" fill="{c2}" fill-opacity=".5"/>' + g
        s += f'<rect x="0" y="390" width="800" height="110" fill="{CREAM}"/>' + "".join(f'<rect x="{x}" y="372" width="26" height="24" fill="{CREAM}"/>' for x in range(0, 800, 52))
        s += f'<rect x="600" y="150" width="70" height="250" fill="{CREAM}"/><rect x="590" y="140" width="90" height="16" fill="{CREAM}"/><path d="M610 140Q635 90 660 140Z" fill="{CREAM}"/><circle cx="635" cy="82" r="8" fill="{CREAM}"/>'
        s += f'<path d="M220 400V320A60 60 0 0 1 340 320V400Z" fill="{CREAM}"/><path d="M230 320A50 50 0 0 1 330 320Z" fill="{c2}" fill-opacity=".55"/>' + arch(150, 70, 110, c2, c1) + arch(460, 80, 120, c2, c1)
        return s
    if kind == "hotel":
        n = max(0, min(5, int(doc.get("stars") or 0)))
        s = sun + g + f'<rect x="150" y="150" width="500" height="350" fill="{CREAM}"/><rect x="130" y="135" width="540" height="22" fill="{CREAM}"/>'
        s += "".join(arch(x, 80, 120, c2, c1) for x in (250, 400, 550)).replace("500V", "330V").replace("V500Z", "V330Z") if False else ""
        s += "".join(f'<path d="M{x - 38} 300V260A38 38 0 0 1 {x + 38} 260V300Z" fill="{c2}" fill-opacity=".8"/>' for x in (230, 400, 570))
        s += arch(400, 100, 140, c2, c1) + "".join(star(300 + i * 50, 100, 20, CREAM) for i in range(n)) if n else arch(400, 100, 140, c2, c1)
        return s
    if kind == "food":
        return sun.replace('cy="105"', 'cy="95"') + g + f'<rect x="0" y="400" width="800" height="100" fill="{CREAM}" fill-opacity=".9"/><path d="M250 400Q250 330 400 330T550 400Z" fill="{c2}"/><path d="M290 330Q400 130 510 330Z" fill="{CREAM}"/><circle cx="400" cy="125" r="16" fill="{CREAM}"/>' + \
            f'<path d="M370 100Q350 60 380 30M430 100Q450 60 420 30" stroke="{CREAM}" stroke-width="7" fill="none" stroke-linecap="round" stroke-opacity=".7"/>'
    if kind == "dunes":
        return sun + f'<path d="M0 360Q200 260 400 340T800 330V500H0Z" fill="{CREAM}" fill-opacity=".55"/><path d="M0 430Q220 340 440 420T800 400V500H0Z" fill="{c2}" fill-opacity=".7"/>'
    if kind == "sail":
        return sun + f'<rect y="360" width="800" height="140" fill="{c2}" fill-opacity=".5"/>' + waves(CREAM, 380) + f'<polygon points="400,120 400,340 530,340" fill="{CREAM}"/><polygon points="385,150 385,340 300,340" fill="{CREAM}" fill-opacity=".85"/><rect x="330" y="340" width="190" height="16" rx="8" fill="{INK}" fill-opacity=".7"/>'
    if kind == "lanterns":
        s = f'<path d="M0 60Q400 150 800 60" stroke="{CREAM}" stroke-width="4" fill="none"/>'
        for i, x in enumerate((150, 300, 450, 600)):
            y = 90 + (30 if i % 2 else 0) + (10 if i in (1, 2) else 0)
            s += f'<path d="M{x} {y}V{y + 40}" stroke="{CREAM}" stroke-width="3"/><path d="M{x - 40} {y + 40}h80l-14 110h-52z" fill="{CREAM}" fill-opacity=".92"/><circle cx="{x}" cy="{y + 95}" r="14" fill="{c2}"/>'
        return s + "".join(star(x, y, r, CREAM) for x, y, r in ((90, 300, 22), (700, 260, 28), (380, 380, 18), (560, 430, 24), (230, 450, 16))) + g
    if kind == "train":
        return sun + g + f'<rect x="120" y="230" width="560" height="150" rx="40" fill="{CREAM}"/>' + "".join(f'<rect x="{x}" y="260" width="70" height="50" rx="10" fill="{c2}"/>' for x in range(170, 640, 100)) + f'<rect x="60" y="400" width="680" height="12" fill="{INK}" fill-opacity=".5"/>' + f'<circle cx="220" cy="395" r="18" fill="{INK}"/><circle cx="580" cy="395" r="18" fill="{INK}"/>'
    if kind == "plane":
        return sun + g + f'<path d="M140 330L560 230Q640 215 660 250Q650 285 560 290L290 330L230 390H190L220 335Z" fill="{CREAM}"/><path d="M360 285L300 160H340L460 270Z" fill="{CREAM}" fill-opacity=".85"/>'
    if kind == "bus":
        return sun + g + f'<rect x="130" y="220" width="540" height="170" rx="26" fill="{CREAM}"/>' + "".join(f'<rect x="{x}" y="245" width="80" height="60" rx="8" fill="{c2}"/>' for x in range(160, 640, 100)) + f'<circle cx="240" cy="395" r="30" fill="{INK}"/><circle cx="560" cy="395" r="30" fill="{INK}"/>'
    return scene("medina", c1, c2, h, doc)

def svg(kind, city, title, doc):
    c1, c2 = PAL.get(city, DEFAULT); h = zlib.crc32(title.encode()) % 997
    ang = ("0" if h % 2 else "1")
    body = scene(kind, c1, c2, h, doc)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 500" preserveAspectRatio="xMidYMid slice" role="img"><title>{escape(title)}</title>'
            f'<defs><linearGradient id="g" x1="0" y1="0" x2="{ang}" y2="1"><stop offset="0" stop-color="{c1}"/><stop offset="1" stop-color="{c2}"/></linearGradient>'
            f'<pattern id="p" width="80" height="80" patternUnits="userSpaceOnUse"><path d="M40 4l9 27 27 9-27 9-9 27-9-27-27-9 27-9z" fill="none" stroke="#fff" stroke-opacity=".13" stroke-width="2"/></pattern></defs>'
            f'<rect width="800" height="500" fill="url(#g)"/><rect width="800" height="500" fill="url(#p)"/>{body}</svg>')

CITY_SCENE = {"Tanger": "view", "Rabat": "city", "Chefchaouen": "houses", "Marrakech": "tower", "Fès": "arches",
              "Meknès": "medina", "Agadir": "beach", "Essaouira": "sail", "Ouarzazate": "dunes"}

def kind_of(col, d):
    if col == "cities": return CITY_SCENE.get(d.get("name"), "city")
    if col == "attractions": return SCENE.get(d.get("category"), "medina")
    if col == "hotels": return "hotel"
    if col == "restaurants": return "food"
    if col == "events": return "lanterns"
    if col == "activities":
        t = (d.get("activity_type") or ""); return "sail" if "nautique" in t else ("food" if "gastro" in t else "dunes")
    t = (d.get("type") or "") + " " + (d.get("mode") or "")
    return "plane" if "aéroport" in t else ("train" if ("train" in t or "gare" == (d.get("type") or "") or "tram" in t) else "bus")

def write(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w", encoding="utf-8").write(content)

if __name__ == "__main__":
    n = 0
    for col in ("cities", "attractions", "hotels", "restaurants", "activities", "events", "transport"):
        for d in json.load(open(os.path.join(DATA, col + ".json"), encoding="utf-8")):
            write(os.path.join(OUT, col, d["slug"] + ".svg"), svg(kind_of(col, d), d.get("city", ""), d["name"], d)); n += 1
        write(os.path.join(OUT, "_defaults", col + ".svg"), svg(kind_of(col, {}), "", col, {}))
    write(os.path.join(OUT, "hero.svg"), svg("city", "Marrakech", "Maroc Explorer", {}))
    print(n, "images générées dans", OUT)
