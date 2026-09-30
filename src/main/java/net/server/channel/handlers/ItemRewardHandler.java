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

import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.server.Server;
import server.ItemInformationProvider;
import server.ItemInformationProvider.RewardItem;
import tools.PacketCreator;
import tools.Pair;
import tools.Randomizer;

import java.util.List;

/**
 * @author Jay Estrella
 * @author kevintjuh93
 */
public final class ItemRewardHandler extends AbstractPacketHandler {
    public static RewardItem selectReward(List<RewardItem> rewards, int roll) {
        if (roll < 0) return null;
        for (RewardItem reward : rewards) {
            if (reward.prob <= 0) continue;
            if (roll < reward.prob) return reward;
            roll -= reward.prob;
        }
        return null;
    }

    @Override
    public final void handlePacket(InPacket p, Client c) {
        short slot = p.readShort();
        int itemId = p.readInt();
        if (!c.tryacquireClient()) return;
        try {
            synchronized (c.getPlayer()) {
                var useInventory = c.getPlayer().getInventory(InventoryType.USE);
                try (var locks = server.content.InventoryLocks.acquire(c.getPlayer(), InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP, InventoryType.ETC, InventoryType.CASH)) {
                Item it = useInventory.getItem(slot);
                if (it == null || it.getItemId() != itemId || it.getQuantity() < 1) return;
                ItemInformationProvider ii = ItemInformationProvider.getInstance();
                Pair<Integer, List<RewardItem>> rewards = ii.getItemReward(itemId);
                if (rewards == null || rewards.getLeft() <= 0 || rewards.getRight().isEmpty()) return;
                // Check every possible outcome before rolling: a full inventory must not filter rare prizes.
                for (RewardItem reward : rewards.getRight()) {
                    if (!InventoryManipulator.checkSpace(c, reward.itemid, reward.quantity, "")) {
                        c.sendPacket(PacketCreator.getShowInventoryFull()); return;
                    }
                }
                RewardItem reward = selectReward(rewards.getRight(), Randomizer.nextInt(rewards.getLeft()));
                if (reward == null) return;
                Item item = ItemConstants.getInventoryType(reward.itemid) == InventoryType.EQUIP
                    ? ii.getEquipById(reward.itemid) : new Item(reward.itemid, (short) 0, reward.quantity);
                if (item == null) return;
                if (reward.period > 0) item.setExpiration(currentServerTime() + java.util.concurrent.TimeUnit.HOURS.toMillis(reward.period));
                if (!InventoryManipulator.addFromDrop(c, item, false)) return;
                InventoryManipulator.removeFromSlot(c, InventoryType.USE, slot, (short) 1, false);
                c.sendPacket(PacketCreator.getShowItemGain(reward.itemid, reward.quantity, true));
                if (reward.worldmsg != null) {
                    String msg = reward.worldmsg.replace("/name", c.getPlayer().getName()).replace("/item", ii.getName(reward.itemid));
                    Server.getInstance().broadcastMessage(c.getWorld(), PacketCreator.serverNotice(6, msg));
                }
                }
            }
        } finally {
            c.sendPacket(PacketCreator.enableActions());
            c.releaseClient();
        }
    }
}
