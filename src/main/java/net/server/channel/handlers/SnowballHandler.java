/* Snowball event packet handling derives from OdinMS, copyright 2008, AGPL-3.0. */
package net.server.channel.handlers;

import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.events.gm.GmEventService;

public final class SnowballHandler extends AbstractPacketHandler {
    @Override public void handlePacket(InPacket packet,Client client) {
        if(packet.available()<1 || client.getPlayer()==null || client.getPlayer().getMap()==null) return;
        GmEventService.getInstance().snowballHit(client.getPlayer(),packet.readByte(),packet.receivedAtNs());
    }
}
