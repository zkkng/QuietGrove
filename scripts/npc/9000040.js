// Dalair: quest-validated challenges and real ranking records.
var Medals = Java.type("server.content.Medals");
var PqRanks = Java.type("server.content.PqRanks");
var Rankings = Java.type("server.content.RankingMedals");
var status = 0;
function start() { cm.sendSimple(Medals.menu(cm.getPlayer())); }
function action(mode, type, selection) {
    if (mode != 1 || status > 1) { status = 2; cm.dispose(); return; }
    if (status == 1) {
        status = 2;
        cm.sendOk(Medals.retryMissing(cm.getPlayer(), selection - 100000)); cm.dispose(); return;
    }
    if (selection == 4) { status = 1; cm.sendSimple(Medals.retryMenu(cm.getPlayer())); return; }
    status = 2;
    var message;
    if (selection == 1) message = PqRanks.summary(cm.getPlayer());
    else if (selection == 2) message = Rankings.summary(cm.getPlayer());
    else if (selection == 3) message = Rankings.donate(cm.getPlayer(), 100000);
    else message = Medals.interact(cm.getPlayer(), selection);
    cm.sendOk(message);
    cm.dispose();
}
