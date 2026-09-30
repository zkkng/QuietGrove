/* Original Cafe PQ remains available through the shared cafe menu. */
function start() {
    var target = (cm.getEventInstance() != null || !cm.isPcCafeEnabled() ? "pc_cafe_pq_computer" : "pc_cafe_computer");
    cm.dispose();
    cm.openNpc(1052013, target);
}
function action(mode, type, selection) { cm.dispose(); }
