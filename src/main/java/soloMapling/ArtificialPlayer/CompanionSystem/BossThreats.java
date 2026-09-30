package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import client.Disease;
import client.inventory.InventoryType;
import provider.*;
import provider.wz.WZFiles;
import server.ItemInformationProvider;
import server.life.MobSkillType;
import java.util.*;

/** Versioned catalog threat metadata derived from the exact local combat templates. */
public final class BossThreats {
    public record Profile(Set<MobSkillType> skills, Set<Disease> disablingCleanses, String mobilityPolicy) {}
    private static final Map<String,Profile> profiles=new java.util.concurrent.ConcurrentHashMap<>();
    private BossThreats() {}
    public static Profile profile(BossDefinition boss) {
        return profiles.computeIfAbsent(boss.key()+":"+boss.version(),ignored->{
            Set<MobSkillType> kinds=EnumSet.noneOf(MobSkillType.class);
            var source=DataProviderFactory.getDataProvider(WZFiles.MOB);
            for(int id:boss.phases()) if(boss.attackTemplate(id)) {
                Data mob=source.getData(String.format("%07d.img",id));
                String link=DataTool.getString("info/link",mob,"");
                if(!link.isEmpty()) mob=source.getData(String.format("%07d.img",Integer.parseInt(link)));
                Data actions=mob.getChildByPath("info/skill");
                if(actions!=null) for(Data action:actions) MobSkillType.from(DataTool.getInt("skill",action,0)).ifPresent(kinds::add);
                for(int attack=1;attack<=9;attack++) MobSkillType.from(DataTool.getInt("attack"+attack+"/info/disease",mob,0)).ifPresent(kinds::add);
            }
            Set<Disease> cleanses=EnumSet.noneOf(Disease.class);
            if(kinds.contains(MobSkillType.SEAL)) cleanses.add(Disease.SEAL);
            if(kinds.contains(MobSkillType.DARKNESS)) cleanses.add(Disease.DARKNESS);
            return new Profile(Set.copyOf(kinds),Set.copyOf(cleanses),"Real footholds, learned movement skills, ordinary portals; bounded hold/replan on unreachable attack rectangles");
        });
    }
    static String cleanseFailure(Character bot, Set<Disease> required) {
        if(required.isEmpty()) return "";
        Set<Disease> covered=EnumSet.noneOf(Disease.class);
        for(var item:bot.getInventory(InventoryType.USE).list()) if(item.getQuantity()>=3 && item.getItemId()/10000==205) {
            var effect=ItemInformationProvider.getInstance().getItemEffect(item.getItemId());
            if(effect!=null) covered.addAll(effect.getCureDebuffs());
        }
        if(!covered.containsAll(required)) return "I need real cures for " + required.stream().filter(d->!covered.contains(d))
                .map(d->d.name().toLowerCase(Locale.ROOT)).sorted().reduce((a,b)->a+" and "+b).orElse("disabling statuses");
        return "";
    }
}
