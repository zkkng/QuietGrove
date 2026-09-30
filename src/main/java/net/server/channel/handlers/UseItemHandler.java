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
import client.Disease;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.ItemInformationProvider;
import server.StatEffect;
import server.pccafe.PcCafe;
import tools.PacketCreator;

/**
 * @author Matze
 */
public final class UseItemHandler extends AbstractPacketHandler {
    @Override
    public final void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();

        if (!chr.isAlive()) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        p.readInt();
        short slot = p.readShort();
        int itemId = p.readInt();
        Item toUse = chr.getInventory(InventoryType.USE).getItem(slot);
        if (toUse != null && toUse.getQuantity() > 0 && toUse.getItemId() == itemId) {
            if (itemId == ItemId.ALL_CURE_POTION) {
                chr.dispelDebuffs();
                remove(c, slot);
                return;
            } else if (itemId == ItemId.EYEDROP) {
                chr.dispelDebuff(Disease.DARKNESS);
                remove(c, slot);
                return;
            } else if (itemId == ItemId.TONIC) {
                chr.dispelDebuff(Disease.WEAKEN);
                chr.dispelDebuff(Disease.SLOW);
                remove(c, slot);
                return;
            } else if (itemId == ItemId.HOLY_WATER) {
                chr.dispelDebuff(Disease.SEAL);
                chr.dispelDebuff(Disease.CURSE);
                remove(c, slot);
                return;
            } else if (ItemConstants.isTownScroll(itemId)) {
                // Only actual return-scroll items get this override; the legacy
                // isTownScroll predicate also matches unrelated higher item IDs.
                if (itemId / 10000 == 203 && PcCafe.isCafeMap(chr.getMapId())) {
                    if (chr.getMapId() == PcCafe.HUB) {
                        c.sendPacket(PacketCreator.enableActions());
                        return;
                    }
                    var lobby = c.getChannelServer().getMapFactory().getMap(PcCafe.HUB);
                    var portal = lobby == null ? null : lobby.getPortal(0);
                    if (portal == null) {
                        chr.dropMessage(5, "The PC Café lobby is unavailable. Your scroll was not used.");
                        c.sendPacket(PacketCreator.enableActions());
                        return;
                    }
                    chr.changeMap(lobby, portal);
                    if (chr.getMapId() == PcCafe.HUB) {
                        remove(c, slot);
                    } else {
                        c.sendPacket(PacketCreator.enableActions());
                    }
                    return;
                }
                if (ii.getItemEffect(toUse.getItemId()).applyTo(chr)) {
                    remove(c, slot);
                }
                return;
            }

            remove(c, slot);

            if (toUse.getItemId() != ItemId.HAPPY_BIRTHDAY) {
                ii.getItemEffect(toUse.getItemId()).applyTo(chr);
            } else {
                StatEffect mse = ii.getItemEffect(toUse.getItemId());
                for (Character player : chr.getMap().getCharacters()) {
                    mse.applyTo(player);
                }
            }
        }
    }

    private void remove(Client c, short slot) {
        InventoryManipulator.removeFromSlot(c, InventoryType.USE, slot, (short) 1, false);
        c.sendPacket(PacketCreator.enableActions());
    }
}
