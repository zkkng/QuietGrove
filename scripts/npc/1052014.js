/* Original Cafe PQ remains available through the shared cafe menu. */
function start() {
    var target = (cm.isPcCafeEnabled() ? "pc_cafe_vending" : "pc_cafe_pq_vending");
    cm.dispose();
    cm.openNpc(1052014, target);
}
function action(mode, type, selection) { cm.dispose(); }
