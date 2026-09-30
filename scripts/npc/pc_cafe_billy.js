var step = 0;
function start() {
    cm.sendSimple(cm.welcomePcCafe() + "\r\n\r\n#L0#Exchange collected Mice#l\r\n#L1#View original Party Quest prize lists#l");
}
function action(mode, type, selection) {
    if (mode != 1) { cm.dispose(); return; }
    if (step == 0) {
        if (selection == 1) { cm.dispose(); cm.openNpc(1052015, "pc_cafe_pq_billy"); return; }
        if (selection != 0) { cm.dispose(); return; }
        step = 1; cm.sendGetNumber("How many Mice would you like to exchange? Weekly and balance caps apply; any rejected exchange keeps your mice.", 1, 1, 1000);
    } else if (step == 1) {
        step = 2;
        if (!isFinite(selection) || Math.floor(selection) != selection) { cm.dispose(); return; }
        cm.sendOk(cm.exchangePcCafeMice(selection)); cm.dispose();
    } else cm.dispose();
}
