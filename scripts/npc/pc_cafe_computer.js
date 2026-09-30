var step = 0;
function start() {
    if (cm.getMapId() != 193000000) {
        step = 3;
        cm.sendYesNo(cm.getPcCafeStatus() + "\r\n\r\nReturn to the cafe? Your progress is kept.");
    } else {
        cm.sendSimple("Welcome to Premium Road!\r\n#L0#Choose a solo hunting ground#l\r\n#L1#Original Cafe Party Quest#l\r\n#L2#My cafe progress and rules#l");
    }
}
function action(mode, type, selection) {
    if (mode != 1) { cm.dispose(); return; }
    if (step == 3) { cm.warp(193000000); cm.dispose(); return; }
    if (step == 0) {
        if (selection == 1) { cm.dispose(); cm.openNpc(1052013, "pc_cafe_pq_computer"); return; }
        if (selection == 2) { cm.sendOk(cm.getPcCafeStatus()); cm.dispose(); return; }
        if (selection != 0) { cm.dispose(); return; }
        var roads = cm.getPcCafeRoads();
        var text = "Choose a map. Levels are recommendations. Use a Return Scroll or the Computer to return to the cafe.\r\n";
        for (var i = 0; i < roads.size(); i++) text += "#L" + i + "##m" + roads.get(i).map() + "# (Lv. " + roads.get(i).recommendedLevel() + "+)#l\r\n";
        step = 1; cm.sendSimple(text);
    } else if (step == 1) {
        step = 2;
        var result = cm.enterPcCafeRoad(selection);
        if (result.length > 0) cm.sendOk(result);
        cm.dispose();
    } else cm.dispose();
}
