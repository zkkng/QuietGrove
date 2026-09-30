package server.content;

import client.Character;
import client.FamilyEntitlement;
import client.FamilyEntry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import tools.PacketCreator;

public final class FamilyBenefits {
    private FamilyBenefits() {}
    public record Benefit(int expPercent, int dropPercent, int minutes, boolean party) {}
    public static Benefit benefit(FamilyEntitlement type) {
        return switch(type) {
            case SELF_DROP_1_5 -> new Benefit(100,150,15,false);
            case SELF_EXP_1_5 -> new Benefit(150,100,15,false);
            case FAMILY_BONDING -> new Benefit(200,200,30,false);
            case SELF_DROP_2 -> new Benefit(100,200,15,false);
            case SELF_EXP_2 -> new Benefit(200,100,15,false);
            case SELF_DROP_2_30MIN -> new Benefit(100,200,30,false);
            case SELF_EXP_2_30MIN -> new Benefit(200,100,30,false);
            case PARTY_DROP_2_30MIN -> new Benefit(100,200,30,true);
            case PARTY_EXP_2_30MIN -> new Benefit(200,100,30,true);
            default -> null;
        };
    }
    public static double multiplier(ContentState state, boolean exp, int coupon, long now) {
        String key = exp ? "family.exp." : "family.drop.";
        if (state.get(key + "until") <= now) return 1;
        // Family bonuses do not multiply an equal or stronger cash coupon.
        return Math.max(1.0, state.get(key + "percent") / (100.0 * Math.max(1,coupon)));
    }
    public static double expMultiplier(Character chr) {
        return multiplier(chr.getContentState(), true, chr.getCouponExpRate(), System.currentTimeMillis());
    }
    public static double dropMultiplier(Character chr) {
        return multiplier(chr.getContentState(), false, chr.getCouponDropRate(), System.currentTimeMillis());
    }
    public static void grant(ContentState state, Benefit benefit, long now) {
        grantRate(state,"family.exp.",benefit.expPercent(),benefit.minutes(),now);
        grantRate(state,"family.drop.",benefit.dropPercent(),benefit.minutes(),now);
    }
    private static void grantRate(ContentState state,String key,int percent,int minutes,long now) {
        if (percent <= 100) return;
        long until = now + minutes * 60_000L;
        // Refresh matching/stronger bonuses; a weaker purchase never erases a stronger active benefit.
        if (state.get(key+"until") > now && state.get(key+"percent") > percent) return;
        long previous = state.get(key+"percent") == percent ? state.get(key+"until") : 0;
        state.set(key+"percent",percent); state.set(key+"until",Math.max(until,previous));
    }
    public static String use(Character chr, FamilyEntitlement type) {
        FamilyEntry entry = chr.getFamilyEntry();
        Benefit benefit = benefit(type);
        if (entry == null || benefit == null) return "Join a Family before using its benefits.";
        List<Character> targets = new ArrayList<>();
        if (type == FamilyEntitlement.FAMILY_BONDING) {
            var queue = new ArrayDeque<FamilyEntry>();
            var visited = new HashSet<Integer>();
            queue.add(entry);
            while (!queue.isEmpty()) {
                FamilyEntry next = queue.remove();
                if (!visited.add(next.getChrId())) continue;
                if (next != entry && next.getChr() != null && next.getChr().isLoggedinWorld()) targets.add(next.getChr());
                for (FamilyEntry junior : next.getJuniors()) if (junior != null) queue.add(junior);
            }
            if (targets.size() < 6) return "Family Bonding requires six of your junior Family members online.";
        } else if (benefit.party()) {
            if (chr.getParty() == null) return "Join a party and gather its members on this map first.";
            for (var member : chr.getParty().getMembers()) {
                Character target = chr.getMap().getCharacterById(member.getId());
                if (target != null && target != chr && target.isAlive()) targets.add(target);
            }
        }
        targets.add(chr);
        synchronized (entry) {
            if (entry.getReputation() < type.getRepCost()) return "You do not have enough Family reputation.";
            if (entry.isEntitlementUsed(type)) return "You have already used that Family benefit today.";
            if (!entry.purchaseBenefit(type)) return "The benefit could not be saved. Your reputation was not spent.";
        }
        long now = System.currentTimeMillis();
        for (Character target : targets) {
            synchronized (target) { grant(target.getContentState(),benefit,now); }
            target.dropMessage(5, chr.getName()+" shared "+type.getName()+" with you.");
        }
        chr.sendPacket(PacketCreator.getFamilyInfo(entry));
        return type.getName()+" is active. Its timer continues through logout and channel changes.";
    }
}
