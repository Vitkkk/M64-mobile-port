# Gera o outlook (estilo Prevots) para o dia do surto fictício de tornados.
# As áreas são recortadas pelo contorno do Brasil (Natural Earth 10m, estados unidos).
import json, sys, re
from shapely.geometry import shape, Polygon, Point
from shapely.ops import unary_union

STATES = sys.argv[1]          # ne_10m_admin_1_states_provinces.geojson
TEMPLATE = sys.argv[2]        # Template_Prevots.kml (estilos e cidades)
OUT = sys.argv[3]

br = unary_union([shape(f["geometry"]).buffer(0) for f in json.load(open(STATES))["features"]
                  if f["properties"].get("admin") == "Brazil"])

def chaikin(pts, n=4):
    for _ in range(n):
        q = []
        for (x0, y0), (x1, y1) in zip(pts, pts[1:] + pts[:1]):
            q += [(0.75*x0+0.25*x1, 0.75*y0+0.25*y1), (0.25*x0+0.75*x1, 0.25*y0+0.75*y1)]
        pts = q
    return pts

AREAS = [
 ("Tempestades", "risco0", [(-58.8,-30.0),(-57.0,-34.2),(-52.0,-34.2),(-49.6,-30.6),(-48.0,-27.5),(-47.2,-24.9),
   (-45.6,-23.9),(-45.8,-21.6),(-48.6,-20.4),(-52.4,-19.2),(-56.4,-19.0),(-58.6,-21.0),(-58.8,-25.5)]),
 ("Nível 1", "risco1", [(-58.2,-30.4),(-56.0,-32.2),(-53.2,-31.8),(-50.6,-30.1),(-49.0,-28.4),(-48.4,-26.6),
   (-48.8,-25.0),(-50.6,-23.4),(-53.0,-22.6),(-55.4,-22.9),(-56.6,-25.0),(-57.8,-27.6)]),
 ("Nível 2", "risco2", [(-57.2,-29.6),(-55.4,-30.5),(-53.4,-30.1),(-51.6,-29.1),(-50.6,-27.9),(-50.4,-26.4),
   (-51.1,-25.1),(-52.6,-24.4),(-54.4,-24.6),(-55.8,-26.4),(-56.8,-28.0)]),
 ("Nível 3", "risco3", [(-55.5,-28.8),(-54.4,-29.3),(-53.0,-29.0),(-52.0,-28.1),(-51.5,-27.0),(-51.8,-26.0),
   (-52.7,-25.6),(-53.7,-26.2),(-54.5,-27.1),(-55.2,-27.9)]),
]

TXT = ("Cavado em níveis médios avançando desde a Argentina, com baixa em superfície sobre o nordeste argentino e "
       "frente fria entrando no RS no fim da tarde. Jato em baixos níveis de 40–50 kt transportando umidade "
       "do Paraguai/Bolívia sobre o oeste do RS, SC e PR (Td 20–22 °C). CAPE 2500–3500 J/kg, cisalhamento 0–6 km de "
       "50–60 kt, SRH 0–1 km de 250–400 m²/s² e NCL baixo (&lt; 900 m) favorecem supercélulas discretas a partir das 15 h "
       "no noroeste gaúcho, evoluindo para SC e sudoeste do PR à noite, com tendência a linha/sistema convectivo depois das 21 h.")
DESC = {
 "Tempestades": "Tempestades isoladas a dispersas, com raios e chuva forte pontual.",
 "Nível 1": "Risco de tempestades severas isoladas: rajadas de 60–80 km/h, granizo pequeno, chuva forte.",
 "Nível 2": "Risco de tempestades severas dispersas: rajadas acima de 80 km/h, granizo maior que 2 cm, possibilidade de tornados.",
 "Nível 3": "Risco de tempestades severas numerosas e supercélulas: tornados possíveis, alguns podendo ser fortes (F2+), "
            "granizo grande (&gt; 4 cm) e rajadas destrutivas acima de 100 km/h. Maior risco de tornados entre 15 h e 21 h "
            "no noroeste do RS e oeste de SC.",
}

tpl = open(TEMPLATE, encoding="utf-8").read()
styles = "".join(re.findall(r'<Style id="(?:risco\d|cidade)">.*?</Style>', tpl))
cities = re.search(r'(<Folder><name>04 • Cidades.*?</Folder>)', tpl).group(1)

def ring(p):
    c = list(p.exterior.coords)
    return " ".join(f"{x:.5f},{y:.5f},0" for x, y in c)

geoms = {}
pm = []
for name, sty, pts in AREAS:
    g = Polygon(chaikin(pts)).intersection(br)
    g = g.buffer(0)
    parts = [g] if g.geom_type == "Polygon" else [p for p in g.geoms if p.area > 1e-3]
    geoms[name] = g
    pm.append(f'<Folder><name>{name}</name><open>0</open>')
    for i, p in enumerate(parts, 1):
        inner = "".join(f"<innerBoundaryIs><LinearRing><coordinates>{' '.join(f'{x:.5f},{y:.5f},0' for x,y in h.coords)}</coordinates></LinearRing></innerBoundaryIs>" for h in p.interiors)
        pm.append(f'<Placemark><name>{name} • área {i}</name><description>{DESC[name]}</description><styleUrl>#{sty}</styleUrl>'
                  f'<Polygon><tessellate>1</tessellate><altitudeMode>clampToGround</altitudeMode><outerBoundaryIs><LinearRing>'
                  f'<coordinates>{ring(p)}</coordinates></LinearRing></outerBoundaryIs>{inner}</Polygon></Placemark>')
    pm.append('</Folder>')

kml = ('<?xml version="1.0" encoding="UTF-8"?>\n<kml xmlns="http://www.opengis.net/kml/2.2"><Document>'
       '<name>PREVISÃO DE TEMPO SEVERO - DD/MM/AAAA (*) • dia do surto fictício</name><open>1</open>'
       f'<description><![CDATA[<b>Outlook fictício no layout Prevots</b> (não é emissão oficial). (*) Previsão de caráter experimental.<br><br>{TXT}<br><br>'
       + "<br>".join(f"<b>{k}</b>: {v}" for k, v in DESC.items()) + ']]></description>'
       '<LookAt><longitude>-52.5</longitude><latitude>-26.5</latitude><altitude>0</altitude><heading>0</heading><tilt>0</tilt>'
       '<range>2200000</range><altitudeMode>relativeToGround</altitudeMode></LookAt>'
       + styles + '<Folder><name>Níveis de Severidade</name><open>1</open>' + "".join(pm) + '</Folder>' + cities +
       '</Document></kml>')
open(OUT, "w", encoding="utf-8").write(kml)

# conferência: todas as trajetórias dentro do Nível 3?
if len(sys.argv) > 4:
    t = open(sys.argv[4], encoding="utf-8").read()
    for m in re.finditer(r'<name>(\S+) eixo</name>.*?<coordinates>(.*?)</coordinates>', t):
        xy = [tuple(map(float, c.split(",")[:2])) for c in m.group(2).split()]
        ok = all(geoms["Nível 3"].contains(Point(p)) for p in xy)
        print(m.group(1), "dentro do Nível 3" if ok else "FORA do Nível 3")
