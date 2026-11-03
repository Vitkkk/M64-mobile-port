#!/usr/bin/env python3
"""
validate_catrobat.py - Valida um .catrobat gerado por fnf_to_catrobat.py.

1. Estrutura: ZIP, code.xml bem formado, referencias XStream, assets existentes,
   tipos de brick/script conhecidos (conjunto conferido com projetos reais do Catroid).
2. Simulacao headless (catrobat_sim.py) de cenarios de jogo:
   - menu -> JOGAR (toque no botao) -> carregamento -> partida;
   - botplay (todas as notas devem ser acertadas);
   - toques simulados de um jogador (com erro de tempo aleatorio);
   - sem toques (deve terminar em GAME OVER e voltar ao menu pelo botao).

Uso: python3 converter/validate_catrobat.py output/FNF_Port.catrobat [--seconds 40] [--full]
"""

import argparse
import io
import json
import os
import random
import sys
import time
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import catrobat_core as cc  # noqa: E402
import catrobat_sim as cs  # noqa: E402

# Tipos encontrados em projetos .catrobat reais ou em classes serializaveis do Catroid
KNOWN_BRICKS = {
    "AddItemToUserListBrick", "BroadcastBrick", "ChangeVariableBrick", "ClearUserListBrick", "CloneBrick",
    "DeleteThisCloneBrick", "ForeverBrick", "HideBrick", "HideTextBrick", "IfLogicBeginBrick",
    "IfThenLogicBeginBrick", "NoteBrick", "PlaceAtBrick", "PlaySoundBrick", "ReadVariableFromDeviceBrick",
    "RepeatBrick", "RepeatUntilBrick", "ReplaceItemInUserListBrick", "ResetTimerBrick", "SetLookBrick",
    "SetLookByIndexBrick", "SetSizeToBrick", "SetTransparencyBrick", "SetVariableBrick", "SetXBrick",
    "SetYBrick", "ShowBrick", "ShowTextColorSizeAlignmentBrick", "StopAllSoundsBrick", "WaitBrick",
    "WaitUntilBrick", "WriteVariableOnDeviceBrick", "SetVolumeToBrick", "StopScriptBrick", "ForeverBrick", "BroadcastWaitBrick"}
KNOWN_SCRIPTS = {"StartScript", "BroadcastScript", "WhenScript", "WhenClonedScript"}


# Sequencias de elementos-filho observadas em projetos reais (linguagem >= 1.1) de
# Catroid/catroid/src/androidTest/assets, e (codigo_fonte) derivadas das classes
# serializaveis para tipos sem exemplo recente.
REFERENCE_SIGNATURES = {
 "codigo_fonte": {
  "brick:HideTextBrick": "brickId,commentedOut,userVariable",
  "brick:PlaySoundBrick": "brickId,commentedOut,sound",
  "brick:ReadVariableFromDeviceBrick": "brickId,commentedOut,userVariable",
  "brick:SetLookByIndexBrick": "brickId,commentedOut,formulaList",
  "brick:ShowBrick": "brickId,commentedOut",
  "brick:ShowTextColorSizeAlignmentBrick": "brickId,commentedOut,formulaList,userVariable,alignmentSelection",
  "brick:StopAllSoundsBrick": "brickId,commentedOut",
  "brick:WriteVariableOnDeviceBrick": "brickId,commentedOut,userVariable",
  "script:WhenScript": "brickList,commentedOut,scriptId",
  "sound": ""
 },
 "observado": {
  "brick:AddItemToUserListBrick": [
   "brickId,commentedOut,formulaList,userList"
  ],
  "brick:AssertEqualsBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:AssertUserListsBrick": [
   "brickId,commentedOut,formulaList,userDataList"
  ],
  "brick:BroadcastBrick": [
   "brickId,commentedOut,broadcastMessage"
  ],
  "brick:BroadcastWaitBrick": [
   "brickId,commentedOut,broadcastMessage"
  ],
  "brick:ChangeSizeByNBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:ChangeVariableBrick": [
   "brickId,commentedOut,formulaList,userVariable"
  ],
  "brick:ChangeXByNBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:ClearUserListBrick": [
   "brickId,commentedOut,formulaList,userList"
  ],
  "brick:CloneBrick": [
   "brickId,commentedOut"
  ],
  "brick:DeleteItemOfUserListBrick": [
   "brickId,commentedOut,formulaList,userList"
  ],
  "brick:DeleteThisCloneBrick": [
   "brickId,commentedOut"
  ],
  "brick:ExitStageBrick": [
   "brickId,commentedOut"
  ],
  "brick:FinishStageBrick": [
   "brickId,commentedOut"
  ],
  "brick:ForItemInUserListBrick": [
   "brickId,commentedOut,formulaList,userDataList,loopBricks"
  ],
  "brick:ForVariableFromToBrick": [
   "brickId,commentedOut,formulaList,userVariable,loopBricks"
  ],
  "brick:ForeverBrick": [
   "brickId,commentedOut,loopBricks"
  ],
  "brick:GlideToBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:HideBrick": [
   "brickId,commentedOut"
  ],
  "brick:IfLogicBeginBrick": [
   "brickId,commentedOut,formulaList,elseBranchBricks,ifBranchBricks"
  ],
  "brick:IfOnEdgeBounceBrick": [
   "brickId,commentedOut"
  ],
  "brick:IfThenLogicBeginBrick": [
   "brickId,commentedOut,formulaList,ifBranchBricks"
  ],
  "brick:InsertItemIntoUserListBrick": [
   "brickId,commentedOut,formulaList,userList"
  ],
  "brick:MoveNStepsBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:NoteBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:OpenUrlBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:ParameterizedBrick": [
   "brickId,commentedOut,userLists,endBrick,loopBricks"
  ],
  "brick:PlaceAtBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:PointInDirectionBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:ReadVariableFromFileBrick": [
   "brickId,commentedOut,formulaList,userVariable,spinnerSelectionID"
  ],
  "brick:RepeatBrick": [
   "brickId,commentedOut,formulaList,loopBricks"
  ],
  "brick:RepeatUntilBrick": [
   "brickId,commentedOut,formulaList,loopBricks"
  ],
  "brick:ReplaceItemInUserListBrick": [
   "brickId,commentedOut,formulaList,userList"
  ],
  "brick:ResetTimerBrick": [
   "brickId,commentedOut"
  ],
  "brick:RunningStitchBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:SceneStartBrick": [
   "brickId,commentedOut,sceneToStart"
  ],
  "brick:SetBackgroundBrick": [
   "brickId,commentedOut,look"
  ],
  "brick:SetBrightnessBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:SetGravityBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:SetLookBrick": [
   "brickId,commentedOut,look"
  ],
  "brick:SetMassBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:SetPhysicsObjectTypeBrick": [
   "brickId,commentedOut,type"
  ],
  "brick:SetRotationStyleBrick": [
   "brickId,commentedOut,selection"
  ],
  "brick:SetSizeToBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:SetTransparencyBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:SetVariableBrick": [
   "brickId,commentedOut,formulaList,userVariable"
  ],
  "brick:SetVelocityBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:SetXBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:SetYBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:ShowTextBrick": [
   "brickId,commentedOut,formulaList,userVariable"
  ],
  "brick:StopRunningStitchBrick": [
   "brickId,commentedOut"
  ],
  "brick:StopScriptBrick": [
   "brickId,commentedOut,spinnerSelection"
  ],
  "brick:TapAtBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:TurnLeftBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:TurnRightBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:UserDefinedBrick": [
   "brickId,commentedOut,formulaList,isCallingBrick,userDefinedBrickDataList,userDefinedBrickID"
  ],
  "brick:WaitBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:WaitTillIdleBrick": [
   "brickId,commentedOut"
  ],
  "brick:WaitUntilBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "brick:WriteEmbroideryToFileBrick": [
   "brickId,commentedOut,formulaList"
  ],
  "look": [
   "isWebRequest,valid"
  ],
  "script:BroadcastScript": [
   "brickList,commentedOut,scriptId,receivedMessage"
  ],
  "script:StartScript": [
   "brickList,commentedOut,scriptId"
  ],
  "script:UserDefinedScript": [
   "brickList,commentedOut,scriptId,screenRefresh,userDefinedBrickID"
  ],
  "script:WhenBounceOffScript": [
   "brickList,commentedOut,scriptId"
  ],
  "script:WhenClonedScript": [
   "brickList,commentedOut,scriptId"
  ],
  "script:WhenConditionScript": [
   "brickList,commentedOut,scriptId,formulaMap"
  ],
  "script:WhenTouchDownScript": [
   "brickList,commentedOut,scriptId"
  ],
  "userList": [
   "deviceListKey,initialIndex,name"
  ],
  "userVariable": [
   "default",
   "userVariable"
  ]
 }
}


def structure_checks(path):
    out = []
    ok = True
    with zipfile.ZipFile(path) as z:
        bad = z.testzip()
        names = set(z.namelist())
        xml = z.read("code.xml").decode("utf-8")
    out.append("ZIP: %s (%d entradas)" % ("OK" if bad is None else "corrompido: " + bad, len(names)))
    ok &= bad is None
    nref, errs = cc.validate_xml(xml)
    out.append("XML bem formado; %d referencias XStream resolvidas; erros: %s" % (nref, errs or "nenhum"))
    ok &= not errs
    proj = cs.load_project(path)
    missing = []
    types = set()
    stypes = set()
    for sp in proj["sprites"]:
        for typ, msg, bricks in sp.scripts:
            stypes.add(typ)
            stack = list(bricks)
            while stack:
                b = stack.pop()
                types.add(b.type)
                stack += b.then + b.else_ + b.body
    unknown = (types - KNOWN_BRICKS) | (stypes - KNOWN_SCRIPTS)
    out.append("Tipos de brick: %d, de script: %d, desconhecidos: %s" % (len(types), len(stypes), unknown or "nenhum"))
    ok &= not unknown
    out.append("Imagens referenciadas carregadas: %d (todas existem no ZIP)" % len(proj["images"]))
    import xml.etree.ElementTree as ET
    mine = {}
    for el in ET.fromstring(xml.encode("utf-8")).iter():
        if el.get("reference") is not None:
            continue
        if el.tag in ("brick", "script") and el.get("type"):
            key = "%s:%s" % (el.tag, el.get("type"))
        elif el.tag in ("look", "sound", "userVariable", "userList"):
            key = el.tag
        else:
            continue
        mine.setdefault(key, set()).add(",".join(c.tag for c in el))
    obs, srcd = REFERENCE_SIGNATURES["observado"], REFERENCE_SIGNATURES["codigo_fonte"]
    conf, viasrc, diverg = 0, 0, []
    for key, sigs_ in sorted(mine.items()):
        for sg in sigs_:
            if sg in obs.get(key, []):
                conf += 1
            elif srcd.get(key) == sg:
                viasrc += 1
            else:
                diverg.append("%s: %s (real: %s)" % (key, sg, obs.get(key, srcd.get(key))))
    out.append("Estrutura de elementos: %d assinaturas identicas a projetos reais, %d conferidas pelo "
               "codigo-fonte, divergencias: %s" % (conf, viasrc, diverg or "nenhuma"))
    ok &= not diverg
    return ok, out, proj


def run_scenario(proj, mode, seconds, seed=1, apf=12, chart=None, log=None):
    sim = cs.Sim(proj, acts_per_frame=apf)
    rnd = random.Random(seed)
    res = {"modo": mode}
    t0 = time.time()
    sim.run_until(0.3)
    while sim.gvars.get("estado") != 0.0 and sim.time < 120:
        sim.step_frame()
    res["preparo_inicial_s"] = round(sim.time, 2)
    sim.run_until(sim.time + 0.3)
    if sim.gvars.get("estado") != 0.0:
        res["erro"] = "menu nao iniciou (estado=%r)" % sim.gvars.get("estado")
        return res
    if mode == "botplay":
        if sim.tap(420, -310) != "BotaoBotplay":
            res["erro"] = "botao BOTPLAY nao recebeu o toque"
            return res
        sim.run_until(sim.time + 0.2)
    hit = sim.tap(0, -240)
    if hit != "BotaoJogar":
        res["erro"] = "botao JOGAR nao recebeu o toque (%s)" % hit
        return res
    t_tap = sim.time
    while sim.gvars.get("estado") != 1.0 and sim.time < t_tap + 60:
        sim.step_frame()
    res["carregamento_s"] = round(sim.time - t_tap, 2)
    res["listas"] = {k: len(v) for k, v in sim.glists.items() if k in ("notaTempo", "longaTempo", "secaoTempo")}
    t_start = sim.time
    # agenda de toques humanos
    events = []
    if mode == "humano" and chart:
        lane_x = [-640 + 160 + 320 * i for i in range(4)]
        for t, lane, ln in chart["notes"]:
            if lane < 4:
                continue
            err = rnd.gauss(0, 35)
            down = t + err
            up = t + max(ln, 0) + (40 if ln > 0 else 70)
            events.append((down, "down", lane - 4, lane_x[lane - 4]))
            events.append((up, "up", lane - 4, lane_x[lane - 4]))
        events.sort()
    ev_i = [0]
    fid = [0]
    active = {}

    def on_frame(s):
        if mode != "humano":
            return
        pos = (s.time - s.timer_ref) * 1000 + s.gvars["baseTempo"] - s.gvars["offsetMs"]
        while ev_i[0] < len(events) and events[ev_i[0]][0] <= pos:
            et, kind, lane, x = events[ev_i[0]]
            ev_i[0] += 1
            if kind == "down":
                fid[0] += 1
                s.fingers[fid[0]] = (x, 0)
                active.setdefault(lane, []).append(fid[0])
                s.tap(x, 0)
            else:
                if active.get(lane):
                    s.fingers.pop(active[lane].pop(0), None)

    samples = []

    def frame_hook(s):
        on_frame(s)
        if int(s.time * 60) % 30 == 0:
            notas = [i for i in s.find("Notas") if i.is_clone and i.visible]
            for n in notas:
                if not (-700 < n.y < 700):
                    s.stat("nota_visivel_fora_da_tela")
            samples.append((round(s.time - t_start, 2), s.gvars["pontos"], s.gvars["erros"], s.gvars["vida"],
                            len(notas)))

    end = t_start + seconds
    while sim.time < end and sim.gvars.get("estado") == 1.0:
        frame_hook(sim)
        sim.step_frame()
    g = sim.gvars
    res.update({
        "tempo_simulado_s": round(sim.time - t_start, 1),
        "estado_final": g["estado"], "pontos": g["pontos"], "erros": g["erros"], "combo_max": g["comboMax"],
        "notas_jogadas": g["notasJogadas"], "vida": round(cs.to_double(g["vida"]), 3),
        "ponteiro_oponente": g["ponteiroOpNota"], "musica_iniciada_em_s": [x[0] for x in sim.sounds_played],
        "clones_max": sim.max_clones, "estatisticas": sim.stats, "segundos_reais": round(time.time() - t0, 1),
        "texto_hud": g.get("textoPontos"),
    })
    if mode == "sem_toques":
        # espera o game over e volta ao menu pelo botao
        while sim.gvars["estado"] == 1.0 and sim.time < end + 200:
            sim.step_frame()
        res["estado_apos_espera"] = sim.gvars["estado"]
        res["tempo_game_over_s"] = round(sim.time - t_start, 1)
        sim.run_until(sim.time + 0.5)
        res["textos_resultado"] = [sim.gvars["resultado1"], sim.gvars["resultado2"], sim.gvars["resultado3"]]
        # TENTAR DE NOVO: deve recomecar sem decodificar o chart outra vez
        res["toque_repetir"] = sim.tap(-220, -230)
        t_r = sim.time
        while sim.gvars["estado"] != 1.0 and sim.time < t_r + 30:
            sim.step_frame()
        res["recarregamento_s"] = round(sim.time - t_r, 2)
        res["pontos_apos_repetir"] = sim.gvars["pontos"]
        sim.run_until(sim.time + 3)
        res["estado_3s_apos_repetir"] = sim.gvars["estado"]
        while sim.gvars["estado"] == 1.0 and sim.time < t_r + 200:
            sim.step_frame()
        sim.run_until(sim.time + 0.5)
        res["toque_menu"] = sim.tap(220, -230)
        sim.run_until(sim.time + 0.5)
        res["estado_apos_menu"] = sim.gvars["estado"]
        res["clones_apos_menu"] = sum(1 for i in sim.instances if i.is_clone)
    res["amostras"] = samples[::4][:40]
    return res


def chart_from_project_source(mod_chart_json):
    with open(mod_chart_json) as f:
        return json.load(f)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("catrobat")
    ap.add_argument("--seconds", type=float, default=40)
    ap.add_argument("--full", action="store_true", help="simula a musica inteira")
    ap.add_argument("--chart", help="JSON com as notas (gerado pelo conversor) para o modo humano")
    ap.add_argument("--modes", default="botplay,humano,sem_toques")
    ap.add_argument("--json", help="salva os resultados em JSON")
    a = ap.parse_args()
    ok, lines, proj = structure_checks(a.catrobat)
    for l in lines:
        print("[estrutura]", l)
    chart = chart_from_project_source(a.chart) if a.chart else None
    results = []
    for mode in a.modes.split(","):
        secs = 1e9 if a.full else a.seconds
        r = run_scenario(proj, mode, secs, chart=chart)
        results.append(r)
        print("[simulacao]", json.dumps({k: v for k, v in r.items() if k != "amostras"}, ensure_ascii=False))
    if a.json:
        with open(a.json, "w") as f:
            json.dump({"estrutura": lines, "cenarios": results}, f, ensure_ascii=False, indent=1)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
