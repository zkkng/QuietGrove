function act() {
    Java.type("server.events.gm.GmEventService").getInstance().treasureChestBroken(rm.getPlayer(), rm.getReactor());
}
