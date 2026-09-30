/* Shared v83 iTCG shop and workshop. Prices/recipes live in data/tcg-catalog.tsv. */
var offers;
var sections = [];
var choices = [];
var stage = 0;
var page = 0;
var selected = -1;
var finished = false;
var pageSize = 10;

function money(value) {
    return String(value).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

function start() {
    offers = cm.getTcgOffers();
    sections = [];
    for (var i = 0; i < offers.size(); i++) {
        var section = String(offers.get(i).section());
        if (sections.indexOf(section) < 0) sections.push(section);
    }
    if (sections.length == 0) { cm.dispose(); return; }
    var hasShop = sections.some(function(s) { return s.indexOf("Buy -") == 0; });
    var text = (hasShop
        ? "Welcome! Buy original code-exclusive rewards with mesos, or use my crafting services.\r\n"
        : "Welcome! Bring the required materials and I can help you with these crafts.\r\n")
        + "Pets last #b90 days#k. Equipment has standard stats. Upgrades are guaranteed; old equipment is consumed and its stats do not transfer.\r\n"
        + "Please keep a free slot in the reward's inventory tab.\r\n";
    for (var j = 0; j < sections.length; j++) text += "\r\n#L" + j + "#" + sections[j] + "#l";
    cm.sendSimple(text);
}

function showPage() {
    var text = "Choose a reward. Prices are for the quantity shown.\r\n";
    for (var i = page * pageSize; i < Math.min(choices.length, (page + 1) * pageSize); i++) {
        var offer = offers.get(choices[i]);
        text += "\r\n#L" + i + "##i" + offer.itemId() + "# #t" + offer.itemId() + "# x" + offer.quantity()
            + " - " + money(offer.mesos()) + " mesos#l";
    }
    if (page > 0) text += "\r\n#L10000#Previous page#l";
    if ((page + 1) * pageSize < choices.length) text += "\r\n#L10001#Next page#l";
    text += "\r\n#L10002#Back to categories#l";
    cm.sendSimple(text);
}

function action(mode, type, selection) {
    if (finished || mode != 1) {
        finished = true;
        cm.dispose();
        return;
    }
    if (stage == 0) {
        if (selection < 0 || selection >= sections.length) { finished = true; cm.dispose(); return; }
        choices = [];
        for (var i = 0; i < offers.size(); i++) {
            if (String(offers.get(i).section()) == sections[selection]) choices.push(i);
        }
        page = 0;
        stage = 1;
        showPage();
    } else if (stage == 1) {
        if (selection == 10000 && page > 0) { page--; showPage(); return; }
        if (selection == 10001 && (page + 1) * pageSize < choices.length) { page++; showPage(); return; }
        if (selection == 10002) { stage = 0; start(); return; }
        if (selection < page * pageSize || selection >= Math.min(choices.length, (page + 1) * pageSize)) {
            finished = true; cm.dispose(); return;
        }
        selected = choices[selection];
        var offer = offers.get(selected);
        var text = "Receive #i" + offer.itemId() + "# #b#t" + offer.itemId() + "# x" + offer.quantity() + "#k"
            + "\r\nCost: #b" + money(offer.mesos()) + " mesos#k";
        for (var j = 0; j < offer.ingredients().size(); j++) {
            var material = offer.ingredients().get(j);
            text += "\r\n#i" + material.itemId() + "# #t" + material.itemId() + "# x" + material.quantity()
                + (material.wholeStack() ? " whole stack(s), including all remaining ammunition" : "");
        }
        if (offer.petDays() > 0) text += "\r\nPet duration: " + offer.petDays() + " days. Use Water of Life after it expires.";
        if (offer.petLevel() > 0) text += "\r\nRequires a summoned pet at level " + offer.petLevel() + " or higher (the pet is kept).";
        text += "\r\n\r\nProceed? Any equipment listed as an ingredient will be consumed; its upgrades will not transfer.";
        stage = 2;
        cm.sendYesNo(text);
    } else if (stage == 2) {
        // Mark finished before the exchange so repeated confirmation cannot purchase twice.
        finished = true;
        cm.sendOk(cm.exchangeTcg(selected));
        cm.dispose();
    }
}
