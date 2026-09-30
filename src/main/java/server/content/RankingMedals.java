package server.content;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import java.sql.SQLException;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;
import net.server.Server;
import org.slf4j.LoggerFactory;
import server.quest.Quest;
import tools.DatabaseConnection;

/** Rankings use saved character state, overlaid with every online channel in the world. */
public final class RankingMedals {
    private RankingMedals() {}
    public static final int[] IDS={29500,29501,29502,29503,29505,29506};
    private static final Map<Integer,Snapshot> cache=new HashMap<>();
    private record Entry(int id,String name,int fame,ContentState state) {}
    private record Snapshot(long at,List<Entry> entries) {}
    public static boolean ranking(int id) { return Arrays.stream(IDS).anyMatch(i->i==id); }
    private static int town(Character chr) { return chr.getMapId()/1_000_000; }
    public static String donationKey(int town) { return "rank.donation."+YearMonth.now(ZoneOffset.UTC).toString().replace("-","")+"."+town; }
    public static void started(Character chr,int id) {
        if(!ranking(id)) return;
        ContentState s=chr.getContentState();
        if(s.get("rank.entry."+id)==0) {
            s.set("rank.entry."+id,1);
            if(id==29501) s.set("rank.base.horntail",s.get("rank.horntail"));
            if(id==29502) s.set("rank.base.pinkbean",s.get("rank.pinkbean"));
        }
    }
    public static long score(ContentState s,int fame,int id,int town) {
        if(s.get("rank.entry."+id)==0) return 0;
        return switch(id) {
            case 29500 -> Math.max(0,fame);
            case 29501 -> Math.max(0,s.get("rank.horntail")-s.get("rank.base.horntail"));
            case 29502 -> Math.max(0,s.get("rank.pinkbean")-s.get("rank.base.pinkbean"));
            case 29503 -> s.get(donationKey(town));
            case 29505 -> s.get("rank.carnival.wins");
            case 29506 -> s.get("rank.carnival.plays")>0 ? s.get("rank.carnival.wins")*1_000_000/s.get("rank.carnival.plays") : 0;
            default -> 0;
        };
    }
    private static synchronized List<Entry> entries(Character chr,boolean refresh) throws SQLException {
        int world=chr.getWorld(); long now=System.currentTimeMillis();Snapshot old=cache.get(world);
        List<Entry> saved;
        if(!refresh && old!=null && now-old.at()<60_000) saved=old.entries();
        else {
            saved=new ArrayList<>();
            try(var con=DatabaseConnection.getConnection();var ps=con.prepareStatement("SELECT c.id,c.name,c.fame,s.state FROM characters c JOIN character_content s ON c.id=s.characterid WHERE c.world=? AND c.gm=0")) {
                ps.setInt(1,world);
                try(var rs=ps.executeQuery()) {
                    while(rs.next()) {
                        try {saved.add(new Entry(rs.getInt(1),rs.getString(2),rs.getInt(3),ContentState.decode(rs.getString(4))));}
                        catch(IllegalArgumentException ex) {throw new SQLException("Invalid ranking state",ex);}
                    }
                }
            }
            cache.put(world,new Snapshot(now,List.copyOf(saved)));
        }
        Map<Integer,Entry> rows=new HashMap<>();for(var e:saved)rows.put(e.id(),e);
        for(Character online:Server.getInstance().getWorld(world).getPlayerStorage().getAllCharacters()) {
            if(!online.isGM() && online.isLoggedinWorld()) rows.put(online.getId(),new Entry(online.getId(),online.getName(),online.getFame(),online.getContentState()));
        }
        return new ArrayList<>(rows.values());
    }
    private static Entry leader(List<Entry> entries,int id,int town) {
        return entries.stream().filter(e->score(e.state(),e.fame(),id,town)>0)
            .min(Comparator.<Entry>comparingLong(e->-score(e.state(),e.fame(),id,town)).thenComparingInt(Entry::id)).orElse(null);
    }
    public static boolean isLeader(Character chr,int id) {
        try {var winner=leader(entries(chr,true),id,town(chr));return winner!=null && winner.id()==chr.getId();}
        catch(SQLException ex) {LoggerFactory.getLogger(RankingMedals.class).warn("Could not read medal ranking",ex);return false;}
    }
    public static String summary(Character chr) {
        try {
            var rows=entries(chr,true);StringBuilder out=new StringBuilder("Rankings are world-wide. Donation King resets monthly by town (UTC). Ties go to the older character. Titles pass to a new leader; validation runs every minute.\r\n");
            for(int id:IDS) {var e=leader(rows,id,town(chr));out.append(Quest.getInstance(id).getName()).append(": ").append(e==null?"No record":e.name()+" ("+score(e.state(),e.fame(),id,town(chr))+(id==29506?" / 1,000,000 win ratio":"")+")").append("\r\n");}
            return out.toString();
        } catch(SQLException ex) {return "The ranking records could not be read. Please try again; no medal or mesos were taken.";}
    }
    public static String donate(Character chr,int amount) {
        synchronized(chr) {
            if(amount!=100_000 || chr.getLevel()<30 || chr.getMeso()<amount) return "Donation King requires level 30 and 100,000 mesos for this donation.";
            if(!chr.getMap().isTown()) return "Make donations to Dalair in town.";
            started(chr,29503);
            chr.gainMeso(-amount,false);
            chr.getContentState().add(donationKey(town(chr)),amount);
            return "Your town's donation total this month is "+chr.getContentState().get(donationKey(town(chr)))+" mesos. Select Donation King to claim when you lead.";
        }
    }
    public static void claimed(Character chr,int id) {
        if(id==29503) chr.getContentState().set("rank.held.town",town(chr));
    }
    public static void validate(Character chr) {
        if(Arrays.stream(IDS).noneMatch(id->chr.haveItem(Quest.getInstance(id).getMedalRequirement()))) return;
        try {
            var rows=entries(chr,false);
            synchronized (chr) {
            try (var locks = InventoryLocks.acquire(chr, InventoryType.EQUIPPED, InventoryType.EQUIP)) {
            for(int id:IDS) {
                int item=Quest.getInstance(id).getMedalRequirement();
                if(!chr.haveItem(item)) continue;
                int town=id==29503?(int)chr.getContentState().get("rank.held.town"):town(chr);
                var winner=leader(rows,id,town);
                if(winner!=null && winner.id()==chr.getId())continue;
                for(var type:List.of(InventoryType.EQUIP,InventoryType.EQUIPPED)) {
                    for(var owned:new ArrayList<>(chr.getInventory(type).list())) if(owned.getItemId()==item) {
                        InventoryManipulator.removeFromSlot(chr.getClient(),type,owned.getPosition(),owned.getQuantity(),true);
                    }
                }
                chr.equipChanged();Quest.getInstance(id).reset(chr);
                chr.dropMessage(5,"The "+Quest.getInstance(id).getName()+" title has passed to the current leader. Your record is retained.");
            }
            }
            }
        } catch(SQLException ex) {LoggerFactory.getLogger(RankingMedals.class).warn("Medal validation deferred: ranking store unavailable",ex);}
    }
    public static void carnival(Character chr,boolean won) {
        chr.getContentState().add("rank.carnival.plays",1);
        if(won)chr.getContentState().add("rank.carnival.wins",1);
    }
}
