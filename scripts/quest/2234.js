/* Restored from this client's Quest.wz instructions; no invented reward. */
function start(mode, type, selection) { qm.dispose(); }
function end(mode, type, selection) {
    if (mode != 1 || !qm.isQuestStarted(2234)) { qm.dispose(); return; }
    var family = qm.getPlayer().getFamilyEntry();
    if (family != null && family.getTotalReputation() >= 2000 && family.getReputation() < 500) {
        qm.forceCompleteQuest();
        qm.sendOk("Well done! You have completed this Family lesson.");
    } else {
        qm.sendOk("Reach at least 2,000 total Rep, then use Entitlements to bring your current Rep below 500. You can check both values in the Family window.");
    }
    qm.dispose();
}
