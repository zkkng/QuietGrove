/* Direct entry preserves tour access with the stock expired client quest metadata. */
function start() { var npc = cm.getNpc(); cm.dispose(); cm.openNpc(npc, "traveling_around_maple"); }
function action(mode, type, selection) { cm.dispose(); }
