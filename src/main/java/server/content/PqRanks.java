package server.content;
import client.Character;
import java.util.Map;
public final class PqRanks {
    private PqRanks() {}
    public record Requirement(int attempts,int percent,int seconds,int item) {}
    // Pre-Big-Bang S ranks. Romeo/Juliet count as the same party quest.
    public static final Map<String,Requirement> RULES=Map.of(
        "HenesysPQ",new Requirement(100,90,360,1002798),
        "KerningPQ",new Requirement(100,90,600,1072369),
        "LudiPQ",new Requirement(100,90,1200,1022073),
        "EllinPQ",new Requirement(100,80,900,1032061),
        "Magatia",new Requirement(100,80,1200,1122010),
        "PiratePQ",new Requirement(100,0,Integer.MAX_VALUE,1002574));
    public static String key(String event) { return event.equals("MagatiaPQ_A") || event.equals("MagatiaPQ_Z") ? "Magatia" : event; }
    public static void entered(Character chr,String event) {
        String key=key(event); if (RULES.containsKey(key)) chr.getContentState().add("pq."+key+".tries",1);
    }
    public static void cleared(Character chr,String event,long elapsed) {
        String key=key(event); if (!RULES.containsKey(key) || elapsed <= 0) return;
        ContentState state=chr.getContentState(); String prefix="pq."+key+".";
        synchronized(state) {
            state.add(prefix+"wins",1);
            long best=state.get(prefix+"best");
            state.set(prefix+"best",best==0 ? elapsed : Math.min(best,elapsed));
        }
    }
    public static boolean qualifies(ContentState state,String key,Requirement rule,boolean owns) {
        long tries=state.get("pq."+key+".tries"), wins=state.get("pq."+key+".wins"), best=state.get("pq."+key+".best");
        return owns && tries>=rule.attempts() && wins*100>=tries*rule.percent() && best>0 && best<rule.seconds()*1000L;
    }
    public static int sRanks(Character chr) {
        int count=0;
        for (var e:RULES.entrySet()) if (qualifies(chr.getContentState(),e.getKey(),e.getValue(),chr.haveItem(e.getValue().item()))) count++;
        return count;
    }
    public static String summary(Character chr) {
        StringBuilder out=new StringBuilder("S ranks require the original participation count, success rate, best time and reward equipment (carry or equip it).\r\n");
        RULES.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            String p="pq."+e.getKey()+"."; var s=chr.getContentState();var r=e.getValue();
            out.append(e.getKey()).append(": ").append(s.get(p+"wins")).append(" clears / ").append(s.get(p+"tries")).append(" tries; best ").append(s.get(p+"best")/1000).append("s. Need ").append(r.attempts()).append(" tries, ").append(r.percent()).append("%, ").append(r.seconds()==Integer.MAX_VALUE?"no time limit":r.seconds()+"s").append(", #t").append(r.item()).append("#.\r\n");
        });
        return out.toString();
    }
}
