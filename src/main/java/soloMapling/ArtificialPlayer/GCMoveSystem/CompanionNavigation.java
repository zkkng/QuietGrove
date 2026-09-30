package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import server.maps.MapleMap;
import server.maps.Portal;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionRuntime;

/** Strict ordinary-portal navigation. No GCTravel taxi, no-route, stuck or exception warp fallback. */
public final class CompanionNavigation {
    private CompanionNavigation() {}
    public enum Result { ARRIVED, MOVING, NO_ROUTE }
    public static boolean routeExists(Character source, int mapId) {
        var route = GCWorldGraph.route(source.getMapId(),mapId,30);
        return route != null && !route.isEmpty();
    }
    public static int nextMap(int source,int destination) {
        var route=GCWorldGraph.route(source,destination,30);
        return route==null || route.isEmpty()?-1:route.getFirst();
    }
    public static Result step(Character bot, Character leader) {
        return step(bot,leader,leader.getMapId());
    }
    public static Result step(Character bot, Character leader, int goalId) {
        long started = System.nanoTime();
        try { return routeStep(bot,leader,goalId); }
        finally { if (soloMapling.ArtificialPlayer.CompanionSystem.BossRuntime.get().active(bot))
            soloMapling.ArtificialPlayer.CompanionSystem.BossTelemetry.sample(soloMapling.ArtificialPlayer.CompanionSystem.BossTelemetry.Stage.ROUTE,System.nanoTime()-started); }
    }
    private static Result routeStep(Character bot, Character leader, int goalId) {
        MapleMap goal = goalId == leader.getMapId() ? leader.getMap() : bot.getEventInstance() == null
                ? bot.getMap().getChannelServer().getMapFactory().getMap(goalId) : bot.getEventInstance().getMapInstance(goalId);
        if (goal == null) return Result.NO_ROUTE;
        if (bot.getMap() == goal) return Result.ARRIVED;
        var boss = soloMapling.ArtificialPlayer.CompanionSystem.BossRuntime.get().definition(bot);
        if (boss != null && soloMapling.ArtificialPlayer.CompanionSystem.BossAccess.tick(bot,leader,boss)) return Result.MOVING;
        if (!CompanionRuntime.companionMap(bot) || !CompanionRuntime.companionMap(leader)
                || bot.getMap().getChannelServer() != leader.getMap().getChannelServer()) return Result.NO_ROUTE;
        var route = GCWorldGraph.route(bot.getMapId(), goalId, 20);
        if (route == null || route.isEmpty()) return Result.NO_ROUTE;
        int nextId = route.getFirst();
        boolean boatEdge = boss != null && boss.type() == soloMapling.ArtificialPlayer.CompanionSystem.BossDefinition.Type.BOAT
                && java.util.Set.of(200090000,200090001,200090010,200090011).contains(bot.getMapId())
                && java.util.Set.of(200090000,200090001,200090010,200090011).contains(nextId);
        if (nextId >= 900000000 || !boatEdge && (nextId / 10000 == 20009 || nextId / 10000 == 20008)) return Result.NO_ROUTE;
        Portal source = null;
        for (Portal portal : bot.getMap().getPortals()) {
            if (portal.getTargetMapId() == nextId && portal.getType() != Portal.DOOR_PORTAL
                    && (portal.getScriptName() == null || portal.getScriptName().isEmpty())
                    && portal.getPortalStatus()) {
                source = portal; break;
            }
        }
        if (source == null) return Result.NO_ROUTE;
        var position = bot.getPosition();
        var destination = source.getPosition();
        if (Math.abs(position.x - destination.x) > 35 || Math.abs(position.y - destination.y) > 100) {
            GCMovement.move(bot, destination.x, destination.y);
            return Result.MOVING;
        }
        MapleMap next = bot.getEventInstance() == null ? bot.getMap().getChannelServer().getMapFactory().getMap(nextId)
                : boss != null && boss.maps().contains(nextId) && bot.getEventInstance() == leader.getEventInstance()
                    ? bot.getEventInstance().getMapInstance(nextId) : null;
        Portal arrival = next == null ? null : next.getPortal(source.getTarget());
        if (arrival == null) return Result.NO_ROUTE;
        GCMovement.stop(bot);
        bot.changeMap(next, arrival);
        return bot.getMap() == goal ? Result.ARRIVED : Result.MOVING;
    }
}
