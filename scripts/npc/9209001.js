var status=0;
function start() {cm.sendSimple("Welcome to the Maple 7th Day Market! We are open every day. Our merchants trade regional supplies at hourly prices, and Lazy Daisy has eggs to raise. Abdula still offers his mastery-book guidance.\r\n#L0#Enter market layout 1#l\r\n#L1#Enter market layout 2#l\r\n#L2#Enter market layout 3#l\r\n#L3#Enter market layout 4#l");}
function action(mode,type,selection) {
    if(mode==1 && status++==0 && selection>=0 && selection<=3) {
        if(cm.getPlayer().getLevel()<10)cm.sendOk("Come back at level 10.");
        else {cm.getPlayer().saveLocation("EVENT");cm.warp(680100000+selection,0);}
    }
    cm.dispose();
}
