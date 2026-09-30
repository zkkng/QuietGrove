var eventService = Java.type("server.events.gm.GmEventService").getInstance();
function start() {
    cm.sendSimple("Treasure Hunt lasts ten minutes. Break chests using basic melee, pick up a Treasure Scroll and redeem it for one Scroll of Secrets.\r\n#L0#Enter the eastern field.#l\r\n#L1#Enter the southern field.#l\r\n#L2#Redeem my event treasure.#l\r\n#L3#Leave the event.#l");
}
function action(mode,type,selection) {
    if(mode==1) {
        if(selection==0 || selection==1) cm.sendOk(eventService.treasureField(cm.getPlayer(),selection==0?109010100:109010200));
        else if(selection==2) cm.sendOk(eventService.redeemTreasure(cm.getPlayer())?"Here is your prize!":"You need a Treasure Scroll collected in this event and room for the prize.");
        else if(selection==3) cm.sendOk(cm.leaveGmEvent());
    }
    cm.dispose();
}
