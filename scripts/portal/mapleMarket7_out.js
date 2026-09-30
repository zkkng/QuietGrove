function enter(pi) {
    var destination=pi.getPlayer().getSavedLocation("EVENT");
    if(destination<=0 || (destination>=680100000 && destination<=680100003))destination=100000000;
    pi.playPortalSound();pi.warp(destination,0);return true;
}
