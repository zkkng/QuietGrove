function enter(pi) {
    pi.getPlayer().saveLocation("MIRROR");
    pi.playPortalSound();
    pi.warp(910320000, 2);
    return true;
}
