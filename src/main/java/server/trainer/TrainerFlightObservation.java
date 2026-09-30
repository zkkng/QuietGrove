package server.trainer;

import server.maps.FootholdTree;
import server.maps.Rope;
import server.movement.AbsoluteLifeMovement;
import server.movement.LifeMovementFragment;

import java.awt.Point;
import java.util.List;

/** Conservative visual evidence only. Ordinary supported movement breaks the observation. */
final class TrainerFlightObservation {
    private Object map;
    private Point start;
    private long started, sampled, updated;
    private int minY, maxY, samples;

    static boolean hasUnexplainedAir(FootholdTree terrain, List<Rope> ropes, boolean swimming,
                                     List<LifeMovementFragment> movements) {
        if (swimming || movements == null || movements.isEmpty()) return false;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (LifeMovementFragment fragment : movements) {
            if (!(fragment instanceof AbsoluteLifeMovement movement) || movement.getType() != 0) return false;
            Point at = movement.getPosition();
            if (!hasAirGap(terrain, at)) return false;
            if (ropes.stream().anyMatch(rope -> Math.abs((long) rope.x() - at.x) <= 36
                    && at.y >= rope.topY() - 48 && at.y <= rope.bottomY() + 48)) return false;
            minY = Math.min(minY, at.y);
            maxY = Math.max(maxY, at.y);
        }
        return (long) maxY - minY <= 28;
    }

    static boolean hasAirGap(FootholdTree terrain, Point at) {
        if (terrain == null || at == null) return false;
        // Look ABOVE the feet: querying y+10 skips the very platform being walked on.
        var below = terrain.findBelow(new Point(at.x, at.y - 16));
        if (below == null || below.isWall()) return false; // Missing geometry proves nothing.
        double y = below.getY1() + (double) (at.x - below.getX1())
                * (below.getY2() - below.getY1()) / (below.getX2() - below.getX1());
        return y - at.y >= 140;
    }

    boolean observe(Object scope, Point at, boolean unexplainedAir, long now) {
        if (!unexplainedAir || scope == null || at == null) {
            reset();
            return false;
        }
        if (map != scope || start == null || now < updated || now - updated > 900) {
            begin(scope, at, now);
            return false;
        }
        updated = now;
        // Include every packet in the vertical range, even between timed samples.
        minY = Math.min(minY, at.y);
        maxY = Math.max(maxY, at.y);
        if ((long) maxY - minY > 28) {
            begin(scope, at, now);
            return false;
        }
        if (now - sampled < 150) return false;
        sampled = now;
        samples++;
        if (now - started < 4_000 || samples < 16 || Math.abs((long) at.x - start.x) < 360) return false;
        reset();
        return true;
    }

    private void begin(Object scope, Point at, long now) {
        map = scope;
        start = new Point(at);
        started = sampled = updated = now;
        minY = maxY = at.y;
        samples = 1;
    }

    private void reset() {
        map = null;
        start = null;
    }
}
