"""
catrobat_sim.py - Simulador headless de um subconjunto do runtime do Catroid.

Le o code.xml GERADO (nao os objetos Python), resolve as referencias XStream e
executa os scripts com a semantica do Pocket Code observada no codigo-fonte:

* libGDX SequenceAction: um brick por "act"; o palco faz varios acts por quadro
  (StageListener: ate 50, adaptativo) - aqui um numero fixo configuravel;
* loops (Forever/Repeat/RepeatUntil) com o atraso de 20 ms (LoopUtil) quando o
  corpo contem bricks visuais (PlaceAt, SetX/Y, SetLook*, SetSize, SetTransparency...);
* WaitUntil verifica a condicao a cada 20 ms; Wait e temporal;
* broadcasts reiniciam scripts ja em execucao; clones copiam variaveis/listas locais;
* toque: o objeto visivel mais acima cujo pixel tem alpha > 10 recebe o "quando tocado";
  COLLIDES_WITH_FINGER testa os dedos ativos contra pixels nao transparentes;
* formulas: Double/String, NaN, comparacoes com Double.compareTo (NaN e o maior),
  LIST_ITEM/SUBTEXT 1-based, JOIN com formatacao de inteiros, IF_THEN_ELSE, MOD do Catroid.

Nao e o Pocket Code: serve para encontrar erros de logica antes de testar no aparelho.
"""

import io
import math
import zipfile
import xml.etree.ElementTree as ET

from PIL import Image

LOOP_DELAY_BRICKS = {"PlaceAtBrick", "SetXBrick", "SetYBrick", "ChangeXByNBrick", "ChangeYByNBrick",
                     "GoToBrick", "IfOnEdgeBounceBrick", "MoveNStepsBrick", "TurnLeftBrick",
                     "TurnRightBrick", "PointInDirectionBrick", "PointToBrick", "SetLookBrick",
                     "SetLookByIndexBrick", "NextLookBrick", "PreviousLookBrick", "SetSizeToBrick",
                     "ChangeSizeByNBrick", "SetTransparencyBrick", "ChangeTransparencyByNBrick",
                     "SetBrightnessBrick", "ChangeBrightnessByNBrick", "SetColorBrick",
                     "ChangeColorByNBrick", "SetBackgroundBrick", "SetBackgroundByIndexBrick",
                     "SetVolumeToBrick", "ChangeVolumeByNBrick", "SetTempoBrick"}

NaN = float("nan")


class SimError(Exception):
    pass


class StopThisScript(Exception):
    pass


# --------------------------------------------------------------------------
# Leitura do code.xml
# --------------------------------------------------------------------------

class XmlModel:
    def __init__(self, xml_bytes):
        self.root = ET.fromstring(xml_bytes)
        self.by_path = {}
        self._index(self.root, [self.root.tag])

    def _index(self, el, path):
        el.set("__path", "/".join(path))
        self.by_path["/".join(path)] = el
        cnt = {}
        for c in el:
            n = cnt.get(c.tag, 0) + 1
            cnt[c.tag] = n
            self._index(c, path + [c.tag if n == 1 else "%s[%d]" % (c.tag, n)])

    def resolve(self, el):
        ref = el.get("reference")
        if ref is None:
            return el
        cur = el.get("__path").split("/")
        for part in ref.split("/"):
            if part == "..":
                cur.pop()
            elif part != ".":
                cur.append(part)
        return self.by_path["/".join(cur)]


class FormulaNode:
    __slots__ = ("type", "value", "left", "right", "extra")


def parse_formula(el):
    n = FormulaNode()
    n.type = el.findtext("type")
    n.value = el.findtext("value")
    lc, rc = el.find("leftChild"), el.find("rightChild")
    n.left = parse_formula(lc) if lc is not None else None
    n.right = parse_formula(rc) if rc is not None else None
    ac = el.find("additionalChildren")
    n.extra = [parse_formula(c) for c in ac] if ac is not None else []
    return n


class BrickM:
    def __init__(self):
        self.type = None
        self.formulas = {}
        self.var = None
        self.list = None
        self.look = None
        self.sound = None
        self.message = None
        self.then = []
        self.else_ = []
        self.body = []
        self.spinner = None
        self.has_delay = False


def parse_bricks(model, parent):
    out = []
    if parent is None:
        return out
    for b in parent.findall("brick"):
        m = BrickM()
        m.type = b.get("type")
        fl = b.find("formulaList")
        if fl is not None:
            for f in fl.findall("formula"):
                m.formulas[f.get("category")] = parse_formula(f)
        for tag in ("userVariable", "userList"):
            e = b.find(tag)
            if e is not None:
                e = model.resolve(e)
                name = e.findtext("userVariable/default/name") if tag == "userVariable" else e.findtext("name")
                setattr(m, "var" if tag == "userVariable" else "list", name)
        lk = b.find("look")
        if lk is not None:
            m.look = model.resolve(lk).get("name")
        sd = b.find("sound")
        if sd is not None:
            m.sound = model.resolve(sd).get("name")
        m.message = b.findtext("broadcastMessage")
        m.spinner = b.findtext("spinnerSelection")
        m.then = parse_bricks(model, b.find("ifBranchBricks"))
        m.else_ = parse_bricks(model, b.find("elseBranchBricks"))
        m.body = parse_bricks(model, b.find("loopBricks"))
        if m.body:
            stack = list(m.body)
            while stack:
                x = stack.pop()
                if x.type in LOOP_DELAY_BRICKS:
                    m.has_delay = True
                stack += x.then + x.else_ + x.body
        out.append(m)
    return out


class SpriteM:
    def __init__(self, name):
        self.name = name
        self.looks = []      # (name, fileName)
        self.sounds = []
        self.scripts = []    # (type, message, bricks)
        self.local_vars = []
        self.local_lists = []


def load_project(path):
    z = zipfile.ZipFile(path)
    xml = z.read("code.xml")
    model = XmlModel(xml)
    scene = model.root.find("scenes/scene")
    scene_name = scene.findtext("name")
    sprites = []
    for ob in scene.find("objectList").findall("object"):
        sp = SpriteM(ob.get("name"))
        for lk in ob.find("lookList"):
            lk = model.resolve(lk)
            sp.looks.append((lk.get("name"), lk.get("fileName")))
        for sd in ob.find("soundList"):
            sd = model.resolve(sd)
            sp.sounds.append((sd.get("name"), sd.get("fileName")))
        for sc in ob.find("scriptList").findall("script"):
            sp.scripts.append((sc.get("type"), sc.findtext("receivedMessage"),
                               parse_bricks(model, sc.find("brickList"))))
        for v in ob.find("userVariables"):
            sp.local_vars.append(model.resolve(v).findtext("userVariable/default/name"))
        for l in ob.find("userLists"):
            sp.local_lists.append(model.resolve(l).findtext("name"))
        sprites.append(sp)
    gvars = [model.resolve(v).findtext("userVariable/default/name") for v in model.root.find("programVariableList")]
    glists = [model.resolve(l).findtext("name") for l in model.root.find("programListOfLists")]
    images = {}
    for sp in sprites:
        for _, fn in sp.looks:
            if fn not in images:
                images[fn] = Image.open(io.BytesIO(z.read("%s/images/%s" % (scene_name, fn)))).convert("RGBA")
    header = model.root.find("header")
    return dict(sprites=sprites, gvars=gvars, glists=glists, images=images,
                width=int(header.findtext("screenWidth")), height=int(header.findtext("screenHeight")))


# --------------------------------------------------------------------------
# Interpretacao de formulas
# --------------------------------------------------------------------------

def to_double(v):
    if isinstance(v, bool):
        return 1.0 if v else 0.0
    if isinstance(v, (int, float)):
        return float(v)
    if isinstance(v, str):
        try:
            return float(v.strip()) if v.strip() else NaN
        except ValueError:
            return NaN
    return NaN


def java_compare(a, b):
    # Double.compareTo: NaN e maior que tudo, NaN == NaN
    an, bn = math.isnan(a), math.isnan(b)
    if an and bn:
        return 0
    if an:
        return 1
    if bn:
        return -1
    return (a > b) - (a < b)


def fmt_num(v):
    if isinstance(v, float) and not math.isnan(v) and not math.isinf(v) and v == int(v):
        return str(int(v))
    return str(v)


def as_string(v):
    if isinstance(v, float):
        return fmt_num(v)
    return str(v)


def catroid_equal(a, b):
    sa, sb = as_string(a), as_string(b)
    try:
        return float(sa) == float(sb)
    except ValueError:
        return sa == sb


class Evaluator:
    def __init__(self, sim):
        self.sim = sim

    def ev(self, n, inst):
        t = n.type
        self.sim.cost["nos"] += 1
        if t == "OPERATOR" and n.value in ("PLUS", "MINUS", "MULT", "DIVIDE") and n.left is not None:
            self.sim.cost["bigdecimal"] += 1
        if t == "NUMBER":
            return float(n.value)
        if t == "STRING":
            return n.value or ""
        if t == "BRACKET":
            return self.ev(n.right, inst)
        if t == "USER_VARIABLE":
            return self.sim.get_var(inst, n.value)
        if t == "USER_LIST":
            lst = self.sim.get_list(inst, n.value)
            return " ".join(as_string(x) for x in lst)
        if t == "SENSOR":
            return self.sim.sensor(inst, n.value)
        if t == "OPERATOR":
            return self.op(n, inst)
        if t == "FUNCTION":
            return self.func(n, inst)
        raise SimError("tipo de formula desconhecido %s" % t)

    def op(self, n, inst):
        v = n.value
        if n.left is None:
            r = to_double(self.ev(n.right, inst))
            if v == "MINUS":
                return -r
            if v == "LOGICAL_NOT":
                return 1.0 if r == 0 else 0.0
            raise SimError(v)
        lo, ro = self.ev(n.left, inst), self.ev(n.right, inst)
        l, r = to_double(lo), to_double(ro)
        nan = math.isnan(l) or math.isnan(r)
        if v == "PLUS":
            return NaN if nan else l + r
        if v == "MINUS":
            return NaN if nan else l - r
        if v == "MULT":
            return NaN if nan else l * r
        if v == "DIVIDE":
            return NaN if nan or r == 0 else l / r
        if v == "EQUAL":
            return 1.0 if catroid_equal(lo, ro) else 0.0
        if v == "NOT_EQUAL":
            return 0.0 if catroid_equal(lo, ro) else 1.0
        if v == "GREATER_THAN":
            return 1.0 if java_compare(l, r) > 0 else 0.0
        if v == "GREATER_OR_EQUAL":
            return 1.0 if java_compare(l, r) >= 0 else 0.0
        if v == "SMALLER_THAN":
            return 1.0 if java_compare(l, r) < 0 else 0.0
        if v == "SMALLER_OR_EQUAL":
            return 1.0 if java_compare(l, r) <= 0 else 0.0
        if v == "LOGICAL_AND":
            return 1.0 if (l != 0 and r != 0) else 0.0
        if v == "LOGICAL_OR":
            return 1.0 if (l != 0 or r != 0) else 0.0
        raise SimError("operador %s" % v)

    def func(self, n, inst):
        v = n.value
        if v == "TRUE":
            return 1.0
        if v == "FALSE":
            return 0.0
        if v == "LIST_ITEM":
            lst = self.sim.get_list(inst, n.right.value)
            i = to_double(self.ev(n.left, inst))
            i = 0 if math.isnan(i) else int(i)
            if 1 <= i <= len(lst):
                return lst[i - 1]
            self.sim.stat("list_item_fora_do_intervalo")
            return ""
        if v == "NUMBER_OF_ITEMS":
            return float(len(self.sim.get_list(inst, n.left.value)))
        if v == "SUBTEXT":
            a = to_double(self.ev(n.left, inst))
            b = to_double(self.ev(n.right, inst))
            s = as_string(self.ev(n.extra[0], inst))
            a = 0 if math.isnan(a) else int(a)
            b = 0 if math.isnan(b) else int(b)
            st = a - 1
            if st < 0 or b < 0 or st > b or b > len(s):
                return ""
            return s[st:b]
        if v == "JOIN":
            return self.jstr(n.left, inst) + self.jstr(n.right, inst)
        if v == "IF_THEN_ELSE":
            c = to_double(self.ev(n.left, inst))
            a, b = self.ev(n.right, inst), self.ev(n.extra[0], inst)
            if math.isnan(c):
                return NaN
            pick = a if c != 0 else b
            return pick if isinstance(pick, str) else to_double(pick)
        args = [to_double(self.ev(x, inst)) if x is not None else None
                for x in [n.left, n.right] + n.extra]
        x = args[0]
        if x is None:
            return 0.0
        if v == "ROUND":
            return 0.0 if math.isnan(x) else float(math.floor(x + 0.5))
        if v == "FLOOR":
            return float(math.floor(x)) if not math.isnan(x) else NaN
        if v == "CEIL":
            return float(math.ceil(x)) if not math.isnan(x) else NaN
        if v == "ABS":
            return abs(x)
        if v == "SIN":
            return math.sin(math.radians(x))
        if v == "COS":
            return math.cos(math.radians(x))
        y = args[1]
        if v == "MAX":
            return NaN if math.isnan(x) or math.isnan(y) else max(x, y)
        if v == "MIN":
            return NaN if math.isnan(x) or math.isnan(y) else min(x, y)
        if v == "MOD":
            if x == 0 or y == 0:
                return x
            if y > 0:
                x = x % y if x >= 0 else x - y * math.floor(x / y)
                return math.fmod(x, y)
            return math.fmod(x, y)
        raise SimError("funcao nao simulada %s" % v)

    def jstr(self, child, inst):
        if child is None:
            return ""
        o = self.ev(child, inst)
        if child.type == "STRING":
            return o
        return as_string(o)


# --------------------------------------------------------------------------
# Runtime
# --------------------------------------------------------------------------

class Instance:
    def __init__(self, sprite, sim, parent=None):
        self.sprite = sprite
        self.is_clone = parent is not None
        self.alive = True
        if parent:
            self.x, self.y, self.size, self.transp = parent.x, parent.y, parent.size, parent.transp
            self.visible, self.look = parent.visible, parent.look
            self.vars = dict(parent.vars)
            self.lists = {k: list(v) for k, v in parent.lists.items()}
        else:
            self.x = self.y = 0.0
            self.size, self.transp = 100.0, 0.0
            self.visible = True
            self.look = 1 if sprite.looks else 0
            self.vars = {v: 0.0 for v in sprite.local_vars}
            self.lists = {l: [] for l in sprite.local_lists}
        self.threads = {}


class Sim:
    def __init__(self, project, acts_per_frame=20, fps=60.0):
        self.p = project
        self.apf = acts_per_frame
        self.dt = 1.0 / fps
        self.ad = self.dt / acts_per_frame
        self.time = 0.0
        self.timer_ref = 0.0
        self.gvars = {v: 0.0 for v in project["gvars"]}
        self.glists = {l: [] for l in project["glists"]}
        self.instances = [Instance(sp, self) for sp in project["sprites"]]
        self.ev = Evaluator(self)
        self.pending = []
        self.fingers = {}
        self.stats = {}
        self.log = []
        self.errors = []
        self.sounds_played = []
        self.texts = {}
        self.acts = 0
        self.max_clones = 0
        for inst in self.instances:
            for i, (typ, msg, bricks) in enumerate(inst.sprite.scripts):
                if typ == "StartScript":
                    self.start_thread(inst, i)
        self.device = {}
        self.cost = {"nos": 0, "bigdecimal": 0, "bricks": 0}

    # --- dados ---
    def stat(self, k, n=1):
        self.stats[k] = self.stats.get(k, 0) + n

    def get_var(self, inst, name):
        if name in inst.vars:
            return inst.vars[name]
        if name in self.gvars:
            return self.gvars[name]
        raise SimError("variavel inexistente %s em %s" % (name, inst.sprite.name))

    def set_var(self, inst, name, value):
        if name in inst.vars:
            inst.vars[name] = value
        elif name in self.gvars:
            self.gvars[name] = value
        else:
            raise SimError("variavel inexistente %s" % name)

    def get_list(self, inst, name):
        if name in inst.lists:
            return inst.lists[name]
        if name in self.glists:
            return self.glists[name]
        raise SimError("lista inexistente %s" % name)

    def sensor(self, inst, name):
        if name == "TIMER":
            return self.time - self.timer_ref
        if name == "COLLIDES_WITH_FINGER":
            return 1.0 if any(self.hit(inst, fx, fy) for fx, fy in self.fingers.values()) else 0.0
        raise SimError("sensor nao simulado %s" % name)

    def f(self, inst, brick, cat):
        return self.ev.ev(brick.formulas[cat], inst)

    def fd(self, inst, brick, cat):
        return to_double(self.f(inst, brick, cat))

    # --- geometria ---
    def image(self, inst):
        if not inst.sprite.looks or inst.look < 1:
            return None
        return self.p["images"][inst.sprite.looks[inst.look - 1][1]]

    def hit(self, inst, x, y):
        if not inst.visible or not inst.alive:
            return False
        im = self.image(inst)
        if im is None:
            return False
        s = inst.size / 100.0
        if s <= 0:
            return False
        u = (x - inst.x) / s + im.width / 2.0
        v = (inst.y - y) / s + im.height / 2.0
        if 0 <= u < im.width and 0 <= v < im.height:
            return im.getpixel((int(u), int(v)))[3] > 10
        return False

    def tap(self, x, y):
        for inst in reversed(self.instances):
            if self.hit(inst, x, y):
                for i, (typ, msg, bricks) in enumerate(inst.sprite.scripts):
                    if typ == "WhenScript":
                        self.pending.append((inst, i, None))
                return inst.sprite.name
        return None

    # --- threads ---
    def start_thread(self, inst, idx, waiter=None):
        typ, msg, bricks = inst.sprite.scripts[idx]
        g = self.run_seq(inst, bricks)
        inst.threads[idx] = g
        if waiter is not None:
            waiter.append((inst, idx, g))

    def broadcast(self, msg, waiter=None):
        for inst in list(self.instances):
            for i, (typ, m, bricks) in enumerate(inst.sprite.scripts):
                if typ == "BroadcastScript" and m == msg:
                    self.pending.append((inst, i, waiter))
        self.log.append((round(self.time, 3), "broadcast", msg))

    def run_seq(self, inst, bricks):
        for b in bricks:
            yield from self.run_brick(inst, b)

    def loop(self, inst, b, cond):
        # RepeatUntilAction.delegate reavalia a condicao em todo act (custo apenas)
        per_act = b.formulas.get("REPEAT_UNTIL_CONDITION") if b.type == "RepeatUntilBrick" else None
        while True:
            if cond is not None and cond():
                return
            t0 = self.time
            if b.body:
                for _ in self.run_seq(inst, b.body):
                    if per_act is not None:
                        self.ev.ev(per_act, inst)
                    yield
            else:
                yield
            if b.has_delay:
                while self.time - t0 < 0.02 - 1e-9:
                    if per_act is not None:
                        self.ev.ev(per_act, inst)
                    yield

    def run_brick(self, inst, b):
        t = b.type
        self.cost["bricks"] += 1
        sim = self
        if t == "SetVariableBrick":
            v = self.f(inst, b, "VARIABLE")
            if isinstance(v, str):
                fr = b.formulas["VARIABLE"]
                if fr.type != "STRING":
                    d = to_double(v)
                    if not math.isnan(d):
                        v = d
            self.set_var(inst, b.var, v)
        elif t == "ChangeVariableBrick":
            d = self.fd(inst, b, "VARIABLE_CHANGE")
            cur = to_double(self.get_var(inst, b.var))
            if not math.isnan(d):
                self.set_var(inst, b.var, (0.0 if math.isnan(cur) else cur) + d)
            else:
                self.stat("change_var_nan")
        elif t in ("IfLogicBeginBrick", "IfThenLogicBeginBrick"):
            c = self.fd(inst, b, "IF_CONDITION")
            br = b.then if (c != 0 and not math.isnan(c)) else b.else_
            if math.isnan(c):
                self.stat("if_nan")
            if br:
                yield from self.run_seq(inst, br)
                return
        elif t == "ForeverBrick":
            yield from self.loop(inst, b, None)
            return
        elif t == "RepeatBrick":
            n = self.fd(inst, b, "TIMES_TO_REPEAT")
            n = 0 if math.isnan(n) else int(n)
            cnt = [0]

            def cond():
                cnt[0] += 1
                return cnt[0] > n
            yield from self.loop(inst, b, cond)
            return
        elif t == "RepeatUntilBrick":
            yield from self.loop(inst, b, lambda: self.fd(inst, b, "REPEAT_UNTIL_CONDITION") != 0)
            return
        elif t == "WaitBrick":
            d = self.fd(inst, b, "TIME_TO_WAIT_IN_SECONDS")
            t0 = self.time
            while self.time - t0 < d - 1e-9:
                yield
            return
        elif t == "WaitUntilBrick":
            while True:
                c = self.fd(inst, b, "IF_CONDITION")
                if c != 0 and not math.isnan(c):
                    break
                t0 = self.time
                while self.time - t0 < 0.02 - 1e-9:
                    yield
        elif t == "PlaceAtBrick":
            x, y = self.fd(inst, b, "X_POSITION"), self.fd(inst, b, "Y_POSITION")
            if math.isnan(x) or math.isnan(y):
                self.stat("place_nan")
            else:
                inst.x, inst.y = x, y
        elif t == "SetXBrick":
            x = self.fd(inst, b, "X_POSITION")
            if not math.isnan(x):
                inst.x = x
        elif t == "SetYBrick":
            y = self.fd(inst, b, "Y_POSITION")
            if not math.isnan(y):
                inst.y = y
            else:
                self.stat("set_y_nan")
        elif t == "SetSizeToBrick":
            s = self.fd(inst, b, "SIZE")
            if not math.isnan(s):
                inst.size = max(0.0, s)
        elif t == "SetTransparencyBrick":
            s = self.fd(inst, b, "TRANSPARENCY")
            if not math.isnan(s):
                inst.transp = min(100.0, max(0.0, s))
        elif t == "SetLookBrick":
            names = [n for n, _ in inst.sprite.looks]
            inst.look = names.index(b.look) + 1
        elif t == "SetLookByIndexBrick":
            i = self.fd(inst, b, "LOOK_INDEX")
            if math.isnan(i):
                self.stat("look_nan")
            else:
                i = int(i)
                if 1 <= i <= len(inst.sprite.looks):
                    inst.look = i
                else:
                    self.stat("look_fora_do_intervalo:%s" % inst.sprite.name)
        elif t == "ShowBrick":
            inst.visible = True
        elif t == "HideBrick":
            inst.visible = False
        elif t == "BroadcastBrick":
            self.broadcast(b.message)
        elif t == "BroadcastWaitBrick":
            waiter = []
            self.broadcast(b.message, waiter)
            yield
            while any(i.alive and i.threads.get(ix) is g for i, ix, g in waiter):
                yield
            return
        elif t == "CloneBrick":
            c = Instance(inst.sprite, self, parent=inst)
            pos = self.instances.index(inst) + 1
            self.instances.insert(pos, c)
            for i, (typ, msg, bricks) in enumerate(inst.sprite.scripts):
                if typ == "WhenClonedScript":
                    self.pending.append((c, i, None))
            nclones = sum(1 for x in self.instances if x.is_clone)
            self.max_clones = max(self.max_clones, nclones)
        elif t == "DeleteThisCloneBrick":
            if inst.is_clone:
                inst.alive = False
                inst.visible = False
                yield
                return
        elif t == "AddItemToUserListBrick":
            v = self.f(inst, b, "LIST_ADD_ITEM")
            self.get_list(inst, b.list).append(v)
        elif t == "ReplaceItemInUserListBrick":
            i = self.fd(inst, b, "REPLACE_ITEM_IN_USERLIST_INDEX")
            v = self.f(inst, b, "REPLACE_ITEM_IN_USERLIST_VALUE")
            lst = self.get_list(inst, b.list)
            if not math.isnan(i) and 1 <= int(i) <= len(lst):
                lst[int(i) - 1] = v
            else:
                self.stat("replace_fora_do_intervalo")
        elif t == "ClearUserListBrick":
            self.get_list(inst, b.list).clear()
        elif t == "PlaySoundBrick":
            self.sounds_played.append((round(self.time, 3), b.sound))
        elif t in ("StopAllSoundsBrick", "NoteBrick", "SetVolumeToBrick", "ComeToFrontBrick"):
            pass
        elif t == "StopScriptBrick":
            if str(b.spinner) == "0":
                raise StopThisScript()
            raise SimError("StopScriptBrick %s nao simulado" % b.spinner)
        elif t == "ResetTimerBrick":
            self.timer_ref = self.time
        elif t == "ShowTextColorSizeAlignmentBrick":
            self.texts[b.var] = (self.fd(inst, b, "X_POSITION"), self.fd(inst, b, "Y_POSITION"))
        elif t == "HideTextBrick":
            self.texts.pop(b.var, None)
        elif t == "WriteVariableOnDeviceBrick":
            self.device[b.var] = self.get_var(inst, b.var)
        elif t == "ReadVariableFromDeviceBrick":
            if b.var in self.device:
                self.set_var(inst, b.var, self.device[b.var])
        else:
            raise SimError("brick nao simulado: %s" % t)
        yield

    # --- laco principal ---
    def step_frame(self):
        for _ in range(self.apf):
            self.acts += 1
            for inst, idx, waiter in self.pending:
                if inst.alive:
                    self.start_thread(inst, idx, waiter)
            self.pending = []
            for inst in list(self.instances):
                if not inst.alive:
                    continue
                for idx in list(inst.threads):
                    th = inst.threads.get(idx)
                    if th is None:
                        continue
                    try:
                        next(th)
                    except (StopIteration, StopThisScript):
                        if inst.threads.get(idx) is th:
                            del inst.threads[idx]
                    if not inst.alive:
                        break
            self.instances = [i for i in self.instances if i.alive]
            self.time += self.ad

    def run_until(self, t_end, on_frame=None):
        while self.time < t_end:
            if on_frame:
                on_frame(self)
            self.step_frame()

    def find(self, name):
        return [i for i in self.instances if i.sprite.name == name]


def render(sim, path=None, font_path=None):
    """Desenha o estado atual do palco simulado (para inspecao visual)."""
    from PIL import ImageDraw, ImageFont
    W, H = sim.p["width"], sim.p["height"]
    canvas = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    for inst in sim.instances:
        if not inst.visible or not inst.alive:
            continue
        im = sim.image(inst)
        if im is None:
            continue
        if inst.size != 100:
            s = inst.size / 100.0
            im = im.resize((max(1, int(im.width * s)), max(1, int(im.height * s))))
        if inst.transp > 0:
            a = im.split()[3].point(lambda v: int(v * (1 - inst.transp / 100.0)))
            im = im.copy()
            im.putalpha(a)
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        layer.paste(im, (int(W / 2 + inst.x - im.width / 2), int(H / 2 - inst.y - im.height / 2)), im)
        canvas.alpha_composite(layer)
    d = ImageDraw.Draw(canvas)
    try:
        font = ImageFont.truetype(font_path or "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 22)
    except Exception:  # noqa: BLE001
        font = ImageFont.load_default()
    for name, (x, y) in sim.texts.items():
        val = sim.gvars.get(name, "")
        s = as_string(val)
        if s:
            w = d.textlength(s, font=font)
            d.text((W / 2 + x - w / 2, H / 2 - y - 12), s, font=font, fill=(255, 255, 255, 255),
                   stroke_width=2, stroke_fill=(0, 0, 0, 255))
    if path:
        canvas.convert("RGB").save(path)
    return canvas
