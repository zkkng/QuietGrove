// Shared session owns random doors, deadlines, stage progression and one-time completion.
function enter(pi) {
    return Java.type("server.events.gm.GmEventService").getInstance().coursePortal(pi.getPlayer(), pi.getPortal());
}
