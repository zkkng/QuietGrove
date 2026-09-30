// WZ requirements and server-owned counters decide completion and rewards.
var Medals = Java.type("server.content.Medals");
var handled = false;
function interact(mode) {
    if (handled) { qm.dispose(); return; }
    handled = true;
    if (mode == 1) qm.sendOk(Medals.interact(qm.getPlayer(), qm.getQuest()));
    qm.dispose();
}
function start(mode, type, selection) { interact(mode); }
function end(mode, type, selection) { interact(mode); }
