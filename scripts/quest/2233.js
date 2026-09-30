/* Restored from this client's Quest.wz instructions; no invented reward. */
function start(mode, type, selection) { qm.dispose(); }
function end(mode, type, selection) {
    if (mode != 1 || !qm.isQuestStarted(2233)) { qm.dispose(); return; }
    var family = qm.getPlayer().getFamilyEntry();
    if (family != null && family.getReputation() >= 1000) {
        qm.forceCompleteQuest();
        qm.sendOk("Well done! You have completed this Family lesson.");
    } else {
        qm.sendOk("Reach 1,000 current Rep by supporting your Juniors. You can check both values in the Family window.");
    }
    qm.dispose();
}
