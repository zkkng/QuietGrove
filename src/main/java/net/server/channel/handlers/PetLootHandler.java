/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.inventory.Pet;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.MapObject;
import server.trainer.TrainerBotReactions;
import tools.PacketCreator;

import java.awt.Point;
import java.util.Set;

/**
 * @author TheRamon
 * @author Ronan
 */
public final class PetLootHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(PetLootHandler.class);
    @Override
    public final void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();

        int petIndex = chr.getPetIndex(p.readInt());
        Pet pet = chr.getPet(petIndex);
        if (pet == null || !pet.isSummoned()) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        p.skip(13);
        int oid = p.readInt();
        MapleMap pickupMap = chr.getMap();
        MapObject ob = pickupMap.getMapObject(oid);
        try {
            MapItem mapitem = (MapItem) ob;
            if (mapitem.getMeso() > 0) {
                if (!chr.isEquippedMesoMagnet()) {
                    c.sendPacket(PacketCreator.enableActions());
                    return;
                }

                if (chr.isEquippedPetItemIgnore()) {
                    final Set<Integer> petIgnore = chr.getExcludedItems();
                    if (!petIgnore.isEmpty() && petIgnore.contains(Integer.MAX_VALUE)) {
                        c.sendPacket(PacketCreator.enableActions());
                        return;
                    }
                }
            } else {
                if (!chr.isEquippedItemPouch()) {
                    c.sendPacket(PacketCreator.enableActions());
                    return;
                }

                if (chr.isEquippedPetItemIgnore()) {
                    final Set<Integer> petIgnore = chr.getExcludedItems();
                    if (!petIgnore.isEmpty() && petIgnore.contains(mapitem.getItem().getItemId())) {
                        c.sendPacket(PacketCreator.enableActions());
                        return;
                    }
                }
            }

            Point dropPosition = new Point(mapitem.getPosition());
            boolean committed;
            mapitem.lockItem();
            try {
                boolean alreadyPickedUp = mapitem.isPickedUp();
                chr.pickupItem(ob, petIndex);
                committed = !alreadyPickedUp && mapitem.isPickedUp()
                        && mapitem.getCollectedByCharacterId() == chr.getId();
            } finally { mapitem.unlockItem(); }
            if (committed) {
                try { TrainerBotReactions.onCommittedPickup(chr, pickupMap, mapitem, dropPosition, true); }
                catch (RuntimeException reactionFailure) {
                    log.warn("Bot reaction failed after pet pickup character={} item={}",
                            chr.getName(), mapitem.getItemId(), reactionFailure);
                }
            }
        } catch (NullPointerException | ClassCastException e) {
            c.sendPacket(PacketCreator.enableActions());
        }
    }
}
