function start() {
    cm.sendSimple("Use basic melee to break chests. Your Treasure Scroll must come from this event.\r\n#L0#Redeem my treasure.#l\r\n#L1#Leave the event.#l");
}
function action(mode,type,selection) {
    if(mode==1) {
        if(selection==0) cm.sendOk(Java.type("server.events.gm.GmEventService").getInstance().redeemTreasure(cm.getPlayer())?"Here is your Scroll of Secrets!":"Collect an event Treasure Scroll and make room for your prize first.");
        else if(selection==1) cm.sendOk(cm.leaveGmEvent());
    }
    cm.dispose();
}
