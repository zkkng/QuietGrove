package server.statistics;

import client.Character;
import server.life.Monster;
import server.maps.MapleMap;

/** Captures a combat death inside the synchronized disposal boundary, before HP becomes -1. */
public final class MonsterDeathTracking {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    public record Scope(Object monster, Object killer, int mapId, Scope previous) {}

    public static Scope enter(Object map, Object monster, Object killer) {
        Scope scope = new Scope(monster, killer, ((MapleMap) map).getId(), CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    public static void exit(Scope scope) {
        if (scope.previous() == null) CURRENT.remove();
        else CURRENT.set(scope.previous());
    }

    public static Scope beforeDispose(Object object) {
        Scope scope = CURRENT.get();
        Monster monster = (Monster) object;
        return scope != null && scope.monster() == monster && scope.killer() instanceof Character
                && monster.getHp() == 0 && !monster.isEncounterMarker() ? scope : null;
    }

    public static void afterDispose(Object object, Scope scope) {
        Monster monster = (Monster) object;
        if (scope != null && monster.getHp() == -1) {
            WorldStatistics.offer(2, (Character) scope.killer(), monster.getId(), scope.mapId(), 0, 0, 1);
        }
    }
}
