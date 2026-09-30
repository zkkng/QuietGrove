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
package server;

import tools.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author Jay Estrella, Ronan
 */
public class MakerItemFactory {
    private static final ItemInformationProvider ii = ItemInformationProvider.getInstance();

    public static MakerItemCreateEntry getItemCreateEntry(int toCreate, int stimulantid, Map<Integer, Short> reagentids) {
        MakerItemCreateEntry makerEntry = ii.getMakerItemEntry(toCreate);
        if (makerEntry == null) {
            return new MakerItemCreateEntry(0, -1, -1);
        }
        if (makerEntry.isInvalid()) {
            return makerEntry;
        }

        makerEntry.reqCost = MakerCost.calculate(makerEntry.reqCost, stimulantid != -1, reagentids);
        return makerEntry;
    }

    public static MakerItemCreateEntry generateLeftoverCrystalEntry(int fromLeftoverid, int crystalId) {
        MakerItemCreateEntry ret = new MakerItemCreateEntry(0, 0, 1);
        ret.addReqItem(fromLeftoverid, 100);
        ret.addGainItem(crystalId, 1);
        return ret;
    }

    public static MakerItemCreateEntry generateDisassemblyCrystalEntry(int fromEquipid, int cost, List<Pair<Integer, Integer>> gains) {     // equipment at specific position already taken
        MakerItemCreateEntry ret = new MakerItemCreateEntry(cost, 0, 1);
        ret.addReqItem(fromEquipid, 1);
        for (Pair<Integer, Integer> p : gains) {
            ret.addGainItem(p.getLeft(), p.getRight());
        }
        return ret;
    }

    public static class MakerItemCreateEntry {
        private final int reqLevel;
        private final int reqMakerLevel;
        private int reqCost;
        private final List<Pair<Integer, Integer>> reqItems = new ArrayList<>(); // itemId / amount
        private final List<Pair<Integer, Integer>> gainItems = new ArrayList<>(); // itemId / amount

        public MakerItemCreateEntry(int cost, int reqLevel, int reqMakerLevel) {
            this.reqCost = cost;
            this.reqLevel = reqLevel;
            this.reqMakerLevel = reqMakerLevel;
        }

        public MakerItemCreateEntry(MakerItemCreateEntry mi) {
            this.reqCost = mi.reqCost;
            this.reqLevel = mi.reqLevel;
            this.reqMakerLevel = mi.reqMakerLevel;

            reqItems.addAll(mi.reqItems);

            gainItems.addAll(mi.gainItems);
        }

        public List<Pair<Integer, Integer>> getReqItems() {
            return reqItems;
        }

        public List<Pair<Integer, Integer>> getGainItems() {
            return gainItems;
        }

        public int getReqLevel() {
            return reqLevel;
        }

        public int getReqSkillLevel() {
            return reqMakerLevel;
        }

        public int getCost() {
            return reqCost;
        }

        protected void addReqItem(int itemId, int amount) {
            reqItems.add(new Pair<>(itemId, amount));
        }

        protected void addGainItem(int itemId, int amount) {
            gainItems.add(new Pair<>(itemId, amount));
        }

        public boolean isInvalid() {    // thanks Rohenn, Wh1SK3Y for noticing some items not getting checked properly
            return reqLevel < 0;
        }
    }
}
