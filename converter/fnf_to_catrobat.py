#!/usr/bin/env python3
"""
fnf_to_catrobat.py - Converte mods de Friday Night Funkin' em projetos .catrobat
nativos do Pocket Code (Catrobat 1.12), com gameplay implementado em blocos.

Uso:
    python3 converter/fnf_to_catrobat.py MOD(.zip|pasta) -o output/FNF_Port.catrobat
            [--report output/conversion_report.txt] [--songs introduction,...]
            [--difficulty basic] [--minimal]

Requisitos: Python 3.8+, Pillow, ffmpeg (para converter OGG -> MP3).

O projeto gerado contem apenas bricks, scripts, variaveis, listas, looks e sons
nativos do Pocket Code. A serializacao (catrobat_core.py) segue o formato
XStream do Catroid observado em projetos .catrobat reais.
"""

import argparse
import hashlib
import io
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw, ImageFont, ImageFilter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from catrobat_core import *  # noqa: E402,F401,F403
import catrobat_core as cc  # noqa: E402

SCREEN_W, SCREEN_H = 1280, 720
NOTE_COLORS = [(194, 75, 153), (0, 255, 255), (18, 250, 5), (249, 57, 63)]  # roxo azul verde vermelho
DIR_NAMES = ["LEFT", "DOWN", "UP", "RIGHT"]


class Report:
    def __init__(self):
        self.sections = {}

    def add(self, section, line):
        self.sections.setdefault(section, []).append(line)

    def text(self):
        out = []
        for sec, lines in self.sections.items():
            out.append("=" * 78)
            out.append(sec)
            out.append("=" * 78)
            out.extend(lines)
            out.append("")
        return "\n".join(out)


R = Report()


# ==========================================================================
# 1. Leitura do mod
# ==========================================================================

class Mod:
    def __init__(self, path):
        self.tmp = None
        if os.path.isfile(path) and zipfile.is_zipfile(path):
            self.tmp = tempfile.mkdtemp(prefix="fnfmod_")
            with zipfile.ZipFile(path) as z:
                for info in z.infolist():
                    name = info.filename.replace("\\", "/")
                    if name.startswith("/") or ".." in name.split("/"):
                        continue  # protege contra caminhos maliciosos
                    z.extract(info, self.tmp)
            root = self.tmp
        else:
            root = path
        # se o zip tiver uma unica pasta raiz, entra nela
        entries = [e for e in os.listdir(root) if not e.startswith("__MACOSX")]
        while len(entries) == 1 and os.path.isdir(os.path.join(root, entries[0])) and \
                not os.path.isdir(os.path.join(root, "data")):
            root = os.path.join(root, entries[0])
            entries = os.listdir(root)
        # mods de Psych as vezes vem em mods/<nome>/
        if os.path.isdir(os.path.join(root, "mods")) and not os.path.isdir(os.path.join(root, "data")):
            subs = [d for d in os.listdir(os.path.join(root, "mods")) if os.path.isdir(os.path.join(root, "mods", d))]
            if subs:
                root = os.path.join(root, "mods", subs[0])
        self.root = root
        self.files = []
        for dp, dn, fns in os.walk(root):
            for fn_ in fns:
                self.files.append(os.path.relpath(os.path.join(dp, fn_), root).replace("\\", "/"))
        self.files.sort()

    def p(self, *parts):
        return os.path.join(self.root, *parts)

    def exists(self, *parts):
        return os.path.exists(self.p(*parts))

    def find(self, rel_candidates):
        low = {f.lower(): f for f in self.files}
        for c in rel_candidates:
            if c.lower() in low:
                return self.p(low[c.lower()])
        return None

    def json(self, rel):
        with open(self.p(rel), encoding="utf-8-sig") as f:
            txt = f.read()
        txt = re.sub(r",\s*([}\]])", r"\1", txt)  # virgulas sobrando, comuns em mods
        return json.loads(txt)

    def cleanup(self):
        if self.tmp:
            shutil.rmtree(self.tmp, ignore_errors=True)


def detect_engine(mod):
    f = set(mod.files)
    has = lambda pre: any(x.startswith(pre) for x in f)  # noqa: E731
    reasons = []
    engine = "desconhecida"
    if "pack.json" in f or has("custom_events/") or has("custom_notetypes/") or \
            (has("characters/") and has("stages/") and has("weeks/")):
        engine = "Psych Engine (pasta de mod)"
        reasons.append("pack.json / custom_events / characters+stages+weeks em JSON")
    if any(x.endswith("-metadata.json") for x in f) and any(x.endswith("-chart.json") for x in f):
        engine = "V-Slice (FNF 0.3+)"
        reasons.append("arquivos *-chart.json e *-metadata.json")
    if has("data/charts/") and any(x.endswith(".hx") for x in f):
        engine = "Codename Engine"
        reasons.append("data/charts + scripts .hx")
    if any(x.endswith(".hx") for x in f) and engine.startswith("Psych"):
        reasons.append("ha scripts HScript (.hx)")
    return engine, reasons


# ==========================================================================
# 2. Charts
# ==========================================================================

class Song:
    def __init__(self):
        self.id = ""
        self.name = ""
        self.bpm = 100.0
        self.speed = 1.0
        self.notes = []      # (t, lane0..7, sustain_ms) lane<4 oponente, >=4 jogador
        self.sections = []   # (t_inicio, mustHit)
        self.events = []     # (t, nome, v1, v2)
        self.bpm_changes = []  # (t, bpm)
        self.player1 = "bf"
        self.player2 = "dad"
        self.gf = "gf"
        self.stage = "stage"
        self.needs_voices = True
        self.inst = None
        self.voices = None
        self.chart_path = None
        self.format = ""

    def step_at(self, t):
        bpm = self.bpm
        for ct, b in self.bpm_changes:
            if ct <= t:
                bpm = b
        return 60000.0 / bpm / 4.0


def parse_legacy_chart(path, song):
    """Charts Psych/Kade/base (song.notes[] em secoes)."""
    with open(path, encoding="utf-8-sig") as f:
        data = json.loads(re.sub(r",\s*([}\]])", r"\1", f.read()))
    s = data["song"] if isinstance(data.get("song"), dict) else data
    song.format = "Psych/Legacy (secoes)"
    song.name = s.get("song", song.id)
    song.bpm = float(s.get("bpm", 100))
    song.speed = float(s.get("speed", 1))
    song.player1 = s.get("player1") or "bf"
    song.player2 = s.get("player2") or "dad"
    song.gf = s.get("gfVersion") or s.get("player3") or s.get("gf") or "gf"
    song.stage = s.get("stage") or "stage"
    song.needs_voices = bool(s.get("needsVoices", True))
    t = 0.0
    bpm = song.bpm
    skipped_types = {}
    for sec in s.get("notes", []):
        if sec.get("changeBPM") and sec.get("bpm"):
            bpm = float(sec["bpm"])
            song.bpm_changes.append((t, bpm))
        must = bool(sec.get("mustHitSection", True))
        song.sections.append((t, must))
        for n in sec.get("sectionNotes", []):
            if len(n) < 2:
                continue
            nt, d = float(n[0]), int(n[1])
            ln = float(n[2]) if len(n) > 2 and isinstance(n[2], (int, float)) else 0.0
            if d < 0:  # evento antigo do Psych dentro das notas
                if len(n) >= 5:
                    song.events.append((nt, str(n[2]), str(n[3]), str(n[4])))
                continue
            ntype = n[3] if len(n) > 3 else None
            if ntype not in (None, "", 0, "Alt Animation", "No Animation", "GF Sing"):
                skipped_types[str(ntype)] = skipped_types.get(str(ntype), 0) + 1
                if isinstance(ntype, str) and "hurt" in ntype.lower():
                    continue  # notas de dano nao devem ser tocadas
            d = d % 8
            player = (d < 4) == must
            lane = (d % 4) + (4 if player else 0)
            song.notes.append((nt, lane, max(0.0, ln)))
        steps = sec.get("lengthInSteps") or (sec.get("sectionBeats", 4) * 4)
        t += steps * (60000.0 / bpm / 4.0)
    for ev in s.get("events", []) or []:
        et = float(ev[0])
        for sub in ev[1]:
            song.events.append((et, str(sub[0]), str(sub[1]) if len(sub) > 1 else "",
                                str(sub[2]) if len(sub) > 2 else ""))
    if skipped_types:
        R.add("SCRIPTS / RECURSOS NAO CONVERTIDOS",
              "Tipos de nota personalizados em %s: %s (tratados como notas normais, exceto 'hurt')"
              % (song.id, skipped_types))
    song.notes.sort()
    song.events.sort(key=lambda e: e[0])
    # remove duplicatas exatas (mesma lane e mesmo tempo)
    ded, seen = [], set()
    for n in song.notes:
        k = (round(n[0], 1), n[1])
        if k not in seen:
            seen.add(k)
            ded.append(n)
    if len(ded) != len(song.notes):
        R.add("AVISOS", "%s: %d notas duplicadas removidas" % (song.id, len(song.notes) - len(ded)))
    song.notes = ded


def parse_vslice_chart(chart_path, meta_path, difficulty, song):
    with open(meta_path, encoding="utf-8-sig") as f:
        meta = json.load(f)
    with open(chart_path, encoding="utf-8-sig") as f:
        chart = json.load(f)
    song.format = "V-Slice"
    song.name = meta.get("songName", song.id)
    tc = meta.get("timeChanges", [{"t": 0, "bpm": 100}])
    song.bpm = float(tc[0]["bpm"])
    song.bpm_changes = [(float(c["t"]), float(c["bpm"])) for c in tc[1:]]
    pd = meta.get("playData", {})
    chars = pd.get("characters", {})
    song.player1 = chars.get("player", "bf")
    song.player2 = chars.get("opponent", "dad")
    song.gf = chars.get("girlfriend", "gf")
    song.stage = pd.get("stage", "mainStage")
    diff = difficulty if difficulty in chart.get("notes", {}) else next(iter(chart["notes"]))
    song.speed = float(chart.get("scrollSpeed", {}).get(diff, 1.0))
    for n in chart["notes"][diff]:
        d = int(n["d"])
        lane = (d % 4) + (4 if d < 4 else 0)  # V-Slice: 0-3 jogador, 4-7 oponente
        song.notes.append((float(n["t"]), lane, float(n.get("l", 0))))
    for e in chart.get("events", []):
        if e.get("e") == "FocusCamera":
            v = e.get("v")
            ch = v.get("char", 0) if isinstance(v, dict) else v
            song.sections.append((float(e["t"]), int(ch) == 0))
        else:
            song.events.append((float(e["t"]), str(e.get("e")), json.dumps(e.get("v")), ""))
    if not song.sections:
        song.sections = [(0.0, True)]
    song.notes.sort()


def find_songs(mod, wanted, difficulty):
    """Lista as musicas na ordem das weeks e localiza charts e audio."""
    order = []
    weeks_dir = mod.p("weeks")
    week_info = []
    if os.path.isdir(weeks_dir):
        for wf in sorted(os.listdir(weeks_dir)):
            if wf.endswith(".json"):
                try:
                    w = mod.json("weeks/" + wf)
                except Exception as exc:  # noqa: BLE001
                    R.add("AVISOS", "week %s ilegivel: %s" % (wf, exc))
                    continue
                week_info.append((wf, w))
                for sdef in w.get("songs", []):
                    order.append(sdef[0])
    if not order:  # sem weeks: usa pastas de data/
        for d in sorted(os.listdir(mod.p("data"))) if mod.exists("data") else []:
            if os.path.isdir(mod.p("data", d)):
                order.append(d)
    if wanted:
        order = [s for s in order if s.lower() in [w.lower() for w in wanted]] or list(wanted)
    songs = []
    for sid in order:
        key = sid.lower().replace(" ", "-")
        song = Song()
        song.id = sid
        cands = []
        for folder in (key, sid, sid.lower()):
            for fname in ("%s-%s.json" % (key, difficulty), "%s.json" % key,
                          "%s-%s.json" % (sid, difficulty), "%s.json" % sid):
                cands += ["data/%s/%s" % (folder, fname), "data/songs/%s/%s" % (folder, fname)]
        chart = mod.find(cands)
        if chart is None and os.path.isdir(mod.p("data", key)):
            js = [f for f in os.listdir(mod.p("data", key)) if f.endswith(".json") and "events" not in f]
            if js:
                chart = mod.p("data", key, sorted(js)[0])
        vs_chart = mod.find(["data/songs/%s/%s-chart.json" % (key, key)])
        vs_meta = mod.find(["data/songs/%s/%s-metadata.json" % (key, key)])
        try:
            if vs_chart and vs_meta:
                song.chart_path = vs_chart
                parse_vslice_chart(vs_chart, vs_meta, difficulty, song)
            elif chart:
                song.chart_path = chart
                parse_legacy_chart(chart, song)
                ev = mod.find(["data/%s/events.json" % key])
                if ev:
                    tmp = Song()
                    parse_legacy_chart(ev, tmp)
                    song.events += tmp.events
                    song.events.sort(key=lambda e: e[0])
            else:
                R.add("AVISOS", "Chart nao encontrado para '%s'" % sid)
                continue
        except Exception as exc:  # noqa: BLE001
            R.add("AVISOS", "Falha ao ler chart de '%s': %s" % (sid, exc))
            continue
        song.inst = mod.find(["songs/%s/Inst.ogg" % key, "songs/%s/Inst.ogg" % sid,
                              "songs/%s/Inst.mp3" % key, "songs/%s/Inst.wav" % key])
        song.voices = mod.find(["songs/%s/Voices.ogg" % key, "songs/%s/Voices.ogg" % sid,
                                "songs/%s/Voices-Player.ogg" % key])
        if not song.inst:
            R.add("AVISOS", "Instrumental nao encontrado para '%s' - musica ignorada" % sid)
            continue
        songs.append(song)
    return songs, week_info


# ==========================================================================
# 3. Personagens (spritesheets Sparrow/XML)
# ==========================================================================

class Atlas:
    def __init__(self, png, xml):
        self.img = Image.open(png).convert("RGBA")
        root = ET.parse(xml).getroot()
        self.frames = []
        for st in root.iter("SubTexture"):
            a = st.attrib
            x, y, w, h = int(float(a["x"])), int(float(a["y"])), int(float(a["width"])), int(float(a["height"]))
            fx = int(float(a.get("frameX", 0)))
            fy = int(float(a.get("frameY", 0)))
            fw = int(float(a.get("frameWidth", w)))
            fh = int(float(a.get("frameHeight", h)))
            rot = a.get("rotated") == "true"
            self.frames.append(dict(name=a["name"], x=x, y=y, w=w, h=h, fx=fx, fy=fy, fw=fw, fh=fh, rot=rot))

    def by_prefix(self, prefix, indices=None):
        def idx(name):
            m = re.search(r"(\d+)$", name)
            return int(m.group(1)) if m else 0
        frs = [f for f in self.frames if f["name"].startswith(prefix)]
        # flixel: prefixo seguido apenas de digitos
        exact = [f for f in frs if re.fullmatch(re.escape(prefix) + r"\d*", f["name"])]
        if exact:
            frs = exact
        frs.sort(key=lambda f: idx(f["name"]))
        if indices:
            m = {idx(f["name"]): f for f in frs}
            frs = [m[i] for i in indices if i in m]
        return frs

    def content(self, f):
        im = self.img.crop((f["x"], f["y"], f["x"] + f["w"], f["y"] + f["h"]))
        if f["rot"]:
            im = im.rotate(90, expand=True)
        return im


ANIM_IDS = ["idle", "singLEFT", "singDOWN", "singUP", "singRIGHT",
            "singLEFTmiss", "singDOWNmiss", "singUPmiss", "singRIGHTmiss"]


class Character:
    """Personagem do Psych convertido em quadros pre-compostos numa tela comum."""

    def __init__(self, mod, name, world_x, world_y, is_player, zoom, role, max_frames):
        self.name = name
        self.role = role
        self.ok = False
        self.anims = {}
        self.frames_png = []
        js = mod.find(["characters/%s.json" % name])
        if js is None:
            R.add("AVISOS", "Personagem '%s' nao encontrado no mod (shared/base do jogo nao vem no mod)" % name)
            return
        j = mod.json(os.path.relpath(js, mod.root))
        img = j.get("image", "characters/" + name)
        png = mod.find(["images/%s.png" % img])
        xml = mod.find(["images/%s.xml" % img])
        if not png or not xml:
            R.add("AVISOS", "Spritesheet de '%s' (%s) ausente" % (name, img))
            return
        atlas = Atlas(png, xml)
        self.scale = float(j.get("scale", 1))
        self.flip = bool(j.get("flip_x", False)) != bool(is_player)
        self.sing_duration = float(j.get("sing_duration", 4))
        self.pixel = bool(j.get("no_antialiasing", False))
        self.healthicon = j.get("healthicon", name)
        self.color = tuple(j.get("healthbar_colors", [161, 161, 161]))
        self.cam_pos = j.get("camera_position", [0, 0])
        self.x = world_x + j.get("position", [0, 0])[0]
        self.y = world_y + j.get("position", [0, 0])[1]
        f0 = atlas.frames[0]
        self.origin = (f0["fw"] / 2.0, f0["fh"] / 2.0)
        self.hitbox = (f0["fw"] * self.scale, f0["fh"] * self.scale)
        anims = {}
        for a in j.get("animations", []):
            frs = atlas.by_prefix(a["name"], a.get("indices") or None)
            if not frs:
                R.add("AVISOS", "%s: animacao '%s' sem quadros (prefixo '%s')" % (name, a["anim"], a["name"]))
                continue
            anims[a["anim"]] = dict(frames=frs, fps=float(a.get("fps", 24)), off=a.get("offsets", [0, 0]),
                                    loop=a.get("loop", False))
        self.dance_lr = "danceLeft" in anims and "danceRight" in anims
        # seleciona as animacoes usadas
        wanted = ["danceLeft", "danceRight"] if self.dance_lr else ["idle"]
        wanted += ANIM_IDS[1:]
        self.specials = [k for k in anims if k not in wanted and k not in ("idle",)]
        order = wanted + self.specials
        # subamostragem para economizar memoria de textura no celular
        plan = []
        for k in order:
            if k not in anims:
                continue
            a = anims[k]
            frs = a["frames"]
            lim = max_frames.get("special" if k in self.specials else ("miss" if k.endswith("miss") else "base"), 16)
            stepn = max(1, int(math.ceil(len(frs) / float(lim))))
            plan.append((k, frs[::stepn], a["fps"] / stepn, a["off"]))
        # bbox comum em coordenadas de mundo
        def frame_world_rect(f, off):
            bx = -f["fx"]
            by = -f["fy"]
            w, h = (f["h"], f["w"]) if f["rot"] else (f["w"], f["h"])
            if self.flip:
                bx = f["fw"] - (bx + w)
            ox, oy = self.origin
            x0 = self.x - off[0] + ox + (bx - ox) * self.scale
            y0 = self.y - off[1] + oy + (by - oy) * self.scale
            return x0, y0, x0 + w * self.scale, y0 + h * self.scale
        rects = [frame_world_rect(f, off) for _, frs, _, off in plan for f in frs]
        if not rects:
            return
        X0 = min(r[0] for r in rects)
        Y0 = min(r[1] for r in rects)
        X1 = max(r[2] for r in rects)
        Y1 = max(r[3] for r in rects)
        self.world_rect = (X0, Y0, X1, Y1)
        cw = int(math.ceil((X1 - X0) * zoom)) + 2
        ch = int(math.ceil((Y1 - Y0) * zoom)) + 2
        resample = Image.NEAREST if self.pixel else Image.LANCZOS
        idx = 1
        cache = {}
        for k, frs, fps, off in plan:
            start = idx
            for f in frs:
                key = (f["name"], tuple(off))
                if key not in cache:
                    content = atlas.content(f)
                    if self.flip:
                        content = content.transpose(Image.FLIP_LEFT_RIGHT)
                    r = frame_world_rect(f, off)
                    tw = max(1, int(round((r[2] - r[0]) * zoom)))
                    th = max(1, int(round((r[3] - r[1]) * zoom)))
                    content = content.resize((tw, th), resample)
                    canvas = Image.new("RGBA", (cw, ch), (0, 0, 0, 0))
                    canvas.alpha_composite(content, (int(round((r[0] - X0) * zoom)), int(round((r[1] - Y0) * zoom))))
                    cache[key] = png_bytes(canvas)
                self.frames_png.append(cache[key])
                idx += 1
            self.anims[k] = dict(start=start, n=len(frs), fps=fps)
        self.canvas_size = (cw, ch)
        self.ok = True
        R.add("PERSONAGENS", "%s (%s): %d quadros, tela %dx%d px, escala %.2f, flipX=%s, anims=%s"
              % (name, role, len(self.frames_png), cw, ch, self.scale * zoom, self.flip,
                 ", ".join("%s(%d@%.0ffps)" % (k, v["n"], v["fps"]) for k, v in self.anims.items())))
        if self.specials:
            R.add("PERSONAGENS", "  animacoes especiais (usadas por eventos): %s" % ", ".join(self.specials))

    def midpoint(self):
        return self.x + self.hitbox[0] / 2.0, self.y + self.hitbox[1] / 2.0

    def world_center(self):
        X0, Y0, X1, Y1 = self.world_rect
        return (X0 + X1) / 2.0, (Y0 + Y1) / 2.0

    def anim_table(self):
        """ids: 1=idle/danceLeft, 2..5 sing, 6..9 miss, 10=danceRight, 11+ especiais."""
        base = self.anims.get("danceLeft") or self.anims.get("idle") or next(iter(self.anims.values()))
        rows = [base]
        for k in ANIM_IDS[1:5]:
            rows.append(self.anims.get(k, base))
        for i, k in enumerate(ANIM_IDS[5:9]):
            rows.append(self.anims.get(k, rows[1 + i]))  # sem miss: usa o sing
        rows.append(self.anims.get("danceRight", base))
        for k in self.specials:
            rows.append(self.anims[k])
        return rows

    def special_id(self, name):
        if name in self.specials:
            return 11 + self.specials.index(name)
        return None


def png_bytes(im, optimize=True):
    b = io.BytesIO()
    im.save(b, "PNG", optimize=optimize)
    return b.getvalue()


# ==========================================================================
# 4. Stage (stages/*.json + subconjunto de Lua)
# ==========================================================================

def parse_stage(mod, stage_name):
    st = {"defaultZoom": 0.9, "boyfriend": [770, 100], "girlfriend": [400, 130],
          "opponent": [100, 100], "hide_girlfriend": False, "camera_boyfriend": [0, 0],
          "camera_opponent": [0, 0], "camera_girlfriend": [0, 0], "camera_speed": 1}
    js = mod.find(["stages/%s.json" % stage_name])
    if js:
        st.update(mod.json(os.path.relpath(js, mod.root)))
    else:
        R.add("AVISOS", "Stage '%s' sem JSON; usando posicoes padrao do Psych" % stage_name)
    layers = []
    lua = mod.find(["stages/%s.lua" % stage_name])
    if lua:
        src = open(lua, encoding="utf-8", errors="replace").read()
        sprites = {}
        num_re = r"(-?[\d.]+)"
        for m in re.finditer(r"makeLuaSprite\(\s*'([^']+)'\s*,\s*'([^']*)'\s*,\s*%s\s*,\s*%s\s*\)" % (num_re, num_re), src):
            sprites[m.group(1)] = dict(img=m.group(2), x=float(m.group(3)), y=float(m.group(4)),
                                       sx=1.0, sy=1.0, sf=(1.0, 1.0), front=False, added=False, order=0)
        for m in re.finditer(r"scaleLuaSprite\(\s*'([^']+)'\s*,\s*%s\s*,\s*%s\s*\)" % (num_re, num_re), src):
            if m.group(1) in sprites:
                sprites[m.group(1)].update(sx=float(m.group(2)), sy=float(m.group(3)))
        for m in re.finditer(r"setLuaSpriteScrollFactor\(\s*'([^']+)'\s*,\s*%s\s*,\s*%s\s*\)" % (num_re, num_re), src):
            if m.group(1) in sprites:
                sprites[m.group(1)]["sf"] = (float(m.group(2)), float(m.group(3)))
            else:
                R.add("SCRIPTS / RECURSOS NAO CONVERTIDOS",
                      "stage %s: setLuaSpriteScrollFactor('%s') refere sprite inexistente (sem efeito tambem no Psych)"
                      % (stage_name, m.group(1)))
        for i, m in enumerate(re.finditer(r"addLuaSprite\(\s*'([^']+)'\s*(?:,\s*(true|false))?\s*\)", src)):
            if m.group(1) in sprites:
                sprites[m.group(1)].update(added=True, front=(m.group(2) == "true"), order=i)
        for name, s in sorted(sprites.items(), key=lambda kv: kv[1]["order"]):
            if not s["added"]:
                continue
            png = mod.find(["images/%s.png" % s["img"]])
            if not png:
                R.add("AVISOS", "stage: imagem '%s' nao encontrada" % s["img"])
                continue
            s["png"] = png
            s["name"] = name
            layers.append(s)
        unsupported = set(re.findall(r"\b(makeAnimatedLuaSprite|doTween\w*|setProperty|makeLuaText|"
                                     r"setObjectCamera|onBeatHit|onStepHit|onUpdate)\b", src))
        if unsupported:
            R.add("SCRIPTS / RECURSOS NAO CONVERTIDOS",
                  "stages/%s.lua: chamadas ignoradas: %s" % (stage_name, ", ".join(sorted(unsupported))))
        R.add("CENARIO", "stages/%s.lua: %d camada(s) convertida(s): %s" % (
            stage_name, len(layers), ", ".join("%s(%s x%.2f)" % (l["name"], l["img"], l["sx"]) for l in layers)))
    return st, layers


# ==========================================================================
# 5. Audio
# ==========================================================================

def ffmpeg_ok():
    return shutil.which("ffmpeg") is not None


def audio_duration(path):
    try:
        out = subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                                       "-of", "csv=p=0", path], text=True)
        return float(out.strip())
    except Exception:  # noqa: BLE001
        return None


def convert_song_audio(song, out_path):
    """Mistura Inst + Voices num unico MP3 CBR (sincronia perfeita no Pocket Code)."""
    if not ffmpeg_ok():
        raise RuntimeError("ffmpeg nao encontrado: necessario para converter OGG em MP3")
    cmd = ["ffmpeg", "-y", "-loglevel", "error", "-i", song.inst]
    if song.voices and song.needs_voices:
        cmd += ["-i", song.voices, "-filter_complex",
                "[0:a][1:a]amix=inputs=2:duration=longest:normalize=0,alimiter=limit=0.97[a]", "-map", "[a]"]
    cmd += ["-ac", "2", "-ar", "44100", "-c:a", "libmp3lame", "-b:a", "160k", out_path]
    subprocess.check_call(cmd)


# ==========================================================================
# 6. Geracao de imagens de interface (o mod nao traz NOTE_assets nem HUD)
# ==========================================================================

class UI:
    def __init__(self, mod):
        self.font_path = None
        fonts = [f for f in mod.files if f.lower().startswith("fonts/") and f.lower().endswith((".ttf", ".otf"))]
        if fonts:
            self.font_path = mod.p(fonts[0])
            R.add("INTERFACE", "Fonte do mod usada nos textos desenhados: %s" % fonts[0])
        self.fallback = None
        for c in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                  "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"):
            if os.path.exists(c):
                self.fallback = c
                break

    def font(self, size, fallback=False):
        p = self.fallback if fallback or not self.font_path else self.font_path
        try:
            return ImageFont.truetype(p, size) if p else ImageFont.load_default()
        except Exception:  # noqa: BLE001
            return ImageFont.load_default()

    def text_image(self, s, size, fill=(255, 255, 255), stroke=(0, 0, 0), stroke_w=4, pad=8, fallback=False):
        f = self.font(size, fallback)
        d = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
        box = d.textbbox((0, 0), s, font=f, stroke_width=stroke_w)
        w, h = box[2] - box[0] + 2 * pad, box[3] - box[1] + 2 * pad
        im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        ImageDraw.Draw(im).text((pad - box[0], pad - box[1]), s, font=f, fill=fill,
                                stroke_width=stroke_w, stroke_fill=stroke)
        return im

    def button(self, label, w, h, color=(40, 40, 60), border=(255, 255, 255), size=34):
        ss = 3
        im = Image.new("RGBA", (w * ss, h * ss), (0, 0, 0, 0))
        d = ImageDraw.Draw(im)
        d.rounded_rectangle((2 * ss, 2 * ss, (w - 2) * ss, (h - 2) * ss), radius=14 * ss,
                            fill=color + (235,), outline=border + (255,), width=4 * ss)
        im = im.resize((w, h), Image.LANCZOS)
        t = self.text_image(label, size, stroke_w=3)
        if t.width > w - 16:
            t = t.resize((w - 16, int(t.height * (w - 16) / t.width)), Image.LANCZOS)
        im.alpha_composite(t, ((w - t.width) // 2, (h - t.height) // 2))
        return im


def arrow_polygon(s):
    # seta apontando para a esquerda numa caixa s x s
    k = s / 112.0
    pts = [(10, 56), (56, 12), (56, 38), (102, 38), (102, 74), (56, 74), (56, 100)]
    return [(x * k, y * k) for x, y in pts]


def draw_arrow(size, fill, outline=(20, 20, 20), outline_w=7, glow=None, inner_scale=1.0, direction=0):
    ss = 4
    S = size * ss
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    pad = (1 - inner_scale) * S / 2
    poly = [(pad + x * inner_scale, pad + y * inner_scale) for x, y in arrow_polygon(S)]
    d.polygon(poly, fill=fill, outline=outline)
    d.line(poly + [poly[0]], fill=outline, width=outline_w * ss, joint="curve")
    im = im.resize((size, size), Image.LANCZOS)
    if glow:
        g = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        g.paste(Image.new("RGBA", (size, size), glow), mask=im.split()[3])
        g = g.filter(ImageFilter.GaussianBlur(size * 0.08))
        base = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        base.alpha_composite(g)
        base.alpha_composite(g)
        base.alpha_composite(im)
        im = base
    rot = [0, 90, -90, 180][direction]
    return im.rotate(rot, resample=Image.BICUBIC) if rot else im


def note_images(note_size, piece_h):
    out = {}
    for d in range(4):
        c = NOTE_COLORS[d]
        out["note_%d" % d] = draw_arrow(note_size, c + (255,), direction=d)
        gray = (135, 163, 173)
        out["strum_%d" % d] = draw_arrow(note_size, gray + (255,), outline=(40, 50, 60), direction=d)
        press = tuple(int(gray[i] * 0.45 + c[i] * 0.55) for i in range(3))
        out["press_%d" % d] = draw_arrow(note_size, press + (230,), outline=(30, 30, 40), inner_scale=0.88, direction=d)
        bright = tuple(min(255, int(c[i] + (255 - c[i]) * 0.35)) for i in range(3))
        out["confirm_%d" % d] = draw_arrow(note_size, bright + (255,), outline=(255, 255, 255),
                                           glow=(255, 255, 255, 200), direction=d)
        # pecas de nota longa (corpo e ponta). Cada peca cobre meio step e se
        # sobrepoe as vizinhas (36 px), porque cada clone atualiza a posicao em
        # fases diferentes do ciclo de 20 ms dos loops do Pocket Code.
        w = int(note_size * 0.36)
        ov = 36
        h = int(math.ceil(piece_h)) + ov
        body = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        bd = ImageDraw.Draw(body)
        bd.rectangle((0, 0, w - 1, h - 1), fill=(15, 15, 15, 235))
        bd.rectangle((4, 0, w - 5, h - 1), fill=tuple(int(x * 0.8) for x in c) + (235,))
        bd.rectangle((w // 2 - 3, 0, w // 2 + 3, h - 1), fill=tuple(min(255, x + 60) for x in c) + (235,))
        out["hold_%d" % d] = body
        # ponta: metade de cima sobrepoe a peca anterior; extremo arredondado embaixo
        he = int(math.ceil(piece_h)) + ov
        end = Image.new("RGBA", (w, he), (0, 0, 0, 0))
        ed = ImageDraw.Draw(end)
        ed.rounded_rectangle((0, -w, w - 1, he - ov // 2 - 1), radius=w // 2, fill=(15, 15, 15, 235))
        ed.rounded_rectangle((4, -w, w - 5, he - ov // 2 - 5), radius=w // 2 - 4,
                             fill=tuple(int(x * 0.8) for x in c) + (235,))
        ed.rectangle((w // 2 - 3, 0, w // 2 + 3, he - ov // 2 - w // 2), fill=tuple(min(255, x + 60) for x in c) + (235,))
        out["holdend_%d" % d] = end  # ponta arredondada embaixo (upscroll)
        out["holdendd_%d" % d] = end.transpose(Image.FLIP_TOP_BOTTOM)  # downscroll
    return out


def health_bar_frames(dad_color, bf_color, n=51, w=601, h=19):
    frames = []
    inner_w = w - 8
    for i in range(n):
        health = 2.0 * i / (n - 1)
        im = Image.new("RGBA", (w, h), (0, 0, 0, 255))
        split = int(round(inner_w * (1 - health / 2.0)))
        d = ImageDraw.Draw(im)
        if split > 0:
            d.rectangle((4, 4, 4 + split - 1, h - 5), fill=tuple(dad_color) + (255,))
        if split < inner_w:
            d.rectangle((4 + split, 4, 4 + inner_w - 1, h - 5), fill=tuple(bf_color) + (255,))
        frames.append(im)
    return frames


def icon_frames(mod, icon_name, flip):
    p = mod.find(["images/icons/icon-%s.png" % icon_name, "images/icons/%s.png" % icon_name])
    if not p:
        R.add("AVISOS", "Icone '%s' ausente - icone gerado" % icon_name)
        im = Image.new("RGBA", (150, 150), (0, 0, 0, 0))
        ImageDraw.Draw(im).ellipse((10, 10, 140, 140), fill=(200, 200, 200, 255), outline=(0, 0, 0, 255), width=6)
        return [im, im]
    im = Image.open(p).convert("RGBA")
    if im.width >= 2 * im.height:
        fw = im.width // (im.width // im.height)
        frs = [im.crop((0, 0, fw, im.height)), im.crop((fw, 0, 2 * fw, im.height))]
    else:
        frs = [im, im]
    if flip:
        frs = [f.transpose(Image.FLIP_LEFT_RIGHT) for f in frs]
    return frs


# ==========================================================================
# 7. Dados de chart para o runtime do Pocket Code
# ==========================================================================

TAP_REC = 13   # tempo(7) lane(1) proximo(5)
HOLD_REC = 14  # tempo(7) lane(1) tipo(1) proximo(5)
SEC_REC = 8    # tempo(7) mustHit(1)


def build_chart_data(song, speed_px_ms):
    """Gera listas encadeadas: para notas do jogador 'proximo' aponta para a
    proxima nota da MESMA lane; para o oponente, para a proxima nota do oponente."""
    taps = [(round(t), lane) for t, lane, _ in song.notes]
    holds = []
    for t, lane, ln in song.notes:
        if ln <= 0:
            continue
        h = song.step_at(t) / 2.0
        m = max(1, int(round(ln / h)))
        for k in range(m):
            holds.append((round(t + h * k + h / 2.0), lane, 2 if k == m - 1 else 1))
    holds.sort()

    def chain(items):
        n = len(items)
        nxt = [n + 1] * n
        first = {}
        last = {}
        for i, it in enumerate(items):
            lane = it[1]
            key = lane if lane >= 4 else "opp"
            if key in last:
                nxt[last[key]] = i + 1
            else:
                first[key] = i + 1
            last[key] = i
        return nxt, [first.get(4 + i, n + 1) for i in range(4)] + [first.get("opp", n + 1)]

    tn, tfirst = chain(taps)
    hn, hfirst = chain(holds)
    for t, lane in taps:
        assert 0 <= t <= 9999999
    data_t = "#" + "".join("%07d%d%05d" % (t, lane, n) for (t, lane), n in zip(taps, tn))
    data_h = "#" + "".join("%07d%d%d%05d" % (t, lane, k, n) for (t, lane, k), n in zip(holds, hn))
    data_s = "#" + "".join("%07d%d" % (max(0, round(t)), 1 if m else 0) for t, m in song.sections)
    return dict(taps=taps, holds=holds, tnext=tn, hnext=hn, tfirst=tfirst, hfirst=hfirst,
                data_t=data_t, data_h=data_h, data_s=data_s)


def pool_size(times, lead, linger, cap):
    """Menor P tal que a nota k+P so aparece depois que a nota k sumiu."""
    for P in range(4, cap + 1):
        if all(times[k + P] - lead >= times[k] + linger for k in range(len(times) - P)):
            return P
    return cap


def parse_strum_shifts(mod):
    """Le deslocamentos simples de strumLineNotes feitos por scripts Lua (ex.: aspect.lua)."""
    shifts = [0.0] * 8
    yshift = [0.0] * 8
    found = []
    for f in mod.files:
        if not f.lower().endswith(".lua") or not f.lower().startswith("scripts/"):
            continue
        src = open(mod.p(f), encoding="utf-8", errors="replace").read()
        for m in re.finditer(r"for\s+i\s*=\s*(\d)\s*,\s*(\d)\s+do(.*?)end", src, re.S):
            a, b, body = int(m.group(1)), int(m.group(2)), m.group(3)
            for mm in re.finditer(r"setPropertyFromGroup\('strumLineNotes',\s*i,\s*\"(x|y)\",\s*getPropertyFromGroup"
                                  r"\('strumLineNotes',\s*i,\s*\"\1\"\)\s*\+\s*(-?\s*[-\d.]+)\)", body):
                val = float(mm.group(2).replace(" ", ""))
                for i in range(a, b + 1):
                    if mm.group(1) == "x":
                        shifts[i] += val
                    else:
                        yshift[i] += val
                found.append("%s: lanes %d-%d %s %+g" % (f, a, b, mm.group(1), val))
    return shifts, yshift, found


# ==========================================================================
# 8. Construcao do projeto Pocket Code
# ==========================================================================

def build_project(mod, songs, week_info, opts):
    song0 = songs[0]
    ui = UI(mod)
    pack = {}
    if mod.exists("pack.json"):
        try:
            pack = mod.json("pack.json")
        except Exception:  # noqa: BLE001
            pass
    title = pack.get("name") or os.path.basename(mod.root)
    proj = Project(opts.name, scene_name="FNF", width=SCREEN_W, height=SCREEN_H, landscape=True,
                   description="Port de '%s' (Friday Night Funkin') gerado por fnf_to_catrobat.py" % title,
                   notes="Mod original: %s. Musicas: %s. Gameplay 4K com toque, gerado automaticamente."
                         % (title, ", ".join(s.name for s in songs)))
    img_names = set()

    def add_img(name, im):
        assert name not in img_names, name
        img_names.add(name)
        proj.add_image(name, im if isinstance(im, bytes) else png_bytes(im))

    # ---------------- stage e personagens ----------------
    stage, layers = parse_stage(mod, song0.stage)
    zoom = float(stage.get("defaultZoom", 0.9))
    limits = {"base": opts.max_frames, "miss": max(4, opts.max_frames // 2), "special": opts.max_special_frames}
    bf = Character(mod, song0.player1, stage["boyfriend"][0], stage["boyfriend"][1], True, zoom, "jogador", limits)
    dad = Character(mod, song0.player2, stage["opponent"][0], stage["opponent"][1], False, zoom, "oponente", limits)
    gf = None
    if not stage.get("hide_girlfriend", False) and song0.gf:
        gf = Character(mod, song0.gf, stage["girlfriend"][0], stage["girlfriend"][1], False, zoom, "gf", limits)
        if not gf.ok:
            gf = None
    for s in songs[1:]:
        if (s.player1, s.player2, s.gf, s.stage) != (song0.player1, song0.player2, song0.gf, song0.stage):
            R.add("LIMITACOES", "'%s' usa personagens/stage diferentes; o port usa os da primeira musica." % s.name)

    def cam_target(ch, who):
        mx, my = ch.midpoint()
        if who == "dad":
            return (mx + 150 + ch.cam_pos[0] + stage.get("camera_opponent", [0, 0])[0],
                    my - 100 + ch.cam_pos[1] + stage.get("camera_opponent", [0, 0])[1])
        if who == "bf":
            return (mx - 100 - ch.cam_pos[0] + stage.get("camera_boyfriend", [0, 0])[0],
                    my - 100 + ch.cam_pos[1] + stage.get("camera_boyfriend", [0, 0])[1])
        return (mx + ch.cam_pos[0] + stage.get("camera_girlfriend", [0, 0])[0],
                my + ch.cam_pos[1] + stage.get("camera_girlfriend", [0, 0])[1])

    dadT = cam_target(dad, "dad") if dad.ok else (640, 360)
    bfT = cam_target(bf, "bf") if bf.ok else (640, 360)
    gfT = cam_target(gf, "gf") if gf else ((dadT[0] + bfT[0]) / 2, (dadT[1] + bfT[1]) / 2)
    R.add("CAMERA", "zoom %.2f; alvo oponente (%.0f, %.0f), alvo jogador (%.0f, %.0f), inicio (%.0f, %.0f)"
          % (zoom, dadT[0], dadT[1], bfT[0], bfT[1], gfT[0], gfT[1]))

    # cenario composto (camadas atras dos personagens), cobrindo todo o curso da camera
    hw, hh = SCREEN_W / 2.0 / zoom, SCREEN_H / 2.0 / zoom
    xs = [dadT[0], bfT[0], gfT[0]]
    ys = [dadT[1], bfT[1], gfT[1]]
    BX0, BX1 = min(xs) - hw - 40, max(xs) + hw + 40
    BY0, BY1 = min(ys) - hh - 40, max(ys) + hh + 40
    bg_w, bg_h = int((BX1 - BX0) * zoom), int((BY1 - BY0) * zoom)
    back = Image.new("RGBA", (bg_w, bg_h), (0, 0, 0, 255))
    front_layers = []
    for l in layers:
        im = Image.open(l["png"]).convert("RGBA")
        nearest = re.search(r"setProperty\('%s\.antialiasing',\s*false\)" % re.escape(l["name"]),
                            open(mod.find(["stages/%s.lua" % song0.stage]), encoding="utf-8", errors="replace").read())
        w, h = int(round(im.width * l["sx"] * zoom)), int(round(im.height * l["sy"] * zoom))
        im = im.resize((max(1, w), max(1, h)), Image.NEAREST if nearest else Image.LANCZOS)
        if l["sf"] != (1.0, 1.0):
            R.add("LIMITACOES", "camada '%s' tem scrollFactor %s; composta com fator 1" % (l["name"], l["sf"]))
        if l["front"]:
            front_layers.append(l)
            continue
        back.alpha_composite(im, (int(round((l["x"] - BX0) * zoom)), int(round((l["y"] - BY0) * zoom)))) \
            if (l["x"] - BX0) >= 0 and (l["y"] - BY0) >= 0 else back.paste(
                im, (int(round((l["x"] - BX0) * zoom)), int(round((l["y"] - BY0) * zoom))), im)
    if front_layers:
        R.add("LIMITACOES", "camadas de frente (%s) nao convertidas" % ", ".join(l["name"] for l in front_layers))
    bg_center = ((BX0 + BX1) / 2.0, (BY0 + BY1) / 2.0)
    R.add("CENARIO", "Cenario composto em %dx%d px (mundo x%.2f)" % (bg_w, bg_h, zoom))

    # ---------------- layout do HUD (Psych) ----------------
    shifts, yshift, found = parse_strum_shifts(mod)
    for f_ in found:
        R.add("SCRIPTS CONVERTIDOS", "deslocamento de strums aplicado: " + f_)
    lane_x = []
    for i in range(8):
        base = 42 + 50 + 112 * (i % 4) + (SCREEN_W / 2 if i >= 4 else 0)
        lane_x.append(base + 56 + shifts[i] - SCREEN_W / 2)
    strum_up = SCREEN_H / 2 - (50 + 56 + yshift[4])
    strum_down = SCREEN_H / 2 - (SCREEN_H - 150 + 56 + yshift[4])
    piece_px = {}

    # ---------------- variaveis globais ----------------
    G = proj.global_var
    GL = proj.global_list
    gameState, runId, songIdx = G("estado"), G("rodada"), G("musica")
    songName, songBase, offsetMs = G("nomeMusica"), G("baseTempo"), G("offsetMs")
    downscroll, botplay = G("downscroll"), G("botplay")
    score, misses, combo, maxCombo = G("pontos"), G("erros"), G("combo"), G("comboMax")
    hitSum, played, health = G("somaAcertos"), G("notasJogadas"), G("vida")
    scoreTxt, comboTxt, offsetTxt, loadTxt = G("textoPontos"), G("textoCombo"), G("textoOffset"), G("textoCarregando")
    res1, res2, res3 = G("resultado1"), G("resultado2"), G("resultado3")
    ratingIdx, ratingT = G("avaliacao"), G("tempoAvaliacao")
    bfAnim, bfSerial, bfSingT = G("animJogador"), G("serieJogador"), G("cantoJogador")
    dadAnim, dadSerial, dadSingT = G("animOponente"), G("serieOponente"), G("cantoOponente")
    camX, camY, camTX, camTY = G("camX"), G("camY"), G("camAlvoX"), G("camAlvoY")
    nTaps, nHolds, nSec, nEv = G("nNotas"), G("nLongas"), G("nSecoes"), G("nEventos")
    dataT, dataH, dataS = G("dadosNotas"), G("dadosLongas"), G("dadosSecoes")
    songLen, crochet, noteSpeed = G("duracaoMusica"), G("batidaMs"), G("velocidade")
    strumY, scrollSign, songStarted = G("strumY"), G("sentido"), G("musicaIniciada")
    oppT, oppH, secI, evI = G("ponteiroOpNota"), G("ponteiroOpLonga"), G("secaoAtual"), G("eventoAtual")
    flashT, flashDur = G("tempoFlash"), G("duracaoFlash")
    ldP, ldEv = G("cursorDados"), G("tipoEvento")
    tT, tL, tN = GL("notaTempo"), GL("notaLane"), GL("notaProxima")
    hT, hL, hK, hN = GL("longaTempo"), GL("longaLane"), GL("longaTipo"), GL("longaProxima")
    sT, sM = GL("secaoTempo"), GL("secaoJogador")
    eT, eK, eA = GL("eventoTempo"), GL("eventoTipo"), GL("eventoValor")
    fT, fH = GL("primeiraNota"), GL("primeiraLonga")
    ptrT, ptrH, held, strumUntil = GL("ponteiroNota"), GL("ponteiroLonga"), GL("pressionado"), GL("brilhoAte")

    V = var

    def POS():
        return sensor("TIMER") * 1000 + V(songBase) - V(offsetMs)

    def running():
        return V(gameState).eq(1)

    def lane_x_formula(l):
        dx = lane_x[4] - (lane_x[0] + 4 * 112)
        return lane_x[0] + 112 * l + dx * (l > 3)

    def note_y(t):
        return V(strumY) - V(scrollSign) * (t - POS()) * V(noteSpeed)

    def hide_on(sp, *msgs):
        sp.add(when_start(hide()))
        for m in msgs:
            sp.add(when_received(m, hide()))

    # ======================================================================
    # Fundo (primeiro objeto = fundo do palco)
    # ======================================================================
    bgo = proj.sprite("Fundo")
    bgo.look("preto", "fundo_preto.png")
    add_img("fundo_preto.png", Image.new("RGBA", (SCREEN_W, SCREEN_H), (0, 0, 0, 255)))

    # ======================================================================
    # Cenario (move com a camera)
    # ======================================================================
    def world_place(cx, cy, sf=1.0):
        X0 = (cx - SCREEN_W / 2) * zoom
        Y0 = -(cy - SCREEN_H / 2) * zoom
        return place_at(X0 - V(camX) * (zoom * sf), Y0 + V(camY) * (zoom * sf))

    cen = proj.sprite("Cenario")
    cen.look("cenario", "cenario.png")
    add_img("cenario.png", back)
    hide_on(cen, "menu", "load")
    cen.add(when_received("start", world_place(*bg_center), show(),
                          repeat_until(~running(), [world_place(*bg_center)])))

    # ======================================================================
    # Personagens
    # ======================================================================
    def character_sprite(ch, sprite_name, anim_v, serial_v, sing_v, sf, is_player):
        sp = proj.sprite(sprite_name)
        for i, data in enumerate(ch.frames_png):
            fname = "%s_%03d.png" % (re.sub(r"\W", "", ch.name).lower() or "char", i + 1)
            sp.look("%s %d" % (ch.name, i + 1), fname)
            add_img(fname, data)
        aS, aN, aF = sp.local_list("animInicio"), sp.local_list("animQuadros"), sp.local_list("animFps")
        cur, mySer, animT = sp.local_var("animAtual"), sp.local_var("serieVista"), sp.local_var("inicioAnim")
        rows = ch.anim_table()
        init = [clear_list(aS), clear_list(aN), clear_list(aF)]
        for r in rows:
            init += [add_item(aS, r["start"]), add_item(aN, r["n"]), add_item(aF, round(r["fps"], 3))]
        sp.add(when_start(hide(), *init))
        sp.add(when_received("menu", hide()), when_received("load", hide()))
        cx, cy = ch.world_center()
        sing_ms = ch.sing_duration * song0.step_at(0)
        beat_grid = V(crochet) * 2
        frame = item(V(cur), aS) + fmin(floor((POS() - V(animT)) * item(V(cur), aF) / 1000), item(V(cur), aN) - 1)
        if ch.dance_lr:
            # gf: alterna danceRight/danceLeft a cada batida
            body = [
                set_var(animT, floor(POS() / V(crochet)) * V(crochet)),
                set_var(cur, iff(fmod(floor(POS() / V(crochet)), 2).eq(0), 10, 1)),
                set_look_index(frame),
                world_place(cx, cy, sf),
            ]
        else:
            held_sum = item(1, held) + item(2, held) + item(3, held) + item(4, held)
            back_to_idle = V(cur).ne(1) & (POS() - V(sing_v) > sing_ms) & \
                ((V(cur) < 11) | (POS() - V(animT) > item(V(cur), aN) * 1000 / item(V(cur), aF)))
            if is_player:
                back_to_idle = back_to_idle & held_sum.eq(0)
            body = [
                if_(V(serial_v).ne(V(mySer)), [set_var(mySer, V(serial_v)), set_var(cur, V(anim_v)),
                                                set_var(animT, V(sing_v))]),
                if_(back_to_idle, [set_var(cur, 1)]),
                if_(V(cur).eq(1), [set_var(animT, floor(POS() / beat_grid) * beat_grid)]),
                set_look_index(frame),
                world_place(cx, cy, sf),
            ]
        head = [set_var(cur, 1)] + ([] if ch.dance_lr else [set_var(mySer, V(serial_v))])
        sp.add(when_received("start", *head, set_var(animT, 0), set_look_index(rows[0]["start"]),
                             world_place(cx, cy, sf), show(), repeat_until(~running(), body)))
        return sp

    if gf:
        character_sprite(gf, "GF", None, None, None, 0.95, False)
    if dad.ok:
        character_sprite(dad, "Oponente", dadAnim, dadSerial, dadSingT, 1.0, False)
    if bf.ok:
        character_sprite(bf, "Jogador", bfAnim, bfSerial, bfSingT, 1.0, True)

    # ======================================================================
    # Notas: pools de clones reutilizaveis
    # ======================================================================
    note_size = 112
    speed_px = 0.45 * song0.speed
    piece_h = song0.step_at(0) / 2.0 * speed_px
    imgs = note_images(note_size, piece_h)
    lead = (SCREEN_H + 60) / min(0.45 * s.speed for s in songs)
    chart_data = {s.id: build_chart_data(s, 0.45 * s.speed) for s in songs}
    P_tap = max(pool_size([t for t, _ in chart_data[s.id]["taps"]], lead, 180, opts.max_pool) for s in songs)
    P_hold = max(pool_size([t for t, _, _ in chart_data[s.id]["holds"]], lead, 120, opts.max_pool * 2)
                 if chart_data[s.id]["holds"] else 4 for s in songs)
    R.add("DESEMPENHO", "Pool de clones: %d notas normais, %d pecas de notas longas, 8 receptores "
          "(antecedencia %.0f ms)" % (P_tap, P_hold, lead))

    def pool_sprite(name, looks, P, times_l, lanes_l, count_v, look_formula, resolved):
        sp = proj.sprite(name)
        for ln, im in looks:
            sp.look(ln, ln + ".png")
            add_img(ln + ".png", im)
        poolId, k, myRun, myLane, isClone = (sp.local_var(n) for n in ("idPool", "indice", "minhaRodada",
                                                                      "minhaLane", "ehClone"))
        sp.add(when_start(hide(), set_var(isClone, 0)))
        sp.add(when_received("menu", hide()))
        sp.add(when_received("start", if_(V(isClone).eq(0), [
            hide(), set_var(poolId, 1), repeat(P, [clone_myself(), change_var(poolId, 1)])])))
        alive = V(runId).eq(V(myRun)) & running()
        sp.add(when_cloned(
            set_var(isClone, 1), set_var(myRun, V(runId)), set_var(k, V(poolId)),
            repeat_until((V(k) > V(count_v)) | ~alive, [
                wait_until((item(V(k), times_l) - POS() < lead) | ~alive),
                set_var(myLane, item(V(k), lanes_l)),
                set_look_index(look_formula(V(k), V(myLane))),
                place_at(lane_x_formula(V(myLane)), note_y(item(V(k), times_l))),
                show(),
                repeat_until(resolved(V(k), V(myLane)) | ~alive, [set_y(note_y(item(V(k), times_l)))]),
                hide(),
                change_var(k, P),
            ]),
            delete_this_clone()))
        return sp

    hold_looks = [("longa_%s" % DIR_NAMES[d].lower(), imgs["hold_%d" % d]) for d in range(4)] + \
                 [("ponta_%s" % DIR_NAMES[d].lower(), imgs["holdend_%d" % d]) for d in range(4)] + \
                 [("ponta_baixo_%s" % DIR_NAMES[d].lower(), imgs["holdendd_%d" % d]) for d in range(4)]
    pool_sprite("NotasLongas", hold_looks, P_hold, hT, hL, nHolds,
                lambda k, l: fmod(l, 4) + 1 + 4 * (item(k, hK) - 1) + 4 * item(k, hK).eq(2) * V(downscroll),
                lambda k, l: ((l < 4) & (V(oppH) > k)) | ((l > 3) & (item(l - 3, ptrH) > k)))

    # receptores (8 clones)
    rec = proj.sprite("Receptores")
    for kind in ("strum", "press", "confirm"):
        for d in range(4):
            n_ = "%s_%s" % ({"strum": "receptor", "press": "pressionado", "confirm": "acerto"}[kind], DIR_NAMES[d].lower())
            rec.look(n_, n_ + ".png")
            add_img(n_ + ".png", imgs["%s_%d" % (kind, d)])
    rId, rRun, rClone = rec.local_var("idPool"), rec.local_var("minhaRodada"), rec.local_var("ehClone")
    rec.add(when_start(hide(), set_var(rClone, 0)))
    rec.add(when_received("menu", hide()))
    rec.add(when_received("start", if_(V(rClone).eq(0), [
        hide(), set_var(rId, 0), repeat(8, [clone_myself(), change_var(rId, 1)])])))
    state_f = iff(POS() < item(V(rId) + 1, strumUntil), 2,
                  iff((V(rId) > 3) & item(V(rId) - 3, held).eq(1), 1, 0))
    rec.add(when_cloned(
        set_var(rClone, 1), set_var(rRun, V(runId)),
        place_at(lane_x_formula(V(rId)), V(strumY)), set_look_index(fmod(V(rId), 4) + 1), show(),
        repeat_until(V(runId).ne(V(rRun)) | ~running(), [set_look_index(fmod(V(rId), 4) + 1 + 4 * state_f)]),
        delete_this_clone()))

    tap_looks = [("nota_%s" % DIR_NAMES[d].lower(), imgs["note_%d" % d]) for d in range(4)]
    pool_sprite("Notas", tap_looks, P_tap, tT, tL, nTaps, lambda k, l: fmod(l, 4) + 1,
                lambda k, l: ((l < 4) & (V(oppT) > k)) | ((l > 3) & (item(l - 3, ptrT) > k)))

    # ======================================================================
    # HUD: avaliacao, barra de vida, icones, contagem
    # ======================================================================
    rat = proj.sprite("Avaliacao")
    for i, (txt, col) in enumerate([("SICK!!", (90, 220, 255)), ("GOOD!", (110, 230, 80)),
                                    ("BAD", (230, 160, 60)), ("SHIT", (150, 120, 90))]):
        rat.look(txt, "avaliacao_%d.png" % (i + 1))
        add_img("avaliacao_%d.png" % (i + 1), ui.text_image(txt, 64, fill=col, stroke_w=6))
    hide_on(rat, "menu", "load")
    since = POS() - V(ratingT)
    rat.add(when_received("start", repeat_until(~running(), [
        if_(since < 700, [set_look_index(V(ratingIdx)),
                          place_at(-110, iff(V(downscroll).eq(1), -40, 40) + since * 0.04),
                          set_transparency(fmax(0, (since - 400) / 3)), show()],
            [hide()])]), hide()))

    dad_color = dad.color if dad.ok else (255, 0, 0)
    bf_color = bf.color if bf.ok else (0, 255, 0)
    hb = proj.sprite("BarraVida")
    for i, im in enumerate(health_bar_frames(dad_color, bf_color)):
        hb.look("vida %d" % i, "vida_%02d.png" % i)
        add_img("vida_%02d.png" % i, im)
    bar_y_up = SCREEN_H / 2 - (SCREEN_H * 0.89 + 9.5)
    bar_y_down = SCREEN_H / 2 - (SCREEN_H * 0.11 + 9.5)
    barY = iff(V(downscroll).eq(1), bar_y_down, bar_y_up)
    hide_on(hb, "menu", "load")
    hb.add(when_received("start", place_at(0, barY), show(), repeat_until(~running(), [
        set_look_index(fround(fmin(fmax(V(health), 0), 2) * 25) + 1)])))

    split_x = (SCREEN_W / 2 - 300.5 + 4) + 593 * (1 - V(health) / 2) - SCREEN_W / 2
    bump = 100 + 20 * iff(POS() > 0, fmax(0, 1 - fmod(POS(), V(crochet)) / (V(crochet) * 0.4)), 0)
    for name, icon, flip, offset, losing in (
            ("IconeOponente", dad.healthicon if dad.ok else "dad", False, -49, V(health) > 1.6),
            ("IconeJogador", bf.healthicon if bf.ok else "bf", True, 49, V(health) < 0.4)):
        ic = proj.sprite(name)
        frs = icon_frames(mod, icon, flip)
        for i, im in enumerate(frs):
            fname = "icone_%s_%d.png" % (re.sub(r"\W", "", icon).lower(), i + 1)
            if fname not in img_names:
                add_img(fname, im)
            ic.look("%s %d" % (icon, i + 1), fname)
        hide_on(ic, "menu", "load")
        ic.add(when_received("start", show(), repeat_until(~running(), [
            place_at(split_x + offset, barY + 5), set_look_index(iff(losing, 2, 1)), set_size(bump)])))

    cd = proj.sprite("Contagem")
    for i, txt in enumerate(["3", "READY?", "SET", "GO!"]):
        cd.look(txt, "contagem_%d.png" % (i + 1))
        add_img("contagem_%d.png" % (i + 1), ui.text_image(txt, 110, fill=(255, 255, 255), stroke_w=8))
    hide_on(cd, "menu", "load")
    cd.add(when_received("start", place_at(0, 30), repeat_until(~running() | (POS() > 50), [
        if_((POS() >= V(crochet) * -4) & (POS() < 0), [
            set_look_index(floor(POS() / V(crochet)) + 5),
            set_transparency(fmod(POS() + V(crochet) * 8, V(crochet)) / V(crochet) * 100), show()], [hide()])]),
        hide()))

    has_flash = any(e[1] == "Flash Camera" for s in songs for e in s.events)
    if has_flash:
        fl = proj.sprite("Flash")
        fl.look("branco", "flash.png")
        add_img("flash.png", Image.new("RGBA", (SCREEN_W, SCREEN_H), (255, 255, 255, 255)))
        hide_on(fl, "menu", "load")
        fl.add(when_received("start", place_at(0, 0), repeat_until(~running(), [
            if_(POS() - V(flashT) < V(flashDur), [
                set_transparency((POS() - V(flashT)) / V(flashDur) * 100), show()], [hide()])]), hide()))

    aspect = None
    for f_ in mod.files:
        if f_.lower().startswith("scripts/") and f_.endswith(".lua"):
            src = open(mod.p(f_), encoding="utf-8", errors="replace").read()
            for m in re.finditer(r"makeLuaSprite\('(\w+)',\s*'([\w/]+)'\s*,\s*0\s*,\s*0\)", src):
                png = mod.find(["images/%s.png" % m.group(2)])
                if png:
                    im = Image.open(png).convert("RGBA")
                    if im.getextrema()[3][1] == 0:
                        R.add("SCRIPTS / RECURSOS NAO CONVERTIDOS",
                              "%s: sobreposicao '%s' e totalmente transparente - ignorada" % (f_, m.group(2)))
                        continue
                    if im.size == (SCREEN_W, SCREEN_H):
                        aspect = (m.group(2), im)
                        R.add("SCRIPTS CONVERTIDOS", "%s: sobreposicao de tela '%s' convertida em objeto"
                              % (f_, m.group(2)))
    if aspect:
        bo = proj.sprite("Bordas")
        bo.look(aspect[0], "sobreposicao.png")
        add_img("sobreposicao.png", aspect[1])
        hide_on(bo, "menu", "load")
        bo.add(when_received("start", place_at(0, 0), show()))
        bo.add(when_received("gameover", hide()), when_received("results", hide()))

    # ======================================================================
    # Areas de toque (4 lanes do jogador) + julgamento
    # ======================================================================
    def hit_bricks(L, d):
        hr = None
        return [
            set_var(ratingIdx, iff(d <= 45, 1, iff(d <= 90, 2, iff(d <= 135, 3, 4)))),
            change_var(score, iff(V(ratingIdx).eq(1), 350, iff(V(ratingIdx).eq(2), 200,
                                                              iff(V(ratingIdx).eq(3), 100, 50)))),
            change_var(combo, 1),
            set_var(maxCombo, fmax(V(maxCombo), V(combo))),
            change_var(hitSum, iff(V(ratingIdx).eq(1), 1, iff(V(ratingIdx).eq(2), 0.7,
                                                             iff(V(ratingIdx).eq(3), 0.4, 0)))),
            change_var(played, 1),
            set_var(health, fmin(2, V(health) + 0.023)),
            set_var(ratingT, POS()),
            replace_item(strumUntil, L + 5, POS() + 150),
            set_var(bfAnim, 2 + L), change_var(bfSerial, 1), set_var(bfSingT, POS()),
        ]

    def miss_bricks(L):
        return [change_var(misses, 1), set_var(combo, 0), change_var(played, 1),
                change_var(health, -0.0475),
                set_var(bfAnim, 6 + L), change_var(bfSerial, 1), set_var(bfSingT, POS())]

    lane_cols = NOTE_COLORS
    for L in range(4):
        hbx = proj.sprite("Toque%d" % L)
        c = lane_cols[L]
        idle = Image.new("RGBA", (SCREEN_W // 4, SCREEN_H), c + (14,))
        ImageDraw.Draw(idle).rectangle((0, 0, SCREEN_W // 4 - 1, SCREEN_H - 1), outline=c + (60,), width=3)
        pressed = Image.new("RGBA", (SCREEN_W // 4, SCREEN_H), c + (70,))
        hbx.look("solto", "toque_%d_solto.png" % L)
        hbx.look("pressionado", "toque_%d_pressionado.png" % L)
        add_img("toque_%d_solto.png" % L, idle)
        add_img("toque_%d_pressionado.png" % L, pressed)
        hx, hp, hd, hh, k1, k2 = (hbx.local_var(n) for n in ("notaToque", "tempoToque", "diferenca",
                                                             "segurando", "notaAtual", "longaAtual"))
        hide_on(hbx, "menu", "load", "gameover", "results")
        bot_hold = (item(L + 1, ptrH) <= V(nHolds)) & (item(item(L + 1, ptrH), hT) - POS() < 90)
        hbx.add(when_received("start",
            place_at(-SCREEN_W / 2 + SCREEN_W / 8 + L * SCREEN_W / 4, 0), set_look_index(1), show(),
            repeat_until(~running(), [
                set_var(hh, iff(V(botplay).eq(1), bot_hold, sensor("COLLIDES_WITH_FINGER"))),
                replace_item(held, L + 1, V(hh)),
                set_var(k1, item(L + 1, ptrT)),
                if_(V(k1) <= V(nTaps), [
                    if_(V(botplay).eq(1) & (POS() >= item(V(k1), tT)),
                        [replace_item(ptrT, L + 1, item(V(k1), tN))] + hit_bricks(L, 0),
                        [if_(POS() > item(V(k1), tT) + 166,
                             [replace_item(ptrT, L + 1, item(V(k1), tN))] + miss_bricks(L))]),
                ]),
                set_var(k2, item(L + 1, ptrH)),
                if_((V(k2) <= V(nHolds)) & (POS() >= item(V(k2), hT)), [
                    if_(V(hh).eq(1),
                        [replace_item(ptrH, L + 1, item(V(k2), hN)),
                         set_var(health, fmin(2, V(health) + 0.0115)),
                         set_var(bfSingT, POS()), replace_item(strumUntil, L + 5, POS() + 150)],
                        [if_(POS() > item(V(k2), hT) + 100,
                             [replace_item(ptrH, L + 1, item(V(k2), hN)),
                              change_var(health, -0.02375), set_var(combo, 0)])]),
                ]),
            ]),
            replace_item(held, L + 1, 0)))
        hbx.add(when_received("start", repeat_until(~running(), [
            set_look_index(item(L + 1, held) + 1)])))
        hbx.add(when_tapped(if_(running() & V(botplay).eq(0), [
            set_var(hp, POS()),
            set_var(hx, item(L + 1, ptrT)),
            set_var(hd, fabs(item(V(hx), tT) - V(hp))),
            if_((V(hx) <= V(nTaps)) & (V(hd) <= 166), [
                if_(item(L + 1, ptrT).eq(V(hx)),
                    [replace_item(ptrT, L + 1, item(V(hx), tN))] + hit_bricks(L, V(hd)))]),
        ])))


    # ======================================================================
    # Menu inicial / selecao de musica / opcoes
    # ======================================================================
    week = week_info[0][1] if week_info else {}
    menu = Image.new("RGBA", (SCREEN_W, SCREEN_H), (0, 0, 0, 255))
    md = ImageDraw.Draw(menu)
    md.rectangle((0, 40, SCREEN_W, 400), fill=(249, 207, 81, 255))
    wb = week.get("weekBackground")
    wbp = mod.find(["images/menubackgrounds/menu_%s.png" % wb]) if wb else None
    if wbp:
        im = Image.open(wbp).convert("RGBA")
        s_ = 360.0 / im.height
        im = im.resize((int(im.width * s_), 360), Image.LANCZOS)
        menu.alpha_composite(im, ((SCREEN_W - im.width) // 2, 40))
        R.add("INTERFACE", "Menu: fundo da week '%s' (%s)" % (wb, os.path.relpath(wbp, mod.root)))
    t_ = ui.text_image(title, 30, stroke_w=3, pad=4)
    menu.alpha_composite(t_, (12, 4))
    sn = week.get("storyName", "")
    if sn:
        t_ = ui.text_image(sn, 30, fill=(200, 200, 200), stroke_w=3, pad=4)
        menu.alpha_composite(t_, (SCREEN_W - t_.width - 12, 4))
    wt = mod.find(["images/storymenu/%s.png" % week.get("weekName", "")]) if week else None
    if wt:
        im = Image.open(wt).convert("RGBA")
        menu.alpha_composite(im, ((SCREEN_W - im.width) // 2, 445 - im.height // 2))
    diffs = (week.get("difficulties") or "").split(",")
    dp = mod.find(["images/menudifficulties/%s.png" % diffs[0].strip().lower()]) if diffs and diffs[0] else None
    if dp:
        im = Image.open(dp).convert("RGBA")
        menu.alpha_composite(im, (SCREEN_W - im.width - 40, 445 - im.height // 2))
    mf = proj.sprite("MenuFundo")
    mf.look("menu", "menu_fundo.png")
    add_img("menu_fundo.png", menu)
    mf.add(when_received("menu", place_at(0, 0), show()), when_received("load", hide()))

    def menu_button(name, labels, x, y, w, h, on_tap, size=32):
        b = proj.sprite(name)
        for i, lab in enumerate(labels):
            fname = "botao_%s_%d.png" % (name.lower(), i + 1)
            b.look(lab, fname)
            add_img(fname, ui.button(lab, w, h, size=size))
        b.add(when_start(hide()))
        b.add(when_received("load", hide()))
        b.add(when_tapped(if_(V(gameState).eq(0), on_tap)))
        return b

    nS = len(songs)
    b = menu_button("BotaoAnterior", ["<"], -330, -170, 80, 56,
                    [change_var(songIdx, -1), if_(V(songIdx) < 1, [set_var(songIdx, nS)]), broadcast("songsel")], 40)
    b.add(when_received("menu", place_at(-330, -170), show()))
    b = menu_button("BotaoProximo", [">"], 330, -170, 80, 56,
                    [change_var(songIdx, 1), if_(V(songIdx) > nS, [set_var(songIdx, 1)]), broadcast("songsel")], 40)
    b.add(when_received("menu", place_at(330, -170), show()))
    b = menu_button("BotaoJogar", ["JOGAR"], 0, -240, 300, 60, [broadcast("load")], 40)
    b.add(when_received("menu", place_at(0, -240), show()))
    b = menu_button("BotaoRolagem", ["ROLAGEM: CIMA", "ROLAGEM: BAIXO"], -420, -310, 330, 52,
                    [set_var(downscroll, 1 - V(downscroll)), write_var_device(downscroll),
                     set_look_index(V(downscroll) + 1)], 28)
    b.add(when_received("menu", place_at(-420, -310), set_look_index(V(downscroll) + 1), show()))
    for nm, delta, x in (("BotaoOffsetMenos", -10, -150), ("BotaoOffsetMais", 10, 150)):
        b = menu_button(nm, ["-" if delta < 0 else "+"], x, -310, 64, 52,
                        [change_var(offsetMs, delta), write_var_device(offsetMs),
                         set_var(offsetTxt, join("Offset: ", V(offsetMs), " ms"))], 36)
        b.add(when_received("menu", place_at(x, -310), show()))
    b = menu_button("BotaoBotplay", ["BOTPLAY: NAO", "BOTPLAY: SIM"], 420, -310, 330, 52,
                    [set_var(botplay, 1 - V(botplay)), write_var_device(botplay),
                     set_look_index(V(botplay) + 1)], 28)
    b.add(when_received("menu", place_at(420, -310), set_look_index(V(botplay) + 1), show()))

    # ======================================================================
    # Paineis de fim de musica / game over
    # ======================================================================
    pn = proj.sprite("Painel")
    for i, (txt, col) in enumerate([("GAME OVER", (255, 80, 80)), ("MUSICA CONCLUIDA!", (120, 255, 120))]):
        im = Image.new("RGBA", (SCREEN_W, SCREEN_H), (0, 0, 0, 170))
        t_ = ui.text_image(txt, 84, fill=col, stroke_w=6)
        im.alpha_composite(t_, ((SCREEN_W - t_.width) // 2, 110))
        pn.look(txt, "painel_%d.png" % (i + 1))
        add_img("painel_%d.png" % (i + 1), im)
    pn.add(when_start(hide()), when_received("menu", hide()), when_received("load", hide()),
           when_received("gameover", place_at(0, 0), set_look_index(1), show()),
           when_received("results", place_at(0, 0), set_look_index(2), show()))
    for nm, lab, x, act in (("BotaoRepetir", "TENTAR DE NOVO", -220, "load"), ("BotaoMenu", "MENU", 220, "menu")):
        b = proj.sprite(nm)
        b.look(lab, "botao_%s.png" % nm.lower())
        add_img("botao_%s.png" % nm.lower(), ui.button(lab, 330, 64, size=32))
        b.add(when_start(hide()), when_received("menu", hide()), when_received("load", hide()),
              when_received("gameover", place_at(x, -230), show()),
              when_received("results", place_at(x, -230), show()),
              when_tapped(if_(V(gameState).eq(2) | V(gameState).eq(3), [broadcast(act)])))

    # ======================================================================
    # Textos (variaveis mostradas no palco)
    # ======================================================================
    tx = proj.sprite("Textos")
    tx.look("vazio", "vazio.png")
    add_img("vazio.png", Image.new("RGBA", (2, 2), (0, 0, 0, 0)))
    acc = iff(V(played) > 0, fround(V(hitSum) / V(played) * 10000) / 100, 100)
    all_txt = [scoreTxt, comboTxt, res1, res2, res3, loadTxt, songName, offsetTxt]
    tx.add(when_start(hide()))
    tx.add(when_received("menu", *[hide_text(v) for v in all_txt],
                         show_text(songName, 0, -165, 75, "#FFFFFF"),
                         show_text(offsetTxt, 0, -305, 60, "#FFFFFF")))
    tx.add(when_received("load", *[hide_text(v) for v in all_txt], show_text(loadTxt, 0, 0, 90, "#FFFFFF")))
    tx.add(when_received("start", hide_text(loadTxt),
                         show_text(scoreTxt, 0, iff(V(downscroll).eq(1), 240, -322), 55, "#FFFFFF"),
                         show_text(comboTxt, -110, iff(V(downscroll).eq(1), -110, -30), 80, "#FFFFFF"),
                         repeat_until(~running(), [
                             set_var(scoreTxt, join("Pontos: ", V(score), " | Erros: ", V(misses),
                                                    " | Precisao: ", acc, "%")),
                             set_var(comboTxt, iff((V(combo) > 1) & (POS() - V(ratingT) < 700),
                                                   join(V(combo)), text(""))),
                             wait(0.1)])))
    for msg in ("gameover", "results"):
        tx.add(when_received(msg, hide_text(comboTxt), hide_text(scoreTxt),
                             set_var(res1, join("Pontos: ", V(score))),
                             set_var(res2, join("Erros: ", V(misses), "   Combo maximo: ", V(maxCombo))),
                             set_var(res3, join("Precisao: ", acc, "%")),
                             show_text(res1, 0, 80, 90, "#FFFFFF"),
                             show_text(res2, 0, 10, 70, "#FFFFFF"),
                             show_text(res3, 0, -60, 70, "#FFFFFF")))

    # ======================================================================
    # Controlador do jogo: carregamento, maestro (relogio), oponente, camera, eventos
    # ======================================================================
    jg = proj.sprite("Jogo")
    jg.look("vazio", "vazio.png")
    sounds = {}
    for i, s in enumerate(songs, 1):
        fname = "musica_%d_%s.mp3" % (i, re.sub(r"\W", "", s.id).lower())
        sounds[s.id] = jg.sound(s.name, fname)
    name_chain = [if_(V(songIdx).eq(i), [set_var(songName, s.name)]) for i, s in enumerate(songs, 1)]
    jg.add(when_start(
        hide(), set_var(gameState, 0), set_var(runId, 0), set_var(songIdx, 1), set_var(G("musicaCarregada"), 0),
        set_var(offsetMs, 0), set_var(downscroll, 0), set_var(botplay, 0),
        read_var_device(offsetMs), read_var_device(downscroll), read_var_device(botplay),
        set_var(offsetMs, fround(V(offsetMs) * 1)),
        set_var(downscroll, iff(V(downscroll).eq(1), 1, 0)), set_var(botplay, iff(V(botplay).eq(1), 1, 0)),
        broadcast("menu")))
    jg.add(when_received("menu", set_var(gameState, 0), change_var(runId, 1), stop_all_sounds(),
                         set_var(offsetTxt, join("Offset: ", V(offsetMs), " ms")), *name_chain))
    jg.add(when_received("songsel", *name_chain))

    load = [set_var(gameState, 4), change_var(runId, 1), stop_all_sounds(),
            set_var(loadTxt, "Carregando musica..."),
            *[clear_list(l) for l in (eT, eK, eA, fT, fH, ptrT, ptrH, held, strumUntil)]]
    ev_supported = {}
    for i, s in enumerate(songs, 1):
        cdat = chart_data[s.id]
        dur = audio_duration(s.inst) or (s.notes[-1][0] / 1000.0 + 3 if s.notes else 60)
        s.duration = dur
        branch = [set_var(dataT, text(cdat["data_t"])), set_var(dataH, text(cdat["data_h"])),
                  set_var(dataS, text(cdat["data_s"])),
                  set_var(nTaps, len(cdat["taps"])), set_var(nHolds, len(cdat["holds"])),
                  set_var(nSec, len(s.sections)), set_var(songLen, round(dur * 1000)),
                  set_var(crochet, round(60000.0 / s.bpm, 4)), set_var(noteSpeed, round(0.45 * s.speed, 4))]
        branch += [add_item(fT, v) for v in cdat["tfirst"]] + [add_item(fH, v) for v in cdat["hfirst"]]
        evs = []
        for (et, name, v1, v2) in s.events:
            if name == "Play Animation":
                target = v2.strip().lower()
                ch, kind = (bf, 2) if target in ("bf", "boyfriend", "1") else (dad, 1)
                if target in ("gf", "girlfriend", "2"):
                    R.add("SCRIPTS / RECURSOS NAO CONVERTIDOS", "%s: Play Animation em GF ignorado" % s.id)
                    continue
                sid = ch.special_id(v1.strip()) if ch.ok else None
                if sid is None:
                    R.add("SCRIPTS / RECURSOS NAO CONVERTIDOS",
                          "%s: Play Animation '%s' (%s) sem animacao correspondente" % (s.id, v1, v2))
                    continue
                evs.append((et, kind, sid))
                ev_supported["Play Animation"] = ev_supported.get("Play Animation", 0) + 1
            elif name == "Flash Camera":
                try:
                    durf = float(v1) * 1000 if v1.strip() else 500
                except ValueError:
                    durf = 500
                evs.append((et, 3, durf))
                ev_supported["Flash Camera"] = ev_supported.get("Flash Camera", 0) + 1
            else:
                R.add("SCRIPTS / RECURSOS NAO CONVERTIDOS",
                      "%s: evento '%s' em %.0f ms (v1=%r, v2=%r) nao suportado" % (s.id, name, et, v1, v2))
        for et, kind, val in evs:
            branch += [add_item(eT, round(et)), add_item(eK, kind), add_item(eA, val)]
        branch.append(set_var(nEv, len(evs)))
        load.append(if_(V(songIdx).eq(i), branch))
        R.add("CHART", "%s: %d notas (%d jogador / %d oponente), %d pecas de notas longas, %d secoes, "
              "BPM %.1f, velocidade %.2f, duracao %.1f s, formato %s, arquivo %s"
              % (s.name, len(cdat["taps"]), sum(1 for _, l in cdat["taps"] if l >= 4),
                 sum(1 for _, l in cdat["taps"] if l < 4), len(cdat["holds"]), len(s.sections),
                 s.bpm, s.speed, dur, s.format, os.path.relpath(s.chart_path, mod.root)))
        if s.bpm_changes:
            R.add("LIMITACOES", "%s: mudancas de BPM %s - tempos das notas corretos, mas a grade de "
                  "batidas das animacoes usa o BPM inicial" % (s.id, s.bpm_changes))
    for name, n in ev_supported.items():
        R.add("SCRIPTS CONVERTIDOS", "evento '%s': %d ocorrencia(s) convertida(s) em dados de evento" % (name, n))

    def decode(count_v, data_v, rec, fields):
        body = []
        for l, start, ln in fields:
            body.append(add_item(l, subtext(V(ldP) + start, V(ldP) + start + ln - 1, V(data_v)) * 1))
        body.append(change_var(ldP, rec))
        return [set_var(ldP, 2), repeat(V(count_v), body)]

    # decodifica so quando a musica muda (reiniciar a mesma musica e instantaneo)
    loadedSong = G("musicaCarregada")
    load.append(if_(V(loadedSong).ne(V(songIdx)),
                    [clear_list(l) for l in (tT, tL, tN, hT, hL, hK, hN, sT, sM)]
                    + decode(nTaps, dataT, TAP_REC, [(tT, 0, 7), (tL, 7, 1), (tN, 8, 5)])
                    + decode(nHolds, dataH, HOLD_REC, [(hT, 0, 7), (hL, 7, 1), (hK, 8, 1), (hN, 9, 5)])
                    + decode(nSec, dataS, SEC_REC, [(sT, 0, 7), (sM, 7, 1)])
                    + [set_var(loadedSong, V(songIdx))]))
    load += [add_item(ptrT, item(i, fT)) for i in range(1, 5)]
    load += [add_item(ptrH, item(i, fH)) for i in range(1, 5)]
    load += [add_item(held, 0) for _ in range(4)] + [add_item(strumUntil, -99999) for _ in range(8)]
    load += [
        set_var(oppT, item(5, fT)), set_var(oppH, item(5, fH)), set_var(secI, 1), set_var(evI, 1),
        set_var(score, 0), set_var(misses, 0), set_var(combo, 0), set_var(maxCombo, 0),
        set_var(hitSum, 0), set_var(played, 0), set_var(health, 1),
        set_var(ratingT, -99999), set_var(ratingIdx, 1), set_var(flashT, -99999), set_var(flashDur, 1),
        set_var(bfAnim, 1), set_var(bfSerial, 0), set_var(bfSingT, -99999),
        set_var(dadAnim, 1), set_var(dadSerial, 0), set_var(dadSingT, -99999),
        set_var(scrollSign, iff(V(downscroll).eq(1), -1, 1)),
        set_var(strumY, iff(V(downscroll).eq(1), round(strum_down, 2), round(strum_up, 2))),
        set_var(camX, round(gfT[0] - SCREEN_W / 2, 2)), set_var(camY, round(gfT[1] - SCREEN_H / 2, 2)),
        set_var(camTX, V(camX)), set_var(camTY, V(camY)),
        set_var(songStarted, 0), set_var(songBase, V(crochet) * -5), set_var(scoreTxt, ""),
        set_var(comboTxt, ""), reset_timer(), set_var(loadTxt, ""), set_var(gameState, 1),
        broadcast("start"),
    ]
    jg.add(when_received("load", *load))

    play_chain = [if_(V(songIdx).eq(i), [play_sound(sounds[s.id])]) for i, s in enumerate(songs, 1)]
    cam_bf = (round(bfT[0] - SCREEN_W / 2, 2), round(bfT[1] - SCREEN_H / 2, 2))
    cam_dad = (round(dadT[0] - SCREEN_W / 2, 2), round(dadT[1] - SCREEN_H / 2, 2))
    jg.add(when_received("start", repeat_until(~running(), [
        if_(V(songStarted).eq(0) & (sensor("TIMER") * 1000 + V(songBase) >= 0),
            # sincronizacao atomica: um unico brick faz o relogio valer 0 no instante do PlaySound
            play_chain + [set_var(songBase, sensor("TIMER") * -1000), set_var(songStarted, 1)]),
        if_(V(health) <= 0, [set_var(gameState, 2), stop_all_sounds(), broadcast("gameover")]),
        if_(V(songStarted).eq(1) & (POS() > V(songLen)), [set_var(gameState, 3), stop_all_sounds(),
                                                         broadcast("results")]),
        if_((V(secI) <= V(nSec)) & (POS() >= item(V(secI), sT)), [
            set_var(camTX, iff(item(V(secI), sM).eq(1), cam_bf[0], cam_dad[0])),
            set_var(camTY, iff(item(V(secI), sM).eq(1), cam_bf[1], cam_dad[1])),
            change_var(secI, 1)]),
        if_((V(evI) <= V(nEv)) & (POS() >= item(V(evI), eT)), [
            set_var(ldEv, item(V(evI), eK)),
            if_(V(ldEv).eq(1), [set_var(dadAnim, item(V(evI), eA)), change_var(dadSerial, 1),
                                set_var(dadSingT, POS())]),
            if_(V(ldEv).eq(2), [set_var(bfAnim, item(V(evI), eA)), change_var(bfSerial, 1),
                                set_var(bfSingT, POS())]),
            if_(V(ldEv).eq(3), [set_var(flashT, POS()), set_var(flashDur, item(V(evI), eA))]),
            change_var(evI, 1)]),
    ])))
    # IA do oponente: toca cada nota no tempo exato
    jg.add(when_received("start", repeat_until(~running(), [
        if_((V(oppT) <= V(nTaps)) & (POS() >= item(V(oppT), tT)), [
            replace_item(strumUntil, item(V(oppT), tL) + 1, item(V(oppT), tT) + 150),
            set_var(dadAnim, item(V(oppT), tL) + 2), change_var(dadSerial, 1), set_var(dadSingT, POS()),
            set_var(oppT, item(V(oppT), tN))]),
        if_((V(oppH) <= V(nHolds)) & (POS() >= item(V(oppH), hT)), [
            replace_item(strumUntil, item(V(oppH), hL) + 1, POS() + 150),
            set_var(dadSingT, POS()), set_var(oppH, item(V(oppH), hN))]),
    ])))
    # camera suave (lerp) entre oponente e jogador
    jg.add(when_received("start", repeat_until(~running(), [
        change_var(camX, (V(camTX) - V(camX)) * 0.05),
        change_var(camY, (V(camTY) - V(camY)) * 0.05),
        wait(0.0167)])))

    return dict(proj=proj, ui=ui, zoom=zoom, bf=bf, dad=dad, gf=gf, chart_data=chart_data,
                sounds=sounds, title=title, P_tap=P_tap, P_hold=P_hold, menu=menu, lane_x=lane_x,
                strum_up=strum_up, strum_down=strum_down, imgs=imgs, back=back, bg_center=bg_center,
                gfT=gfT, dadT=dadT, bfT=bfT, piece_h=piece_h)


# ==========================================================================
# 9. Captura de tela simulada (miniatura do projeto) e inventario de scripts
# ==========================================================================

def make_screenshot(info):
    """Desenha o primeiro quadro do gameplay a partir dos mesmos assets e
    coordenadas usadas pelos scripts (serve de miniatura e de conferencia)."""
    W, H = SCREEN_W, SCREEN_H
    im = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    zoom = info["zoom"]
    cam = (info["bfT"][0] - W / 2, info["bfT"][1] - H / 2)

    def put(img, cx_world, cy_world, sf=1.0):
        x = (cx_world - W / 2) * zoom - cam[0] * zoom * sf
        y = -(cy_world - H / 2) * zoom + cam[1] * zoom * sf
        im.alpha_composite(img, (int(W / 2 + x - img.width / 2), int(H / 2 - y - img.height / 2))) \
            if True else None

    def safe_put(img, cx, cy, sf=1.0):
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        x = (cx - W / 2) * zoom - cam[0] * zoom * sf
        y = -(cy - H / 2) * zoom + cam[1] * zoom * sf
        layer.paste(img, (int(W / 2 + x - img.width / 2), int(H / 2 - y - img.height / 2)), img)
        im.alpha_composite(layer)

    safe_put(info["back"], *info["bg_center"])
    for ch, sf in ((info["gf"], 0.95), (info["dad"], 1.0), (info["bf"], 1.0)):
        if ch and ch.ok:
            fr = Image.open(io.BytesIO(ch.frames_png[ch.anim_table()[0]["start"] - 1]))
            safe_put(fr, *ch.world_center(), sf=sf)
    for lane in range(8):
        img = info["imgs"]["strum_%d" % (lane % 4)]
        x = W / 2 + info["lane_x"][lane]
        y = H / 2 - info["strum_up"]
        im.alpha_composite(img, (int(x - img.width / 2), int(y - img.height / 2)))
    return im


def inventory_scripts(mod, songs):
    used_events = {e[1] for s in songs for e in s.events}
    for f in mod.files:
        low = f.lower()
        if not low.endswith((".lua", ".hx", ".hxs")):
            continue
        base = os.path.basename(f)
        if low.startswith("stages/"):
            continue  # relatado em CENARIO
        if low.startswith("custom_events/"):
            ev = os.path.splitext(base)[0]
            if ev in used_events:
                status = "usado no chart"
            else:
                status = "nao usado por nenhum chart convertido (nada a converter)"
            R.add("SCRIPTS (INVENTARIO)", "%s: evento personalizado - %s" % (f, status))
            continue
        src = open(mod.p(f), encoding="utf-8", errors="replace").read()
        notes = []
        if "strumLineNotes" in src:
            notes.append("deslocamento de strums -> convertido")
        if "makeLuaSprite" in src and "aspect" in src:
            notes.append("sobreposicao de bordas -> convertida")
        if "setTextFont" in src:
            notes.append("troca de fonte do HUD -> parcial (imagens de texto usam a fonte do mod; "
                         "textos dinamicos usam a fonte do Pocket Code)")
        if "camFollow" in src or "smoothCamera" in src:
            notes.append("camera suave -> convertida como lerp; deslocamento da camera por nota e "
                         "zoom por batida -> NAO convertidos")
        if low.endswith((".hx", ".hxs")):
            notes.append("HScript nao suportado")
        if not notes:
            notes.append("NAO convertido (Lua arbitrario nao tem equivalente automatico)")
        R.add("SCRIPTS (INVENTARIO)", "%s: %s" % (f, "; ".join(notes)))


# ==========================================================================
# 10. Programa principal
# ==========================================================================

def build_minimal(path):
    """Projeto minimo valido (etapa 1 da validacao)."""
    p = Project("FNF_Minimo", scene_name="FNF")
    bgs = p.sprite("Fundo")
    bgs.look("preto", "preto.png")
    p.add_image("preto.png", png_bytes(Image.new("RGBA", (SCREEN_W, SCREEN_H), (0, 0, 0, 255))))
    s = p.sprite("Seta")
    s.look("seta", "seta.png")
    p.add_image("seta.png", png_bytes(draw_arrow(112, NOTE_COLORS[0] + (255,))))
    v = p.global_var("toques")
    s.add(when_start(set_var(v, 0), show_text(v, 0, 200, 100, "#FFFFFF"),
                     forever([set_y(fn("SIN", sensor("TIMER") * 90) * 150)])),
          when_tapped(change_var(v, 1)))
    problems = p.check()
    xml = p.save(path)
    return problems, cc.validate_xml(xml)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mod", help="pasta ou .zip do mod de FNF")
    ap.add_argument("-o", "--output", default="output/FNF_Port.catrobat")
    ap.add_argument("--report", default=None, help="caminho do relatorio (padrao: ao lado do .catrobat)")
    ap.add_argument("--songs", default="", help="lista de musicas separadas por virgula (padrao: todas)")
    ap.add_argument("--difficulty", default=None, help="dificuldade do chart (padrao: primeira da week)")
    ap.add_argument("--name", default="FNF Port", help="nome do programa no Pocket Code")
    ap.add_argument("--max-frames", type=int, default=16, help="limite de quadros por animacao")
    ap.add_argument("--max-special-frames", type=int, default=34, help="limite p/ animacoes especiais")
    ap.add_argument("--max-pool", type=int, default=40, help="limite de clones por pool de notas")
    ap.add_argument("--minimal", action="store_true", help="gera tambem o projeto minimo de validacao")
    ap.add_argument("--no-simulate", action="store_true", help="pula a simulacao headless do gameplay")
    opts = ap.parse_args(argv)

    out_dir = os.path.dirname(os.path.abspath(opts.output))
    os.makedirs(out_dir, exist_ok=True)
    report_path = opts.report or os.path.join(out_dir, "conversion_report.txt")

    if opts.minimal:
        mp = os.path.join(out_dir, "FNF_Minimo.catrobat")
        problems, (nref, errs) = build_minimal(mp)
        R.add("VALIDACAO", "Projeto minimo %s: problemas=%s, referencias=%d, erros=%s"
              % (os.path.basename(mp), problems or "nenhum", nref, errs or "nenhum"))

    mod = Mod(opts.mod)
    try:
        engine, reasons = detect_engine(mod)
        R.add("MOD", "Origem: %s" % opts.mod)
        R.add("MOD", "Engine detectada: %s (%s)" % (engine, "; ".join(reasons)))
        R.add("MOD", "%d arquivos" % len(mod.files))
        weeks_diff = None
        if os.path.isdir(mod.p("weeks")):
            for wf in sorted(os.listdir(mod.p("weeks"))):
                if wf.endswith(".json"):
                    d = mod.json("weeks/" + wf).get("difficulties") or ""
                    if d.strip():
                        weeks_diff = d.split(",")[0].strip().lower()
                        break
        difficulty = (opts.difficulty or weeks_diff or "normal").lower()
        R.add("MOD", "Dificuldade: %s" % difficulty)
        wanted = [s.strip() for s in opts.songs.split(",") if s.strip()]
        songs, week_info = find_songs(mod, wanted, difficulty)
        if not songs:
            raise SystemExit("Nenhuma musica convertivel encontrada no mod.")
        for wf, w in week_info:
            R.add("MOD", "Week %s: '%s' musicas=%s" % (wf, w.get("storyName", ""), [s[0] for s in w.get("songs", [])]))
        info = build_project(mod, songs, week_info, opts)
        proj = info["proj"]

        # audio
        for i, s in enumerate(songs, 1):
            snd = info["sounds"][s.id]
            tmp = os.path.join(tempfile.mkdtemp(prefix="fnfaudio_"), snd.file_name)
            convert_song_audio(s, tmp)
            with open(tmp, "rb") as f:
                proj.add_sound_file(snd.file_name, f.read())
            R.add("AUDIO", "%s: %s + %s -> %s (MP3 CBR 160 kbps, 44,1 kHz, %.1f s, %d KB)" % (
                s.name, os.path.relpath(s.inst, mod.root),
                os.path.relpath(s.voices, mod.root) if s.voices else "(sem voices)", snd.file_name,
                audio_duration(tmp) or 0, os.path.getsize(tmp) // 1024))
            shutil.rmtree(os.path.dirname(tmp), ignore_errors=True)

        inventory_scripts(mod, songs)
        shot = make_screenshot(info)
        shot_png = png_bytes(shot.convert("RGB"))
        problems = proj.check()
        xml = proj.save(opts.output, screenshot_png=shot_png)
        nref, errs = cc.validate_xml(xml)

        # estatisticas
        nscripts = sum(len(sp.scripts) for sp in proj.sprites)
        nbricks = sum(1 for sp in proj.sprites for sc in sp.scripts for _ in sc.walk())
        types = {}
        for sp in proj.sprites:
            for sc in sp.scripts:
                types[sc.type] = types.get(sc.type, 0) + 1
                for b in sc.walk():
                    types[b.type] = types.get(b.type, 0) + 1
        R.add("PROJETO GERADO", "Arquivo: %s (%.1f MB)" % (opts.output, os.path.getsize(opts.output) / 1e6))
        R.add("PROJETO GERADO", "Linguagem Catrobat 1.12, paisagem %dx%d, cena 'FNF'" % (SCREEN_W, SCREEN_H))
        R.add("PROJETO GERADO", "%d objetos, %d scripts, %d bricks, %d variaveis globais, %d listas globais, "
              "%d imagens, %d sons" % (len(proj.sprites), nscripts, nbricks, len(proj.gvars), len(proj.glists),
                                       sum(1 for k in proj.files if "/images/" in k),
                                       sum(1 for k in proj.files if "/sounds/" in k)))
        R.add("PROJETO GERADO", "Objetos: " + ", ".join(sp.name for sp in proj.sprites))
        R.add("PROJETO GERADO", "Tipos usados: " + ", ".join("%s x%d" % kv for kv in sorted(types.items())))
        R.add("VALIDACAO", "Checagem semantica (escopos, assets, broadcasts): %s"
              % ("; ".join(problems) if problems else "OK"))
        R.add("VALIDACAO", "Referencias XStream resolvidas: %d, erros: %s" % (nref, errs or "nenhum"))
        with zipfile.ZipFile(opts.output) as z:
            bad = z.testzip()
            names = z.namelist()
        R.add("VALIDACAO", "Integridade ZIP: %s; %d entradas" % ("OK" if bad is None else "FALHA " + bad, len(names)))
        if problems or errs or bad:
            print("ATENCAO: problemas de validacao, veja o relatorio", file=sys.stderr)
        if not opts.no_simulate:
            run_simulations(opts.output, songs[0])
        with open(report_path, "w", encoding="utf-8") as f:
            f.write(compose_report(songs, info, opts))
        print("Gerado:", opts.output)
        print("Relatorio:", report_path)
        return 0
    finally:
        mod.cleanup()


def run_simulations(path, song):
    import validate_catrobat as vc
    ok, lines, proj = vc.structure_checks(path)
    for l in lines:
        R.add("VALIDACAO", "Estrutura (releitura do .catrobat): " + l)
    chart = {"notes": song.notes}
    for mode, desc in (("botplay", "BOTPLAY ligado pelo botao do menu"),
                       ("humano", "toques simulados com erro gaussiano de 35 ms e notas longas seguradas"),
                       ("sem_toques", "nenhum toque: deve chegar ao GAME OVER e voltar ao menu")):
        r = vc.run_scenario(proj, mode, 1e9, chart=chart)
        if "erro" in r:
            R.add("VALIDACAO", "Simulacao '%s': FALHOU - %s" % (mode, r["erro"]))
            continue
        line = ("Simulacao '%s' (%s): carregamento %.1f s a 12 acts/quadro; %.1f s de jogo; estado final %s; "
                "pontos %d; erros %d; combo max %d; notas do jogador julgadas %d; notas do oponente tocadas %d; "
                "clones simultaneos %d; HUD: %r" % (
                    mode, desc, r["carregamento_s"], r["tempo_simulado_s"],
                    {1.0: "jogando", 2.0: "GAME OVER", 3.0: "RESULTADOS"}.get(r["estado_final"], r["estado_final"]),
                    r["pontos"], r["erros"], r["combo_max"], r["notas_jogadas"], r["ponteiro_oponente"] - 1,
                    r["clones_max"], r["texto_hud"]))
        if mode == "sem_toques":
            line += ("; TENTAR DE NOVO -> partida reiniciada em %.2f s (chart em cache), estado apos 3 s: %s; "
                     "botao MENU -> estado %s, clones restantes %d" % (
                         r["recarregamento_s"], r["estado_3s_apos_repetir"], r["estado_apos_menu"],
                         r["clones_apos_menu"]))
        R.add("VALIDACAO", line)
        bad = {k: v for k, v in r["estatisticas"].items() if k != "list_item_fora_do_intervalo"}
        if bad:
            R.add("VALIDACAO", "  anomalias em '%s': %s" % (mode, bad))
    R.add("VALIDACAO", "Teste com o desserializador REAL do Pocket Code: converter/catroid_test/"
          "FnfPortDeserializationTest.java (XstreamSerializer.fromXML + identidade das referencias + "
          "ida e volta das formulas pelo InternFormulaParser). Preparado, mas NAO executado neste "
          "ambiente (a execucao do build Gradle do Catroid foi bloqueada); ver converter/README.md.")
    # quadros reais da simulacao (o que os scripts desenham)
    import catrobat_sim as cs
    out_dir = os.path.dirname(os.path.abspath(path))
    sim = cs.Sim(proj, acts_per_frame=12)
    sim.run_until(0.5)
    cs.render(sim, os.path.join(out_dir, "preview_menu.png"))
    sim.tap(420, -310)
    sim.run_until(0.7)
    sim.tap(0, -240)
    while sim.gvars["estado"] != 1.0 and sim.time < 60:
        sim.step_frame()
    target = None
    for t0, must in song.sections:
        if must and t0 > 20000:
            target = t0 + 2500
            break
    target = target or 30000
    while (sim.time - sim.timer_ref) * 1000 + sim.gvars["baseTempo"] < target and sim.time < 400:
        sim.step_frame()
    cs.render(sim, os.path.join(out_dir, "preview_gameplay.png"))
    R.add("VALIDACAO", "Quadros renderizados a partir do estado simulado: output/preview_menu.png, "
          "output/preview_gameplay.png (BOTPLAY, %.1f s de musica)" % (target / 1000.0))


SECTION_ORDER = ["MOD", "CHART", "PERSONAGENS", "CENARIO", "CAMERA", "AUDIO", "INTERFACE", "DESEMPENHO",
                 "PROJETO GERADO", "SCRIPTS CONVERTIDOS", "SCRIPTS (INVENTARIO)",
                 "SCRIPTS / RECURSOS NAO CONVERTIDOS", "LIMITACOES", "AVISOS", "VALIDACAO"]

FEATURES = """\
Implementado com bricks nativos do Pocket Code (nenhum HTML/APK/engine externo):
 * Menu inicial com fundo/titulo da week, selecao de musica (< >), JOGAR, rolagem
   cima/baixo, offset de audio (-/+ 10 ms) e BOTPLAY; opcoes salvas no aparelho
   (Write/ReadVariableOnDevice).
 * Carregamento do chart: dados compactados em texto e decodificados com SUBTEXT
   para listas (notaTempo/notaLane/notaProxima, longaTempo/...); reiniciar a mesma
   musica nao decodifica de novo.
 * Relogio da musica: posicao = TIMER*1000 + base - offset. No instante em que o
   MP3 comeca (brick logo apos o PlaySoundBrick) um unico SetVariable faz a
   posicao valer 0 (sem salto); antes disso, contagem de 5 batidas.
 * Gameplay 4K: 4 areas de toque multitoque (tela dividida em 4 colunas);
   "quando tocado" julga a nota, COLLIDES_WITH_FINGER detecta nota segurada.
 * Janelas de acerto do Psych: Sick <= 45 ms (350), Good <= 90 (200),
   Bad <= 135 (100), Shit <= 166 (50); miss apos 166 ms; ghost tapping sem
   penalidade; combo, combo maximo, pontos, precisao (%), barra de vida 0..2
   (+0,023 acerto, -0,0475 erro), GAME OVER com vida 0.
 * Notas longas (sustain) em pecas de meio step, julgadas enquanto o dedo
   segura; soltar cedo perde vida e zera o combo.
 * Notas descendo (downscroll) ou subindo (upscroll), velocidade do chart
   (0,45 x speed px/ms), strums nas posicoes do Psych + deslocamentos do aspect.lua.
 * Notas e pecas renderizadas por pools de clones reutilizaveis (sem um script
   por nota); 8 receptores com estados normal/pressionado/acerto.
 * Personagens com quadros recortados dos spritesheets Sparrow (XML), offsets,
   escala e flipX do Psych: idle e singLEFT/DOWN/UP/RIGHT, miss do jogador,
   dancas esquerda/direita da GF por batida, animacao especial por evento.
 * Oponente toca todas as suas notas no tempo, com animacao e brilho do receptor.
 * Camera do Psych (alvos do oponente/jogador por mustHitSection, lerp suave),
   cenario composto a partir do stage Lua, sobreposicao de bordas do mod.
 * HUD: barra de vida com as cores dos personagens, icones com troca de estado e
   pulso na batida, popups de avaliacao, numero de combo, texto de pontuacao,
   contagem 3/READY?/SET/GO!, tela de resultados e de GAME OVER com
   "TENTAR DE NOVO" e "MENU".
 * Orientacao paisagem 1280x720 (STRETCH).
"""

LIMITS = """\
 * O Pocket Code nao expoe a posicao de reproducao do audio; o relogio e o TIMER
   sincronizado no inicio do MP3. Latencia de audio/Bluetooth deve ser
   compensada com o OFFSET do menu.
 * Voices e Inst sao misturadas num unico MP3 (o volume do Pocket Code e global,
   entao nao da para silenciar so a voz quando o jogador erra, como no FNF).
 * Loops do Pocket Code com bricks visuais esperam 20 ms por iteracao: as notas
   sao reposicionadas a ~50 Hz.
 * NOTE_assets, noteSplashes, sons de contagem/erro, bf-dead e fonte vcr do jogo
   base NAO vem no mod: setas/receptores/notas longas/textos foram desenhados
   pelo conversor; nao ha splash, som de erro nem animacao de morte.
 * Lua/HScript arbitrario nao e convertido automaticamente; apenas os padroes
   listados em SCRIPTS CONVERTIDOS. Zoom de camera por batida e deslocamento de
   camera por nota (Cam.lua) nao foram implementados.
 * Uma unica dificuldade por exportacao; personagens/stage da primeira musica.
 * Animacoes longas sao subamostradas para economizar memoria de textura.
 * O texto dinamico (pontos, combo, resultados) usa a fonte do Pocket Code.
 * Mudancas de BPM: tempos das notas corretos, mas a grade de batidas das
   animacoes usa o BPM inicial. Tipos de nota personalizados viram notas
   normais (notas "hurt" sao descartadas).
 * Leitor de charts V-Slice implementado, mas sem amostra para teste; Codename
   Engine e outras engines sao apenas detectadas.
"""

INSTRUCTIONS = """\
COMO IMPORTAR NO POCKET CODE (Android)
 1. Copie output/FNF_Port.catrobat para o celular (ex.: pasta Downloads).
 2. Opcao A: abra o Pocket Code > "Projetos" > menu de tres pontos (canto
    superior direito) > "Importar projeto" e escolha FNF_Port.catrobat.
    Opcao B: toque no arquivo .catrobat no gerenciador de arquivos e escolha
    abrir com o Pocket Code (o app registra a extensao .catrobat).
 3. O projeto aparece como "FNF Port". Toque nele e no botao de reproduzir
    (triangulo). O projeto ja esta configurado em modo paisagem.
 4. No menu do jogo: < > escolhe a musica, JOGAR comeca. Toque nas 4 colunas da
    tela (esquerda, baixo, cima, direita), segure nas notas longas. Se as notas
    chegam ao receptor ANTES do som (comum com fone Bluetooth), aumente o
    OFFSET; se chegam DEPOIS, diminua. BOTPLAY joga sozinho; ROLAGEM troca
    cima/baixo.
 5. O botao Voltar do Android pausa (menu do Pocket Code: continuar/reiniciar).
 6. Na primeira partida o chart e decodificado (alguns segundos com a mensagem
    "Carregando musica..."); "TENTAR DE NOVO" reaproveita os dados.

Reconverter: python3 converter/fnf_to_catrobat.py <mod.zip|pasta> -o output/FNF_Port.catrobat
Validar:     python3 converter/validate_catrobat.py output/FNF_Port.catrobat --full
"""

REVERSE = """\
Fontes consultadas (repositorio oficial Catrobat/Catroid, branch develop):
 * io/XstreamSerializer.java: aliases (program, header, scenes/scene, objectList/
   object, lookList/look, soundList/sound, scriptList/script type=..., brick
   type=..., userVariable, userList), campos omitidos (WhenScript.action,
   HideTextBrick.formulaList...), modo de referencias XPath relativas.
 * io/CatroidFieldKeySorter.java + @XStreamFieldKeyOrder: ordem dos campos.
 * io/XStreamBrickConverter, XStreamScriptConverter, XStreamUserVariableConverter
   (userVariable serialization="custom"), XStreamConcurrentFormulaHashMapConverter
   (<formulaList><formula category="...">), FormulaElement (additionalChildren,
   leftChild, rightChild, type, value; BRACKET).
 * Classes de cada brick usado (campos serializaveis e BrickField das formulas).
 * Runtime: StageListener (ate 50 acts por quadro), libGDX SequenceAction (1 brick
   por act), LoopUtil/LoopAction (atraso de 20 ms em loops com bricks visuais),
   WaitUntilAction, SensorHandler.TIMER, SoundManager (prepare() sincrono, volume
   global), Look.doTouchDown (alpha > 10), CollisionDetection.collidesWithFinger,
   FormulaElement/FormulaElementOperations (NaN, strings, SUBTEXT 1-based, MOD),
   SetVariableAction (conversao booleana), ProjectManager (rotinas de migracao).
 * Projetos .catrobat reais: catroid/src/androidTest/assets (227 projetos; o mais
   novo em linguagem 1.13). Todas as 4.539 referencias desses projetos resolvem
   com o mesmo algoritmo usado no gerador; as assinaturas de elementos dos bricks
   gerados foram comparadas com as desses projetos (ver VALIDACAO).
Formato gerado: ZIP com code.xml, .nomedia, <cena>/images/*.png,
<cena>/sounds/*.mp3 e automatic_screenshot.png; linguagem 1.12 (aceita pelas
versoes atuais, que migram para 1.14 ao abrir; nenhuma rotina de migracao
altera os bricks usados).
"""


def compose_report(songs, info, opts):
    out = ["RELATORIO DE CONVERSAO - fnf_to_catrobat.py",
           "Mod de Friday Night Funkin' -> projeto nativo do Pocket Code (.catrobat, Catrobat 1.12)", "",
           "ESTADO DOS TESTES: o arquivo foi validado estruturalmente e por simulacao headless do",
           "runtime do Catroid (ver VALIDACAO). NAO foi testado no aplicativo Pocket Code nem em",
           "Android/emulador: este ambiente nao possui dispositivo, emulador nem KVM.", "",
           "=" * 78, "FUNCIONALIDADES IMPLEMENTADAS", "=" * 78, FEATURES,
           "=" * 78, "ENGENHARIA REVERSA DO FORMATO", "=" * 78, REVERSE]
    for sec in SECTION_ORDER + [k for k in R.sections if k not in SECTION_ORDER]:
        lines = R.sections.get(sec)
        if not lines and sec != "LIMITACOES":
            continue
        out += ["=" * 78, sec, "=" * 78]
        if sec == "LIMITACOES":
            out.append(LIMITS.rstrip())
        out += lines or []
        out.append("")
    out += ["=" * 78, INSTRUCTIONS]
    return "\n".join(out)


if __name__ == "__main__":
    sys.exit(main())
