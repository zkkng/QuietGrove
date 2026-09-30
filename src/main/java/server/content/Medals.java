package server.content;

import client.Character;
import client.QuestStatus.Status;
import client.inventory.manipulator.InventoryManipulator;
import java.time.LocalDate;
import java.time.ZoneOffset;
import server.life.Monster;
import server.quest.Quest;

/** Server-owned progress supplements the ordinary WZ quest requirements and rewards. */
public final class Medals {
    private Medals() {}
    public static final int[] CHALLENGES={29000,29001,29002,29003,29004,29005,29006,29007,29008,29009,29010,29011,29012,29013,29014,29015,29016,29017,29018,29019,29020,29300,29301,29302,29303,29304,29400,29500,29501,29502,29503,29505,29506,29507,29508,29509,29512,29931,29932};
    public static boolean active(Character chr,int id) { return chr.getQuest(Quest.getInstance(id)).getStatus()==Status.STARTED; }
    private static boolean timed(int id) { return id==29002 || id==29003 || id==29400; }
    public static void started(Character chr,int id) {
        RankingMedals.started(chr,id);
        if (timed(id)) {
            String p="medal."+id+".";
            var s=chr.getContentState(); s.removePrefix(p);
            s.set(p+"start",System.currentTimeMillis());
            s.set(p+"fame",chr.getFame());
        }
        if (id==29020) chr.getContentState().set("medal.hair",0);
    }
    public static boolean withinTrial(ContentState state,int id,long now) {
        long start=state.get("medal."+id+".start");
        return start>0 && now>=start && now-start<30L*24*60*60*1000;
    }
    public static boolean canComplete(Character chr,int id) {
        var s=chr.getContentState();
        if (timed(id) && !withinTrial(s,id,System.currentTimeMillis())) return false;
        return switch(id) {
            case 29002 -> chr.getFame()-s.get("medal.29002.fame") >= 1000;
            case 29003 -> s.get("medal.29003.days") >= 27;
            case 29400 -> s.get("medal.29400.kills") >= 1_000_000;
            case 29508 -> chr.isMarried() && chr.getGuildId()>0 && chr.getFamilyEntry()!=null && chr.getFamilyEntry().getJuniors().stream().anyMatch(j -> j!=null);
            case 29500,29501,29502,29503,29505,29506 -> RankingMedals.isLeader(chr,id);
            default -> true;
        };
    }
    public static boolean handlesInfo(int id) { return id>=29004 && id<=29015 || id==29020 || id==29931 || id==29932; }
    public static boolean checkInfo(Character chr,Quest q) {
        var status=chr.getQuest(q).getStatus();
        if (status!=Status.STARTED && (q.getId()==29012 || q.getId()==29013)) return true; // obsolete client registration marker
        var expected=q.getInfoEx(status); int info=q.getInfoNumber(status);
        for(int i=0;i<expected.size();i++) {
            try {
                long actual=Long.parseLong(chr.getAbstractPlayerInteraction().getQuestProgress(info>0?info:q.getId(),i));
                if(actual<Long.parseLong(expected.get(i))) return false;
            } catch(NumberFormatException e) { return false; }
        }
        return true;
    }
    public static boolean prepareReward(Character chr,Quest quest) {
        int item=quest.getMedalRequirement();
        if(item<0 || quest.hasCompletionItems() || chr.haveItem(item)) return true;
        if (!InventoryManipulator.checkSpace(chr.getClient(),item,1,"")) {chr.dropMessage(5,"Make room in your Equip inventory for the medal.");return false;}
        boolean granted=InventoryManipulator.addById(chr.getClient(),item,(short)1);
        if(granted) RankingMedals.claimed(chr,quest.getId());
        return granted;
    }
    public static void killed(Character chr,Monster mob) {
        if (active(chr,29400) && withinTrial(chr.getContentState(),29400,System.currentTimeMillis()) && mob.getLevel() >= Math.min(chr.getLevel(),120)) chr.getContentState().add("medal.29400.kills",1);
        if (mob.getId()==8810018) chr.getContentState().add("rank.horntail",1);
        if (mob.getId()==8820001) chr.getContentState().add("rank.pinkbean",1);
    }
    public static void hairstyle(Character chr,int oldStyle,int newStyle) {
        if(oldStyle/10!=newStyle/10 && active(chr,29020)) {
            long count=Math.min(50,chr.getContentState().add("medal.hair",1));
            chr.setQuestProgress(29020,29020,Long.toString(count));
        }
    }
    public static void onlineMinute(Character chr) {
        if(!active(chr,29003) || !withinTrial(chr.getContentState(),29003,System.currentTimeMillis())) return;
        ContentState s=chr.getContentState(); long now=System.currentTimeMillis(), day=LocalDate.now(ZoneOffset.UTC).toEpochDay();
        synchronized(s) {
            long last=s.get("medal.29003.tick");
            if(s.get("medal.29003.day")!=day || now-last>90_000 || now<last) s.set("medal.29003.session",0);
            long elapsed=last>0 && now-last<=90_000 ? now-last : 0;
            s.set("medal.29003.tick",now);s.set("medal.29003.day",day);
            if(s.get("medal.29003.credited")!=day && s.add("medal.29003.session",elapsed)>=3*60*60*1000L) {
                s.set("medal.29003.credited",day);s.add("medal.29003.days",1);
                chr.dropMessage(5,"Diligent Explorer: "+s.get("medal.29003.days")+" / 27 days completed.");
            }
        }
    }
    public static String menu(Character chr) {
        StringBuilder out=new StringBuilder("Medal challenges retain their quest requirements. Select one to start it, check progress or claim an earned medal.\r\n#L1#Party quest S-rank records#l\r\n#L2#Ranking standings#l\r\n#L3#Donate 100,000 mesos for Donation King (this town, this month)#l\r\n#L4#Retry a completed challenge with a missing medal#l\r\n");
        for(int id:CHALLENGES) {
            Quest q=Quest.getInstance(id);
            out.append("#L").append(id).append("#").append(q.getName()).append(" [").append(chr.getQuest(q).getStatus()).append("]#l\r\n");
        }
        return out.toString();
    }
    public static String retryMenu(Character chr) {
        StringBuilder out = new StringBuilder("Retry a completed challenge whose medal you no longer own. Its challenge requirements apply again, including timed progress. This also repairs old placeholder completions that never awarded a medal.\r\n");
        for (int id : CHALLENGES) {
            Quest q = Quest.getInstance(id);
            if (chr.getQuest(q).getStatus() == Status.COMPLETED && !chr.haveItem(q.getMedalRequirement()))
                out.append("#L").append(100000 + id).append("#Retry ").append(q.getName()).append("#l\r\n");
        }
        return out.toString();
    }
    public static String retryMissing(Character chr, int id) {
        synchronized (chr) {
            if (java.util.Arrays.stream(CHALLENGES).noneMatch(q -> q == id)) return "That challenge cannot be retried here.";
            Quest q = Quest.getInstance(id);
            if (chr.getQuest(q).getStatus() != Status.COMPLETED || chr.haveItem(q.getMedalRequirement())) return "Only completed challenges with a missing medal can be retried.";
            q.reset(chr);
            return interact(chr, id);
        }
    }
    public static String interact(Character chr,int id) {
        if(id < 1 || id > Short.MAX_VALUE) return "That challenge is not available here.";
        if(Quest.getInstance(id).getMedalRequirement()<0) return "That challenge is not available here.";
        synchronized(chr) {
            Quest q=Quest.getInstance(id);var status=chr.getQuest(q).getStatus();
            if(status==Status.COMPLETED) return "You have already earned this medal.";
            if(timed(id) && status==Status.STARTED && !withinTrial(chr.getContentState(),id,System.currentTimeMillis())) {q.forfeit(chr);status=Status.NOT_STARTED;}
            if(status==Status.NOT_STARTED) {
                q.start(chr,q.getNpcRequirement(false));
                if(!active(chr,id)) return "Meet the level, job and prerequisite quests shown in your quest window first.";
            }
            q.complete(chr,q.getNpcRequirement(true));
            if(chr.getQuest(q).getStatus()==Status.COMPLETED) return "You earned #t"+q.getMedalRequirement()+"#!";
            var s=chr.getContentState();
            return switch(id) {
                case 29000 -> PqRanks.summary(chr);
                case 29002 -> "Gain 1,000 fame after accepting this 30-day challenge. Progress: "+Math.max(0,chr.getFame()-s.get("medal.29002.fame"))+" / 1,000.";
                case 29003 -> "Stay online for three consecutive hours on 27 days within 30 days (UTC days). Progress: "+s.get("medal.29003.days")+" / 27.";
                case 29400 -> "Hunt 1,000,000 monsters at your level or higher in 30 days. From level 120, level-120+ monsters count. Progress: "+s.get("medal.29400.kills")+" / 1,000,000.";
                case 29508 -> "Be married, belong to a guild and have at least one junior in your Family.";
                case 29500,29501,29502,29503,29505,29506 -> RankingMedals.summary(chr);
                default -> "Challenge started. Complete the requirements in your quest window, then speak to the quest NPC or return to Dalair. Leave room in Equip for your medal.";
            };
        }
    }
}
