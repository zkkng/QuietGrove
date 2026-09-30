/* Original Cafe PQ remains available through the shared cafe menu. */
function start() {
    var target = (cm.isPcCafeEnabled() ? "pc_cafe_billy" : "pc_cafe_pq_billy");
    cm.dispose();
    cm.openNpc(1052015, target);
}
function action(mode, type, selection) { cm.dispose(); }
