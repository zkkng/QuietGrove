var step = 0, chosen = -1, currency = "";
function start() {
    cm.sendSimple("Choose exactly what you receive. Prices are per bundle.\r\n#L0#Cafe coin rewards#l\r\n#L1#Meso refreshments#l\r\n#L2#Original eraser prize machine#l");
}
function action(mode, type, selection) {
    if (mode != 1) { cm.dispose(); return; }
    if (step == 0) {
        if (selection == 2) { cm.dispose(); cm.openNpc(1052014, "pc_cafe_pq_vending"); return; }
        if (selection != 0 && selection != 1) { cm.dispose(); return; }
        currency = selection == 0 ? "COINS" : "MESOS";
        var rewards = cm.getPcCafeRewards(), text = cm.getPcCafeStatus() + "\r\n\r\n";
        for (var i = 0; i < rewards.size(); i++) {
            var r = rewards.get(i);
            if (r.currency() == currency) text += "#L" + i + "##i" + r.item() + "# #t" + r.item() + "# x" + r.quantity() + " - " + r.price() + " " + currency + (r.weeklyLimit() > 0 ? " (" + r.weeklyLimit() + " bundles/week)" : "") + "#l\r\n";
        }
        step = 1; cm.sendSimple(text);
    } else if (step == 1) {
        var rewards = cm.getPcCafeRewards();
        if (selection < 0 || selection >= rewards.size() || Math.floor(selection) != selection) { cm.dispose(); return; }
        var r = rewards.get(selection);
        if (r.currency() != currency) { cm.dispose(); return; }
        chosen = selection; step = 2;
        cm.sendYesNo("Buy #t" + r.item() + "# x" + r.quantity() + " for " + r.price() + " " + currency + "? Please leave one free inventory slot.");
    } else if (step == 2) {
        step = 3; cm.sendOk(cm.buyPcCafeReward(chosen)); cm.dispose();
    } else cm.dispose();
}
