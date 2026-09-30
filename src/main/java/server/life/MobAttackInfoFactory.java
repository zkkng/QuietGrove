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
package server.life;

import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import tools.StringUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Danny (Leifde)
 */
public class MobAttackInfoFactory {
    private static final Map<String, MobAttackInfo> mobAttacks = new java.util.concurrent.ConcurrentHashMap<>();
    private static final DataProvider dataSource = DataProviderFactory.getDataProvider(WZFiles.MOB);

    public static MobAttackInfo getMobAttackInfo(Monster mob, int attack) {
        MobAttackInfo ret = mobAttacks.get(mob.getId() + "" + attack);
        if (ret != null) {
            return ret;
        }
        synchronized (mobAttacks) {
            ret = mobAttacks.get(mob.getId() + "" + attack);
            if (ret == null) {
                Data mobData = dataSource.getData(StringUtil.getLeftPaddedStr(mob.getId() + ".img", '0', 11));
                if (mobData != null) {
//					MapleData infoData = mobData.getChildByPath("info");
                    String linkedmob = DataTool.getString("info/link", mobData, "");
                    if (!linkedmob.equals("")) {
                        mobData = dataSource.getData(StringUtil.getLeftPaddedStr(linkedmob + ".img", '0', 11));
                    }
                    Data attackData = mobData.getChildByPath("attack" + (attack + 1) + "/info");

                    if (attackData == null) {
                        return null;
                    }

                    Data deadlyAttack = attackData.getChildByPath("deadlyAttack");
                    int mpBurn = DataTool.getInt("mpBurn", attackData, 0);
                    int disease = DataTool.getInt("disease", attackData, 0);
                    int level = DataTool.getInt("level", attackData, 0);
                    int mpCon = DataTool.getInt("conMP", attackData, 0);
                    ret = new MobAttackInfo(mob.getId(), attack);
                    ret.setDeadlyAttack(deadlyAttack != null);
                    ret.setMpBurn(mpBurn);
                    ret.setDiseaseSkill(disease);
                    ret.setDiseaseLevel(level);
                    ret.setMpCon(mpCon);
                    ret.setRange(DataTool.getPoint("range/lt", attackData, null), DataTool.getPoint("range/rb", attackData, null));
                    ret.setAttackDelay(DataTool.getInt("attackAfter", attackData, 0));
                    ret.setMagic(DataTool.getInt("magic", attackData, 0) != 0 || DataTool.getInt("MADamage",attackData,0)>0);
                    ret.setAttackPower(DataTool.getInt("PADamage",attackData,mob.getStats().getPADamage()),DataTool.getInt("MADamage",attackData,mob.getStats().getMADamage()));
                    ret.setProjectile(DataTool.getInt("range/r",attackData,0),DataTool.getPoint("range/sp",attackData,null),DataTool.getInt("bulletSpeed",attackData,100));
                    ret.setTargetCount(DataTool.getInt("count",attackData,1));
                }
                if (ret != null) mobAttacks.put(mob.getId() + "" + attack, ret);
            }
            return ret;
        }
    }
}
