package server.events.gm;

import constants.id.MapId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Stage progress and random doors belong to the session, never to a player's client. */
public final class CourseEvent {
    public record Transition(int actorId, int sourceMap, int destinationMap, String destinationPortal,
                             boolean finish) {}
    private final List<Integer> maps;
    private final boolean randomOla;
    private final Map<Integer,Integer> stages = new HashMap<>();
    private final Map<Integer,Set<String>> correctDoors = new HashMap<>();
    private final Map<Integer,Transition> pending = new HashMap<>();
    private final Set<Integer> finished = new HashSet<>();

    public CourseEvent(EventDefinition definition, long seed) {
        maps = definition.mapIds();
        randomOla = definition.key().equals("ola");
        if (randomOla) {
            Random random = new Random(seed);
            int[] counts = {5,8,16}, successes = {2,3,2};
            for (int stage=0; stage<maps.size(); stage++) {
                Set<String> doors = new HashSet<>();
                while (doors.size()<successes[stage]) doors.add(String.format("ch%02d",random.nextInt(counts[stage])));
                correctDoors.put(maps.get(stage),Set.copyOf(doors));
            }
        }
    }
    public synchronized void admit(int actorId) { stages.put(actorId,maps.getFirst()); }
    public synchronized void remove(int actorId) { stages.remove(actorId); pending.remove(actorId); }
    public synchronized boolean atStage(int actorId,int mapId) {
        return !finished.contains(actorId) && java.util.Objects.equals(stages.get(actorId),mapId);
    }
    public synchronized Transition plan(int actorId,int mapId,String portal,int wzTarget,String wzPortal) {
        if (!atStage(actorId,mapId) || pending.containsKey(actorId)) return null;
        int stage = maps.indexOf(mapId);
        int destination;
        String target;
        if (portal.equals("join00") && stage==0) {
            destination=mapId; target=randomOla?"start00":"join01";
        } else if (randomOla && portal.matches("ch\\d{2}") && correctDoorExists(mapId,portal)) {
            destination=correctDoors.get(mapId).contains(portal)
                    ? stage+1<maps.size()?maps.get(stage+1):MapId.EVENT_WINNER : mapId;
            target="start00";
        } else if (!randomOla && portal.equals("in00")) {
            destination=stage+1<maps.size()?maps.get(stage+1):MapId.EVENT_WINNER;
            if (destination!=wzTarget || !"start00".equals(wzPortal)) return null;
            target=wzPortal;
        } else if(wzTarget==mapId && portal.startsWith("np") && wzPortal!=null && !wzPortal.isBlank()) {
            destination=mapId; target=wzPortal;
        } else return null;
        Transition transition=new Transition(actorId,mapId,destination,target,destination==MapId.EVENT_WINNER);
        pending.put(actorId,transition);
        return transition;
    }
    private boolean correctDoorExists(int mapId,String name) {
        int index=Integer.parseInt(name.substring(2));
        int count=switch(maps.indexOf(mapId)) {case 0->5;case 1->8;default->16;};
        return index<count;
    }
    public synchronized boolean commit(Transition transition,int actualMap) {
        if (transition==null || !transition.equals(pending.remove(transition.actorId()))
                || actualMap!=transition.destinationMap()) return false;
        if (transition.finish()) finished.add(transition.actorId());
        else stages.put(transition.actorId(),actualMap);
        return true;
    }
    public synchronized void abandon(Transition transition) {
        if (transition!=null) pending.remove(transition.actorId(),transition);
    }
    public synchronized Set<Integer> finished() { return Set.copyOf(finished); }
}
