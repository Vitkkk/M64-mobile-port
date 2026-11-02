"""
catrobat_core.py - Serializador nativo do formato Catrobat (Pocket Code).

Este modulo reproduz a serializacao XStream usada pelo Catroid
(org.catrobat.catroid.io.XstreamSerializer) para a linguagem Catrobat 1.12:

* elementos e atributos copiados de projetos reais (catroid/src/androidTest/assets);
* ordem dos campos = CatroidFieldKeySorter (campos da superclasse primeiro,
  depois ordem alfabetica dentro de cada classe; @XStreamFieldKeyOrder para
  Project/Scene/Sprite);
* objetos compartilhados (UserVariable, UserList, LookData, SoundInfo) sao
  escritos por completo na primeira ocorrencia em ordem de documento e depois
  referenciados com reference="caminho XPath relativo", exatamente como o modo
  padrao XPATH_RELATIVE_REFERENCES do XStream.

Nenhuma tag foi inventada: cada tipo de brick/script usado aqui aparece em
projetos .catrobat reais ou nos campos serializaveis das classes Java/Kotlin
correspondentes do Catroid.
"""

import io
import uuid
import zipfile
from xml.sax.saxutils import escape as _xml_escape

# --------------------------------------------------------------------------
# Identidades
# --------------------------------------------------------------------------

_UUID_NS = uuid.UUID("6f8c2b8e-6d0a-4a51-9b39-6b2f7b0e5c11")


def stable_uuid(*parts):
    """UUID deterministico (para gerar sempre o mesmo code.xml)."""
    return str(uuid.uuid5(_UUID_NS, "/".join(str(p) for p in parts)))


class UserVariable:
    def __init__(self, name, scope="global"):
        self.name = name
        self.scope = scope
        self.key = stable_uuid("var", scope, name)

    def __repr__(self):
        return "Var(%s)" % self.name


class UserList:
    def __init__(self, name, scope="global"):
        self.name = name
        self.scope = scope
        self.key = stable_uuid("list", scope, name)

    def __repr__(self):
        return "List(%s)" % self.name


class Look:
    def __init__(self, name, file_name):
        self.name = name
        self.file_name = file_name


class Sound:
    def __init__(self, name, file_name):
        self.name = name
        self.file_name = file_name


# --------------------------------------------------------------------------
# Arvore XML generica + escritor com referencias XStream
# --------------------------------------------------------------------------

class Node:
    __slots__ = ("tag", "attrs", "children", "text")

    def __init__(self, tag, attrs=None, children=None, text=None):
        self.tag = tag
        self.attrs = attrs or []
        self.children = children if children is not None else []
        self.text = text


class ObjRef:
    """Marca um objeto compartilhado (escrito uma vez, depois referenciado)."""
    __slots__ = ("tag", "obj")

    def __init__(self, tag, obj):
        self.tag = tag
        self.obj = obj


def _esc_text(s):
    return _xml_escape(str(s), {'"': "&quot;", "'": "&apos;"})


def _esc_attr(s):
    return _xml_escape(str(s), {'"': "&quot;", "'": "&apos;"})


def full_node_for(tag, obj):
    if isinstance(obj, UserVariable):
        return Node(tag, [("type", "UserVariable"), ("serialization", "custom")], [
            Node("userVariable", children=[
                Node("default", children=[
                    Node("initialIndex", text="-1"),
                    Node("deviceValueKey", text=obj.key),
                    Node("name", text=obj.name),
                ])
            ])
        ])
    if isinstance(obj, UserList):
        return Node(tag, children=[
            Node("deviceListKey", text=obj.key),
            Node("initialIndex", text="-1"),
            Node("name", text=obj.name),
        ])
    if isinstance(obj, Look):
        return Node(tag, [("fileName", obj.file_name), ("name", obj.name)], [
            Node("isWebRequest", text="false"),
            Node("valid", text="true"),
        ])
    if isinstance(obj, Sound):
        return Node(tag, [("fileName", obj.file_name), ("name", obj.name)])
    raise TypeError(obj)


def relative_path(cur, target):
    """Path.relativeTo do XStream (chunks com indice '[n]' quando n>1)."""
    i = 0
    while i < len(cur) and i < len(target) and cur[i] == target[i]:
        i += 1
    parts = [".."] * (len(cur) - i) + list(target[i:])
    return "/".join(parts) if parts else "."


class XStreamWriter:
    def __init__(self):
        self.out = io.StringIO()
        self.written = {}  # id(obj) -> path chunks
        self.keep = []

    def write_document(self, root):
        self.out.write('<?xml version="1.0" encoding="UTF-8" standalone="yes" ?>\n')
        self._write(root, [], 0, {})
        return self.out.getvalue()

    def _chunk(self, tag, counters):
        n = counters.get(tag, 0) + 1
        counters[tag] = n
        return tag if n == 1 else "%s[%d]" % (tag, n)

    def _write(self, node, parent_path, depth, counters):
        if isinstance(node, ObjRef):
            chunk = self._chunk(node.tag, counters)
            path = parent_path + [chunk]
            key = id(node.obj)
            if key in self.written:
                ref = relative_path(path, self.written[key])
                self.out.write("  " * depth + '<%s reference="%s"/>\n' % (node.tag, _esc_attr(ref)))
                return
            self.written[key] = path
            self.keep.append(node.obj)
            node = full_node_for(node.tag, node.obj)
            self._emit(node, path, depth)
            return
        chunk = self._chunk(node.tag, counters)
        self._emit(node, parent_path + [chunk], depth)

    def _emit(self, node, path, depth):
        ind = "  " * depth
        attrs = "".join(' %s="%s"' % (k, _esc_attr(v)) for k, v in node.attrs)
        if node.text is not None:
            self.out.write("%s<%s%s>%s</%s>\n" % (ind, node.tag, attrs, _esc_text(node.text), node.tag))
        elif not node.children:
            self.out.write("%s<%s%s/>\n" % (ind, node.tag, attrs))
        else:
            self.out.write("%s<%s%s>\n" % (ind, node.tag, attrs))
            counters = {}
            for c in node.children:
                self._write(c, path, depth + 1, counters)
            self.out.write("%s</%s>\n" % (ind, node.tag))


# --------------------------------------------------------------------------
# Formulas (org.catrobat.catroid.formulaeditor.FormulaElement)
# --------------------------------------------------------------------------

_PRIORITY = {"LOGICAL_OR": 1, "LOGICAL_AND": 2, "EQUAL": 3, "NOT_EQUAL": 4,
             "SMALLER_OR_EQUAL": 4, "GREATER_OR_EQUAL": 4, "SMALLER_THAN": 4,
             "GREATER_THAN": 4, "PLUS": 5, "MINUS": 5, "MULT": 6, "DIVIDE": 6,
             "POW": 7, "LOGICAL_NOT": 4}

KNOWN_FUNCTIONS = {
    "SIN", "COS", "TAN", "LN", "LOG", "SQRT", "RAND", "ROUND", "ABS", "PI", "MOD",
    "ARCSIN", "ARCCOS", "ARCTAN", "ARCTAN2", "EXP", "POWER", "FLOOR", "CEIL", "MAX",
    "MIN", "TRUE", "FALSE", "LENGTH", "LETTER", "SUBTEXT", "JOIN", "JOIN3", "REGEX",
    "LIST_ITEM", "CONTAINS", "INDEX_OF_ITEM", "NUMBER_OF_ITEMS", "IF_THEN_ELSE",
    "FLATTEN", "MULTI_FINGER_X", "MULTI_FINGER_Y", "MULTI_FINGER_TOUCHED"}
KNOWN_SENSORS = {"TIMER", "COLLIDES_WITH_FINGER", "OBJECT_X", "OBJECT_Y",
                 "OBJECT_LOOK_NUMBER", "OBJECT_SIZE", "OBJECT_TRANSPARENCY",
                 "STAGE_WIDTH", "STAGE_HEIGHT", "FINGER_TOUCHED", "FINGER_X", "FINGER_Y"}


def _fmt_num(x):
    if isinstance(x, bool):
        x = int(x)
    if isinstance(x, int):
        return str(x)
    if float(x).is_integer() and abs(x) < 1e15:
        return str(int(x))
    s = repr(round(float(x), 6))
    return s


class F:
    """No de formula. Operadores Python geram arvores Catrobat."""
    __slots__ = ("type", "value", "left", "right", "extra")

    def __init__(self, type_, value=None, left=None, right=None, extra=None):
        self.type = type_
        self.value = value
        self.left = left
        self.right = right
        self.extra = extra or []

    # --- construcao ---
    @staticmethod
    def wrap(x):
        if isinstance(x, F):
            return x
        if isinstance(x, (int, float)):
            if x < 0:
                return F("OPERATOR", "MINUS", None, F("NUMBER", _fmt_num(-x)))
            return F("NUMBER", _fmt_num(x))
        if isinstance(x, str):
            return F("STRING", x)
        if isinstance(x, UserVariable):
            return F("USER_VARIABLE", x.name)
        if isinstance(x, UserList):
            return F("USER_LIST", x.name)
        raise TypeError(repr(x))

    def _bin(self, op, other, swap=False):
        a, b = (F.wrap(other), self) if swap else (self, F.wrap(other))
        return F("OPERATOR", op, a, b)

    def __add__(self, o): return self._bin("PLUS", o)
    def __radd__(self, o): return self._bin("PLUS", o, True)
    def __sub__(self, o): return self._bin("MINUS", o)
    def __rsub__(self, o): return self._bin("MINUS", o, True)
    def __mul__(self, o): return self._bin("MULT", o)
    def __rmul__(self, o): return self._bin("MULT", o, True)
    def __truediv__(self, o): return self._bin("DIVIDE", o)
    def __rtruediv__(self, o): return self._bin("DIVIDE", o, True)
    def __lt__(self, o): return self._bin("SMALLER_THAN", o)
    def __le__(self, o): return self._bin("SMALLER_OR_EQUAL", o)
    def __gt__(self, o): return self._bin("GREATER_THAN", o)
    def __ge__(self, o): return self._bin("GREATER_OR_EQUAL", o)
    def __and__(self, o): return self._bin("LOGICAL_AND", o)
    def __rand__(self, o): return self._bin("LOGICAL_AND", o, True)
    def __or__(self, o): return self._bin("LOGICAL_OR", o)
    def __ror__(self, o): return self._bin("LOGICAL_OR", o, True)
    def __neg__(self): return F("OPERATOR", "MINUS", None, self)
    def __invert__(self): return F("OPERATOR", "LOGICAL_NOT", None, self)
    def eq(self, o): return self._bin("EQUAL", o)
    def ne(self, o): return self._bin("NOT_EQUAL", o)
    __hash__ = object.__hash__

    # --- serializacao ---
    def _bracketed(self):
        """Insere BRACKET onde a precedencia exige, para que o editor do
        Pocket Code mostre (e reinterprete) a formula exatamente como a arvore."""
        def br(child, parent_prio, is_right):
            if child is None:
                return None
            child = child._bracketed()
            if child.type == "OPERATOR" and child.left is not None:
                cp = _PRIORITY[child.value]
                if cp < parent_prio or (is_right and cp == parent_prio):
                    return F("BRACKET", None, None, child)
            return child
        if self.type == "OPERATOR":
            p = _PRIORITY[self.value]
            if self.left is None:  # unario
                r = self.right._bracketed()
                if r.type == "OPERATOR" and r.left is not None:
                    r = F("BRACKET", None, None, r)
                return F("OPERATOR", self.value, None, r)
            return F("OPERATOR", self.value, br(self.left, p, False), br(self.right, p, True))
        return F(self.type, self.value,
                 self.left._bracketed() if self.left else None,
                 self.right._bracketed() if self.right else None,
                 [e._bracketed() for e in self.extra])

    def children_nodes(self):
        out = []
        if self.extra:
            out.append(Node("additionalChildren", children=[
                Node("org.catrobat.catroid.formulaeditor.FormulaElement", children=e.children_nodes())
                for e in self.extra]))
        else:
            out.append(Node("additionalChildren"))
        if self.left is not None:
            out.append(Node("leftChild", children=self.left.children_nodes()))
        if self.right is not None:
            out.append(Node("rightChild", children=self.right.children_nodes()))
        out.append(Node("type", text=self.type))
        if self.value is not None:
            out.append(Node("value", text=self.value))
        return out

    def to_formula_node(self, category):
        return Node("formula", [("category", category)], self._bracketed().children_nodes())

    def walk(self):
        yield self
        for c in (self.left, self.right):
            if c is not None:
                yield from c.walk()
        for e in self.extra:
            yield from e.walk()


def num(x): return F.wrap(x)
def text(s): return F("STRING", s)
def var(v): return F("USER_VARIABLE", v.name if isinstance(v, UserVariable) else v)
def lst(l): return F("USER_LIST", l.name if isinstance(l, UserList) else l)
def sensor(name):
    assert name in KNOWN_SENSORS, name
    return F("SENSOR", name)


def fn(name, *args):
    assert name in KNOWN_FUNCTIONS, name
    a = [F.wrap(x) for x in args]
    left = a[0] if len(a) > 0 else None
    right = a[1] if len(a) > 1 else None
    return F("FUNCTION", name, left, right, a[2:])


def item(index, l): return fn("LIST_ITEM", index, lst(l))
def count(l): return fn("NUMBER_OF_ITEMS", lst(l))
def iff(c, a, b): return fn("IF_THEN_ELSE", c, a, b)
def fmin(a, b): return fn("MIN", a, b)
def fmax(a, b): return fn("MAX", a, b)
def floor(a): return fn("FLOOR", a)
def fabs(a): return fn("ABS", a)
def fround(a): return fn("ROUND", a)
def fmod(a, b): return fn("MOD", a, b)
def join(*parts):
    parts = [F.wrap(p) for p in parts]
    acc = parts[-1]
    for p in reversed(parts[:-1]):
        acc = fn("JOIN", p, acc)
    return acc
def subtext(a, b, s): return fn("SUBTEXT", a, b, s)
TRUE = F("FUNCTION", "TRUE")
FALSE = F("FUNCTION", "FALSE")


# --------------------------------------------------------------------------
# Bricks e scripts
# --------------------------------------------------------------------------

# Categoria (BrickField) das formulas de cada brick, conferidas no codigo-fonte.
BRICK_FORMULAS = {
    "SetVariableBrick": ["VARIABLE"],
    "ChangeVariableBrick": ["VARIABLE_CHANGE"],
    "IfLogicBeginBrick": ["IF_CONDITION"],
    "IfThenLogicBeginBrick": ["IF_CONDITION"],
    "RepeatBrick": ["TIMES_TO_REPEAT"],
    "RepeatUntilBrick": ["REPEAT_UNTIL_CONDITION"],
    "WaitBrick": ["TIME_TO_WAIT_IN_SECONDS"],
    "WaitUntilBrick": ["IF_CONDITION"],
    "PlaceAtBrick": ["X_POSITION", "Y_POSITION"],
    "SetXBrick": ["X_POSITION"],
    "SetYBrick": ["Y_POSITION"],
    "SetSizeToBrick": ["SIZE"],
    "SetTransparencyBrick": ["TRANSPARENCY"],
    "SetLookByIndexBrick": ["LOOK_INDEX"],
    "AddItemToUserListBrick": ["LIST_ADD_ITEM"],
    "ReplaceItemInUserListBrick": ["REPLACE_ITEM_IN_USERLIST_INDEX", "REPLACE_ITEM_IN_USERLIST_VALUE"],
    "ShowTextColorSizeAlignmentBrick": ["X_POSITION", "Y_POSITION", "SIZE", "COLOR"],
    "SetVolumeToBrick": ["VOLUME"],
    "NoteBrick": ["NOTE"],
}

LOOP_BRICKS = {"ForeverBrick", "RepeatBrick", "RepeatUntilBrick"}
# Subclasses de FormulaBrick sem formulas: o XStream escreve <formulaList/> vazio
# (observado em ClearUserListBrick nos projetos de teste do Catroid).
FORMULA_BRICKS_WITHOUT_FORMULA = {"ClearUserListBrick"}


class Brick:
    def __init__(self, type_, formulas=None, **kw):
        self.type = type_
        self.formulas = formulas or {}
        self.kw = kw
        self.id = None
        expected = BRICK_FORMULAS.get(type_, [])
        for k in self.formulas:
            assert k in expected, (type_, k)
        for k in expected:
            assert k in self.formulas, ("faltando formula", type_, k)

    def to_node(self, ctx):
        bid = stable_uuid("brick", ctx.next_id())
        t = self.type
        ch = [Node("brickId", text=bid), Node("commentedOut", text="false")]
        if self.formulas or t in FORMULA_BRICKS_WITHOUT_FORMULA:
            ch.append(Node("formulaList", children=[
                F.wrap(f).to_formula_node(cat) for cat, f in self.formulas.items()]))
        kw = self.kw
        t = self.type
        if "var" in kw:
            ch.append(ObjRef("userVariable", kw["var"]))
        if "list" in kw:
            ch.append(ObjRef("userList", kw["list"]))
        if "look" in kw:
            ch.append(ObjRef("look", kw["look"]))
        if "sound" in kw:
            ch.append(ObjRef("sound", kw["sound"]))
        if "message" in kw:
            ch.append(Node("broadcastMessage", text=kw["message"]))
        if "spinner" in kw:
            ch.append(Node("spinnerSelection", text=str(kw["spinner"])))
        if "alignment" in kw:
            ch.append(Node("alignmentSelection", text=str(kw["alignment"])))
        if t == "IfLogicBeginBrick":
            ch.append(Node("elseBranchBricks", children=[b.to_node(ctx) for b in kw["else_"]]))
            ch.append(Node("ifBranchBricks", children=[b.to_node(ctx) for b in kw["then"]]))
        elif t == "IfThenLogicBeginBrick":
            ch.append(Node("ifBranchBricks", children=[b.to_node(ctx) for b in kw["then"]]))
        elif t in LOOP_BRICKS:
            ch.append(Node("loopBricks", children=[b.to_node(ctx) for b in kw["body"]]))
        return Node("brick", [("type", t)], ch)

    def walk(self):
        yield self
        for key in ("then", "else_", "body"):
            for b in self.kw.get(key, []):
                yield from b.walk()


class Script:
    def __init__(self, type_, bricks, message=None):
        self.type = type_
        self.bricks = bricks
        self.message = message

    def to_node(self, ctx):
        ch = [Node("brickList", children=[b.to_node(ctx) for b in self.bricks]),
              Node("commentedOut", text="false"),
              Node("scriptId", text=stable_uuid("script", ctx.next_id()))]
        if self.type == "BroadcastScript":
            ch.append(Node("receivedMessage", text=self.message))
        return Node("script", [("type", self.type), ("posX", "0.0"), ("posY", "0.0")], ch)

    def walk(self):
        for b in self.bricks:
            yield from b.walk()


# ---- atalhos de bricks ----
def set_var(v, f): return Brick("SetVariableBrick", {"VARIABLE": f}, var=v)
def change_var(v, f): return Brick("ChangeVariableBrick", {"VARIABLE_CHANGE": f}, var=v)
def if_(c, then, else_=None):
    if else_:
        return Brick("IfLogicBeginBrick", {"IF_CONDITION": c}, then=list(then), else_=list(else_))
    return Brick("IfThenLogicBeginBrick", {"IF_CONDITION": c}, then=list(then))
def forever(body): return Brick("ForeverBrick", body=list(body))
def repeat(n, body): return Brick("RepeatBrick", {"TIMES_TO_REPEAT": n}, body=list(body))
def repeat_until(c, body): return Brick("RepeatUntilBrick", {"REPEAT_UNTIL_CONDITION": c}, body=list(body))
def wait(s): return Brick("WaitBrick", {"TIME_TO_WAIT_IN_SECONDS": s})
def wait_until(c): return Brick("WaitUntilBrick", {"IF_CONDITION": c})
def place_at(x, y): return Brick("PlaceAtBrick", {"X_POSITION": x, "Y_POSITION": y})
def set_x(x): return Brick("SetXBrick", {"X_POSITION": x})
def set_y(y): return Brick("SetYBrick", {"Y_POSITION": y})
def set_size(s): return Brick("SetSizeToBrick", {"SIZE": s})
def set_transparency(t): return Brick("SetTransparencyBrick", {"TRANSPARENCY": t})
def set_look(look): return Brick("SetLookBrick", look=look)
def set_look_index(i): return Brick("SetLookByIndexBrick", {"LOOK_INDEX": i})
def show(): return Brick("ShowBrick")
def hide(): return Brick("HideBrick")
def come_to_front(): return Brick("ComeToFrontBrick")
def broadcast(msg): return Brick("BroadcastBrick", message=msg)
def broadcast_wait(msg): return Brick("BroadcastWaitBrick", message=msg)
def clone_myself(): return Brick("CloneBrick")
def delete_this_clone(): return Brick("DeleteThisCloneBrick")
def add_item(l, f): return Brick("AddItemToUserListBrick", {"LIST_ADD_ITEM": f}, list=l)
def replace_item(l, idx, f):
    return Brick("ReplaceItemInUserListBrick", {"REPLACE_ITEM_IN_USERLIST_INDEX": idx,
                                                "REPLACE_ITEM_IN_USERLIST_VALUE": f}, list=l)
def clear_list(l): return Brick("ClearUserListBrick", list=l)
def play_sound(snd): return Brick("PlaySoundBrick", sound=snd)
def stop_all_sounds(): return Brick("StopAllSoundsBrick")
def set_volume(v): return Brick("SetVolumeToBrick", {"VOLUME": v})
def reset_timer(): return Brick("ResetTimerBrick")
def stop_script(which): return Brick("StopScriptBrick", spinner=which)  # 0=este,1=todos,2=outros
def show_text(v, x, y, size=100, color="#FFFFFF", align=1):
    return Brick("ShowTextColorSizeAlignmentBrick",
                 {"X_POSITION": x, "Y_POSITION": y, "SIZE": size, "COLOR": text(color)},
                 var=v, alignment=align)
def hide_text(v): return Brick("HideTextBrick", var=v)
def write_var_device(v): return Brick("WriteVariableOnDeviceBrick", var=v)
def read_var_device(v): return Brick("ReadVariableFromDeviceBrick", var=v)
def note(s): return Brick("NoteBrick", {"NOTE": text(s)})


def when_start(*bricks): return Script("StartScript", list(bricks))
def when_received(msg, *bricks): return Script("BroadcastScript", list(bricks), message=msg)
def when_tapped(*bricks): return Script("WhenScript", list(bricks))
def when_cloned(*bricks): return Script("WhenClonedScript", list(bricks))


# --------------------------------------------------------------------------
# Projeto
# --------------------------------------------------------------------------

class Sprite:
    def __init__(self, name):
        self.name = name
        self.looks = []
        self.sounds = []
        self.scripts = []
        self.vars = {}
        self.lists = {}

    def look(self, name, file_name):
        lk = Look(name, file_name)
        self.looks.append(lk)
        return lk

    def sound(self, name, file_name):
        s = Sound(name, file_name)
        self.sounds.append(s)
        return s

    def local_var(self, name):
        if name not in self.vars:
            self.vars[name] = UserVariable(name, scope=self.name)
        return self.vars[name]

    def local_list(self, name):
        if name not in self.lists:
            self.lists[name] = UserList(name, scope=self.name)
        return self.lists[name]

    def add(self, *scripts):
        self.scripts.extend(scripts)


class _Ctx:
    def __init__(self, seed):
        self.n = 0
        self.seed = seed

    def next_id(self):
        self.n += 1
        return "%s-%d" % (self.seed, self.n)


class Project:
    def __init__(self, name, scene_name="Scene", width=1280, height=720, landscape=True,
                 description="", notes=""):
        self.name = name
        self.scene_name = scene_name
        self.width = width
        self.height = height
        self.landscape = landscape
        self.description = description
        self.notes = notes
        self.sprites = []
        self.gvars = {}
        self.glists = {}
        self.files = {}  # caminho no zip -> bytes

    def sprite(self, name):
        s = Sprite(name)
        self.sprites.append(s)
        return s

    def global_var(self, name):
        if name not in self.gvars:
            self.gvars[name] = UserVariable(name)
        return self.gvars[name]

    def global_list(self, name):
        if name not in self.glists:
            self.glists[name] = UserList(name)
        return self.glists[name]

    def add_image(self, file_name, data):
        self.files["%s/images/%s" % (self.scene_name, file_name)] = data

    def add_sound_file(self, file_name, data):
        self.files["%s/sounds/%s" % (self.scene_name, file_name)] = data

    # -------------------------------------------------------------------
    def header_node(self):
        h = [
            ("applicationBuildName", ""), ("applicationBuildNumber", "0"),
            ("applicationBuildType", "release"), ("applicationName", "Pocket Code"),
            ("applicationVersion", "1.1.2"), ("catrobatLanguageVersion", "1.12"),
            ("dateTimeUpload", ""), ("description", self.description),
            ("deviceName", "fnf_to_catrobat"), ("isCastProject", "false"),
            ("landscapeMode", "true" if self.landscape else "false"),
            ("listeningLanguageTag", ""), ("mediaLicense", ""), ("notesAndCredits", self.notes),
            ("platform", "Android"), ("platformVersion", "29"), ("programLicense", ""),
            ("programName", self.name), ("remixOf", ""), ("scenesEnabled", "true"),
            ("screenHeight", str(self.height)), ("screenMode", "STRETCH"),
            ("screenWidth", str(self.width)), ("tags", ""), ("url", ""), ("userHandle", ""),
        ]
        return Node("header", children=[Node(k, text=v) for k, v in h])

    def to_xml(self):
        ctx = _Ctx(self.name)
        objects = []
        for sp in self.sprites:
            objects.append(Node("object", [("type", "Sprite"), ("name", sp.name)], [
                Node("lookList", children=[ObjRef("look", l) for l in sp.looks]),
                Node("soundList", children=[ObjRef("sound", s) for s in sp.sounds]),
                Node("scriptList", children=[s.to_node(ctx) for s in sp.scripts]),
                Node("nfcTagList"),
                Node("userVariables", children=[ObjRef("userVariable", v) for v in sp.vars.values()]),
                Node("userLists", children=[ObjRef("userList", l) for l in sp.lists.values()]),
                Node("userDefinedBrickList"),
            ]))
        root = Node("program", children=[
            self.header_node(),
            Node("settings"),
            Node("scenes", children=[Node("scene", children=[
                Node("name", text=self.scene_name),
                Node("objectList", children=objects)])]),
            Node("programVariableList", children=[ObjRef("userVariable", v) for v in self.gvars.values()]),
            Node("programListOfLists", children=[ObjRef("userList", l) for l in self.glists.values()]),
            Node("programMultiplayerVariableList"),
        ])
        return XStreamWriter().write_document(root)

    def check(self):
        """Validacao semantica: variaveis/listas usadas existem no escopo certo,
        looks/sons referenciados pertencem ao objeto, broadcasts tem receptor."""
        problems = []
        gnames = set(self.gvars)
        glnames = set(self.glists)
        sent, received = set(), set()
        for sp in self.sprites:
            lv, ll = set(sp.vars), set(sp.lists)
            dup = (lv & gnames) | (ll & glnames)
            if dup:
                problems.append("%s: nome local repete global %s" % (sp.name, dup))
            for sc in sp.scripts:
                if sc.type == "BroadcastScript":
                    received.add(sc.message)
                for b in sc.walk():
                    v = b.kw.get("var")
                    if v is not None and v.name not in lv | gnames:
                        problems.append("%s: variavel %s fora de escopo" % (sp.name, v.name))
                    if v is not None and v.scope not in ("global", sp.name):
                        problems.append("%s: variavel local de outro objeto %s" % (sp.name, v.name))
                    l = b.kw.get("list")
                    if l is not None and (l.name not in ll | glnames or l.scope not in ("global", sp.name)):
                        problems.append("%s: lista %s fora de escopo" % (sp.name, l.name))
                    lk = b.kw.get("look")
                    if lk is not None and lk not in sp.looks:
                        problems.append("%s: look de outro objeto" % sp.name)
                    sd = b.kw.get("sound")
                    if sd is not None and sd not in sp.sounds:
                        problems.append("%s: som de outro objeto" % sp.name)
                    if "message" in b.kw:
                        sent.add(b.kw["message"])
                    for f in b.formulas.values():
                        for e in F.wrap(f).walk():
                            if e.type == "USER_VARIABLE" and e.value not in lv | gnames:
                                problems.append("%s: formula usa variavel inexistente %s" % (sp.name, e.value))
                            if e.type == "USER_LIST" and e.value not in ll | glnames:
                                problems.append("%s: formula usa lista inexistente %s" % (sp.name, e.value))
                            if e.type == "FUNCTION":
                                assert e.value in KNOWN_FUNCTIONS
            for lk in sp.looks:
                if "%s/images/%s" % (self.scene_name, lk.file_name) not in self.files:
                    problems.append("%s: imagem ausente %s" % (sp.name, lk.file_name))
            for sd in sp.sounds:
                if "%s/sounds/%s" % (self.scene_name, sd.file_name) not in self.files:
                    problems.append("%s: som ausente %s" % (sp.name, sd.file_name))
        for m in sent - received:
            problems.append("broadcast sem receptor: %s" % m)
        return problems

    def save(self, path, screenshot_png=None):
        xml = self.to_xml()
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("code.xml", xml.encode("utf-8"))
            z.writestr(".nomedia", b"")
            z.writestr("%s/.nomedia" % self.scene_name, b"")
            z.writestr("%s/images/.nomedia" % self.scene_name, b"")
            z.writestr("%s/sounds/.nomedia" % self.scene_name, b"")
            for name in sorted(self.files):
                data = self.files[name]
                if name.endswith((".mp3", ".png")):
                    z.writestr(zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0)), data, zipfile.ZIP_STORED)
                else:
                    z.writestr(name, data)
            if screenshot_png:
                z.writestr("automatic_screenshot.png", screenshot_png)
                z.writestr("%s/automatic_screenshot.png" % self.scene_name, screenshot_png)
        return xml


# --------------------------------------------------------------------------
# Validacao do XML gerado (resolve cada reference="..." como o XStream)
# --------------------------------------------------------------------------

def validate_xml(xml_text):
    import xml.etree.ElementTree as ET
    root = ET.fromstring(xml_text.encode("utf-8"))
    paths = {}
    by_path = {}

    def walk(el, path):
        paths[el] = path
        by_path["/".join(path)] = el
        counters = {}
        for c in el:
            n = counters.get(c.tag, 0) + 1
            counters[c.tag] = n
            walk(c, path + [c.tag if n == 1 else "%s[%d]" % (c.tag, n)])
    walk(root, [root.tag])
    errors = []
    nrefs = 0
    for el, path in paths.items():
        ref = el.get("reference")
        if ref is None:
            continue
        nrefs += 1
        cur = list(path)
        for part in ref.split("/"):
            if part == "..":
                cur.pop()
            elif part != ".":
                cur.append(part)
        target = by_path.get("/".join(cur))
        if target is None:
            errors.append("referencia quebrada %s -> %s" % ("/".join(path), ref))
        elif target.get("reference") is not None:
            errors.append("referencia para elemento errado %s -> %s" % ("/".join(path), ref))
    return nrefs, errors
