package server.trainer;

import client.inventory.Equip;
import server.maps.MapItem;
import java.util.Map;
import java.util.Set;

/** Pickup cadence is independent from reach, drop ownership and scenario age. */
record TrainerPickupOptions(boolean tubi, boolean burst, int interval, int batch,
                            boolean scenarioAge, int minimumAge, int minWatk, int minMatk, int minSlots) {
    static final TrainerPickupOptions OFF=new TrainerPickupOptions(false,false,150,10,false,400,0,0,0);
    TrainerPickupOptions {
        if(interval<50||interval>1000||batch<1||batch>25||minimumAge<0||minimumAge>400
                ||minWatk<0||minWatk>32767||minMatk<0||minMatk>32767||minSlots<0||minSlots>15)
            throw new IllegalArgumentException("Invalid pickup cadence/instance filters");
    }
    static TrainerPickupOptions parse(Map<String,String> f) {
        if(!f.keySet().equals(Set.of("tubi","burst","interval","batch","scenarioAge","minimumAge","minWatk","minMatk","minSlots")))
            throw new IllegalArgumentException("Incomplete or unknown pickup settings");
        return new TrainerPickupOptions(TrainerProfile.flag(f,"tubi"),TrainerProfile.flag(f,"burst"),Integer.parseInt(f.get("interval")),
                Integer.parseInt(f.get("batch")),TrainerProfile.flag(f,"scenarioAge"),Integer.parseInt(f.get("minimumAge")),
                Integer.parseInt(f.get("minWatk")),Integer.parseInt(f.get("minMatk")),Integer.parseInt(f.get("minSlots")));
    }
    boolean automated() { return tubi||burst; }
    TrainerPickupOptions paused() { return new TrainerPickupOptions(false,false,interval,batch,false,minimumAge,minWatk,minMatk,minSlots); }
    int age(MapItem item) { return scenarioAge&&item.getVenueAssetId()!=null&&item.getVenueRoundId()!=null?minimumAge:400; }
    boolean accepts(MapItem item) {
        if(item.getMeso()>0||minWatk==0&&minMatk==0&&minSlots==0) return true;
        return item.getItem() instanceof Equip equip && equip.getWatk()>=minWatk&&equip.getMatk()>=minMatk&&equip.getUpgradeSlots()>=minSlots;
    }
    void status(Map<String,String> r) {
        r.put("pickupOptionsReady","1"); r.put("tubi",tubi?"1":"0"); r.put("burst",burst?"1":"0");
        r.put("pickupInterval",Integer.toString(interval)); r.put("pickupBatch",Integer.toString(batch));
        r.put("scenarioAge",scenarioAge?"1":"0"); r.put("minimumAge",Integer.toString(minimumAge));
        r.put("minWatk",Integer.toString(minWatk)); r.put("minMatk",Integer.toString(minMatk)); r.put("minSlots",Integer.toString(minSlots));
    }
    /** Non-accumulating allowance shared by all trainer pickup triggers. */
    static final class Budget {
        private long refillAt;
        private int remaining;
        int available(long now) { if(now>=refillAt) { remaining=25; refillAt=now+500; } return remaining; }
        boolean take(long now) { if(available(now)==0) return false; remaining--; return true; }
    }
}
