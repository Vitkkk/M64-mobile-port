import math, random
R=6371000.0
COL={0:"ffffff00",1:"ff8b0000",2:"ff00ffff",3:"ff008cff",4:"ff0000ff",5:"ffa00080"}
NAME={0:"F0",1:"F1",2:"F2",3:"F3",4:"F4",5:"F5"}
def interp(kf,s):
    if s<=kf[0][0]: return kf[0][1]
    for (a,va),(b,vb) in zip(kf,kf[1:]):
        if s<=b:
            t=(s-a)/(b-a); t=t*t*(3-2*t)  # smoothstep
            return va+(vb-va)*t
    return kf[-1][1]
def noise(seed,amp,scale):
    r=random.Random(seed); comps=[(r.uniform(0.6,1.6)/scale*2*math.pi,r.uniform(0,6.28),r.uniform(.3,1)) for _ in range(5)]
    tot=sum(c[2] for c in comps)
    return lambda s: amp*sum(a*math.sin(k*s+p) for k,p,a in comps)/tot
def move(lat,lon,d,brg):
    b=math.radians(brg); la=math.radians(lat)
    return lat+math.degrees(d*math.cos(b)/R), lon+math.degrees(d*math.sin(b)/(R*math.cos(la)))
def build(t):
    L=t["len"]; ds=40.0; n=int(L/ds)
    hn=noise(t["seed"],t.get("wob",6),900); wn0=noise(t["seed"]+1,0.10,1800); wn1=noise(t["seed"]+3,0.035,250); wn=lambda s: wn0(s)+wn1(s); inn=noise(t["seed"]+2,0.25,600)
    lat,lon=t["start"]; pts=[]
    for i in range(n+1):
        s=i*ds; h=interp(t["head"],s)+hn(s)
        I=max(0,interp(t["I"],s)+inn(s)*min(1,interp(t["I"],s)))
        W=interp(t["W"],s)*(1+wn(s))
        pts.append((lat,lon,h,I,W,s))
        lat,lon=move(lat,lon,ds,h)
    return pts
def contours(pts,k):
    segs=[];cur=[]
    for lat,lon,h,I,W,s in pts:
        if k==0: hw=W/2 if I>0.05 else 0
        else: hw=W/2*max(0,(I-k)/I)**0.8 if I>k else 0
        if hw>3:
            off=(W/2-hw)*0.35  # nucleo deslocado p/ direita do movimento
            c=move(lat,lon,off,h+90)
            cur.append((move(*c,hw,h-90),move(*c,hw,h+90)))
        elif cur: segs.append(cur);cur=[]
    if cur: segs.append(cur)
    out=[]
    for sg in segs:
        if len(sg)<3: continue
        ring=[p[0] for p in sg]+[p[1] for p in reversed(sg)]+[sg[0][0]]
        out.append(ring)
    return out
def fmt(r): return " ".join(f"{lo:.6f},{la:.6f},0" for la,lo in r)

T=[
 dict(id="T01",nome="Tornado de Eugênio de Castro",start=(-28.555,-54.180),len=9500,seed=11,time="15:42–15:53",
   head=[(0,58),(9500,64)],I=[(0,.3),(1500,1.1),(5000,1.6),(8000,1.0),(9500,0)],W=[(0,60),(3000,180),(6000,220),(9500,40)],
   obs="Danos em telhados, eucaliptos quebrados, galpões destelhados."),
 dict(id="T02",nome="Tornado de Augusto Pestana",start=(-28.540,-53.995),len=21000,seed=22,time="16:05–16:27",
   head=[(0,62),(8000,66),(12000,52),(14000,48),(17000,63),(21000,70)],
   I=[(0,.3),(2500,1.4),(7000,2.6),(10500,2.8),(12500,1.7),(14500,1.3),(17000,2.3),(19500,1.2),(21000,0)],
   W=[(0,50),(4000,250),(10000,420),(13000,260),(17000,330),(21000,60)],
   obs="Tentativa de oclusão por volta do km 12: o tornado desacelerou, desviou à esquerda e enfraqueceu, mas reintensificou sem dissipar."),
 dict(id="T03a",nome="Tornado de Boa Vista do Cadeado (1º da família cíclica)",start=(-28.700,-53.880),len=19500,seed=33,time="16:31–16:55",
   head=[(0,60),(12000,64),(15000,40),(17000,5),(18500,-40),(19500,-70)],
   I=[(0,.3),(2000,1.3),(6000,2.4),(10000,3.3),(13000,3.4),(15500,2.3),(17500,1.2),(19500,0)],
   W=[(0,70),(5000,350),(11000,600),(14000,520),(17000,250),(19500,40)],
   obs="Oclusão clássica: no final o tornado curvou bruscamente para a esquerda (N/NO), estreitou e dissipou enquanto o novo mesociclone se formava a SE."),
 dict(id="T03b",nome="Tornado de Cruz Alta (2º da família cíclica)",start=(-28.690,-53.692),len=46000,seed=44,time="16:52–17:44",wob=5,
   head=[(0,72),(6000,66),(20000,64),(23000,47),(25500,44),(28000,63),(40000,67),(43000,50),(46000,15)],
   I=[(0,.3),(1500,1.8),(5000,3.0),(9000,3.9),(13000,4.4),(15000,5.25),(16200,5.2),(18000,4.3),(21000,3.6),(23500,2.4),(25500,2.1),(28500,3.4),(33000,3.8),(38000,2.8),(42000,1.6),(46000,0)],
   W=[(0,90),(4000,500),(10000,900),(15000,1250),(20000,1000),(24000,550),(28000,800),(35000,850),(42000,450),(46000,60)],
   obs="Tornado mais intenso do evento. Breve faixa F5 (~1,5 km) em área rural (residências de alvenaria varridas das fundações). Tentativa de oclusão no km ~24 (desvio à esquerda e enfraquecimento para F2), reintensificou para F3/F4 e ocluiu ao final curvando para N."),
 dict(id="T03s",nome="Tornado satélite de Cruz Alta",start=(-28.655,-53.560),len=3200,seed=55,time="17:03–17:07",wob=10,
   head=[(0,80),(1500,60),(3200,30)],I=[(0,.2),(1000,.9),(2200,.8),(3200,0)],W=[(0,25),(1200,70),(3200,20)],
   obs="Tornado satélite girando em torno do tornado principal, curto e fraco."),
 dict(id="T04",nome="Tornado de Pejuçara",start=(-28.470,-53.720),len=6000,seed=66,time="16:40–16:47",
   head=[(0,55),(6000,60)],I=[(0,.3),(2000,.8),(4000,.7),(6000,0)],W=[(0,40),(2500,120),(6000,30)],obs="Danos leves: árvores e algumas telhas."),
 dict(id="T05",nome="Tornado de Ibirubá",start=(-28.660,-53.150),len=17500,seed=77,time="17:58–18:18",
   head=[(0,68),(9000,70),(11500,58),(17500,65)],I=[(0,.3),(2500,1.5),(7000,2.5),(9500,3.2),(11500,2.6),(15000,1.4),(17500,0)],
   W=[(0,60),(5000,300),(10000,480),(17500,50)],obs="Silos e armazéns graneleiros destruídos; torres de transmissão tombadas."),
 dict(id="T06",nome="Tornado de Não-Me-Toque",start=(-28.500,-52.880),len=7500,seed=88,time="18:25–18:34",
   head=[(0,62),(7500,58)],I=[(0,.3),(2500,1.3),(5000,1.2),(7500,0)],W=[(0,40),(3000,160),(7500,30)],obs="Danos em telhados e plantações."),
 dict(id="T07",nome="Tornado de Erval Grande",start=(-27.430,-52.640),len=14000,seed=99,time="19:10–19:26",
   head=[(0,50),(6000,55),(9000,40),(11000,35),(14000,52)],I=[(0,.3),(2000,1.2),(6000,2.2),(9000,1.4),(11500,1.9),(14000,0)],
   W=[(0,50),(5000,260),(9000,180),(11500,220),(14000,40)],obs="Tentativa de oclusão leve no km 9; travessia do vale do rio Uruguai."),
 dict(id="T08",nome="Tornado de Xaxim",start=(-26.990,-52.590),len=5000,seed=111,time="19:44–19:50",
   head=[(0,48),(5000,52)],I=[(0,.3),(1800,.9),(3500,.6),(5000,0)],W=[(0,30),(2000,100),(5000,25)],obs="Galhos e telhas; curta duração."),
 dict(id="T09",nome="Tornado de Abelardo Luz",start=(-26.610,-52.400),len=11000,seed=122,time="20:12–20:25",
   head=[(0,55),(11000,62)],I=[(0,.3),(3000,1.5),(6500,1.8),(9000,1.1),(11000,0)],W=[(0,50),(4000,200),(11000,40)],obs="Danos em aviários e linhas de energia."),
 dict(id="T10",nome="Tornado de Clevelândia",start=(-26.430,-52.420),len=4200,seed=133,time="20:31–20:36",
   head=[(0,60),(4200,70)],I=[(0,.2),(1500,.7),(4200,0)],W=[(0,30),(1500,80),(4200,20)],obs="Fraco, danos isolados em árvores."),
]
def length(pts): return pts[-1][5]/1000
out=['<?xml version="1.0" encoding="UTF-8"?>','<kml xmlns="http://www.opengis.net/kml/2.2">','<Document>',
 '<name>Surto de tornados fictício – Sul do Brasil</name>',
 '<description><![CDATA[<b>EVENTO FICTÍCIO</b> – trajetórias simuladas para fins didáticos.<br>Contornos de dano por escala Fujita (aninhados):<br>'
 '<span style="color:#00FFFF">■</span> F0 – ciano<br><span style="color:#00008B">■</span> F1 – azul escuro<br><span style="color:#CCCC00">■</span> F2 – amarelo<br>'
 '<span style="color:#FF8C00">■</span> F3 – laranja<br><span style="color:#FF0000">■</span> F4 – vermelho<br><span style="color:#8000A0">■</span> F5 – roxo]]></description>']
for k in range(6):
    out.append(f'<Style id="F{k}"><LineStyle><color>{COL[k]}</color><width>1</width></LineStyle><PolyStyle><color>b3{COL[k][2:]}</color></PolyStyle></Style>')
out.append('<Style id="eixo"><LineStyle><color>99ffffff</color><width>1</width></LineStyle></Style>')
out.append('<Style id="ini"><IconStyle><scale>0.6</scale><Icon><href>http://maps.google.com/mapfiles/kml/shapes/triangle.png</href></Icon></IconStyle><LabelStyle><scale>0.7</scale></LabelStyle></Style>')
summary=[]
byid={t["id"]:t for t in T}
ea=build(byid["T03a"]); byid["T03b"]["start"]=move(ea[-1][0],ea[-1][1],3500,150)
eb=build(byid["T03b"]); q=[x for x in eb if x[5]>=16000][0]; byid["T03s"]["start"]=move(q[0],q[1],2600,q[2]+100)
for t in T:
    pts=build(t); mx=max(p[3] for p in pts); rat=min(5,int(mx)); mw=max(p[4] for p in pts)
    summary.append((t["id"],rat,length(pts)))
    desc=(f"<b>{t['nome']}</b><br>Classificação: <b>F{rat}</b><br>Extensão: {length(pts):.1f} km<br>"
          f"Largura máxima: {mw:.0f} m<br>Horário (local): {t['time']}<br>{t['obs']}")
    out.append(f'<Folder><name>{t["id"]} – {t["nome"]} (F{rat})</name><description><![CDATA[{desc}]]></description>')
    for k in range(rat+1):
        for j,ring in enumerate(contours(pts,k)):
            out.append(f'<Placemark><name>{t["id"]} contorno F{k}</name><styleUrl>#F{k}</styleUrl><Polygon><tessellate>1</tessellate><outerBoundaryIs><LinearRing><coordinates>{fmt(ring)}</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>')
    out.append(f'<Placemark><name>{t["id"]} eixo</name><styleUrl>#eixo</styleUrl><LineString><tessellate>1</tessellate><coordinates>{fmt([(p[0],p[1]) for p in pts[::5]]+[(pts[-1][0],pts[-1][1])])}</coordinates></LineString></Placemark>')
    out.append(f'<Placemark><name>{t["id"]} (F{rat})</name><description><![CDATA[{desc}]]></description><styleUrl>#ini</styleUrl><Point><coordinates>{pts[0][1]:.6f},{pts[0][0]:.6f},0</coordinates></Point></Placemark>')
    out.append('</Folder>')
out+=['</Document>','</kml>']
open("/home/user/M64-mobile-port/kml/surto_tornados_sul_brasil.kml","w").write("\n".join(out))
for s in summary: print(s)
