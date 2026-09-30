package server.trainer;

import server.life.Monster;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.*;

/** Local map overlays, with explicit filters and no writes to shared monster templates. */
record TrainerMobOptions(boolean freeze, boolean disarm, boolean aggro, int radius,
                         Set<Integer> include, Set<Integer> exclude, boolean pointVac,
                         int x, int y, int spacing, int pullStep) {
    static final TrainerMobOptions OFF = new TrainerMobOptions(false,false,false,0,Set.of(),Set.of(),false,0,0,17,0);
    TrainerMobOptions {
        include = Set.copyOf(include); exclude = Set.copyOf(exclude);
        if (radius < 0 || radius > 3000 || include.size() > 32 || exclude.size() > 32
                || include.stream().anyMatch(id -> id <= 0) || exclude.stream().anyMatch(id -> id <= 0)
                || x < Short.MIN_VALUE || x > Short.MAX_VALUE || y < Short.MIN_VALUE || y > Short.MAX_VALUE
                || spacing < 0 || spacing > 100 || pullStep < 0 || pullStep > 500)
            throw new IllegalArgumentException("Invalid mob control options");
    }
    static TrainerMobOptions parse(Map<String,String> f) {
        if (!f.keySet().equals(Set.of("freeze","disarm","aggro","radius","include","exclude","pointVac","x","y","spacing","pullStep")))
            throw new IllegalArgumentException("Incomplete or unknown mob controls");
        return new TrainerMobOptions(flag(f,"freeze"),flag(f,"disarm"),flag(f,"aggro"),Integer.parseInt(f.get("radius")),
                ids(f.get("include")),ids(f.get("exclude")),flag(f,"pointVac"),Integer.parseInt(f.get("x")),Integer.parseInt(f.get("y")),
                Integer.parseInt(f.get("spacing")),Integer.parseInt(f.get("pullStep")));
    }
    private static boolean flag(Map<String,String> f,String key) {
        if (!Set.of("0","1").contains(f.get(key))) throw new IllegalArgumentException("Flags must be 0 or 1");
        return f.get(key).equals("1");
    }
    private static Set<Integer> ids(String text) {
        Set<Integer> result = new HashSet<>();
        if (!text.isBlank()) for (String id : text.split(",",-1)) result.add(Integer.parseInt(id.trim()));
        return result;
    }
    static boolean ordinary(Monster mob) {
        return mob != null && mob.isAlive() && !mob.isBoss() && mob.getIncidentOwner() == null
                && mob.getMap() != null && mob.getMap().getEventInstance() == null && mob.getStats().selfDestruction() == null;
    }
    boolean accepts(int id, Point player, Point mob) {
        return player != null && mob != null && !exclude.contains(id) && (include.isEmpty() || include.contains(id))
                && (radius == 0 || player.distanceSq(mob) <= (long)radius * radius);
    }
    Point destination(Point player, Point current, int facing, int index, Rectangle area, String mode) {
        Point desired;
        int offset = (index % 5) * spacing;
        if (pointVac) desired = new Point(x + offset, y - 20);
        else if (mode.equals("front")) desired = new Point(player.x + (facing < 0 ? -1 : 1)*(84+offset),player.y-20);
        else desired = new Point(mode.equals("left wall") ? area.x + 84 + offset : area.x + area.width - 84 - offset,player.y-20);
        desired.x = Math.max(area.x,Math.min(area.x + area.width - 1,desired.x));
        desired.y = Math.max(area.y,Math.min(area.y + area.height - 1,desired.y));
        if (pullStep > 0 && current.distanceSq(desired) > (long)pullStep * pullStep) {
            double ratio = pullStep/current.distance(desired);
            desired = new Point(current.x+(int)((desired.x-current.x)*ratio),current.y+(int)((desired.y-current.y)*ratio));
        }
        return desired;
    }
    void status(Map<String,String> r) {
        r.put("mobToolsReady","1"); r.put("mobFreeze",freeze?"1":"0"); r.put("mobDisarm",disarm?"1":"0"); r.put("mobAggro",aggro?"1":"0");
        r.put("mobRadius",Integer.toString(radius)); r.put("mobInclude",text(include)); r.put("mobExclude",text(exclude));
        r.put("pointVac",pointVac?"1":"0"); r.put("pointX",Integer.toString(x)); r.put("pointY",Integer.toString(y));
        r.put("mobSpacing",Integer.toString(spacing)); r.put("mobPullStep",Integer.toString(pullStep));
    }
    private static String text(Set<Integer> ids) { return ids.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")); }
}
