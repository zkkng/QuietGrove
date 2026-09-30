package soloMapling.ArtificialPlayer.CompanionSystem;

import server.life.Monster;
import soloMapling.ArtificialPlayer.GCMoveSystem.BotMobHitboxProvider;
import java.awt.Point;
import java.awt.Rectangle;

/** Shared body geometry for range, facing and engagement; multipart anchors are not hitboxes. */
final class BossGeometry {
    private BossGeometry() {}

    static Rectangle bounds(Monster target) {
        Rectangle body = BotMobHitboxProvider.getMobBounds(target);
        // Ordinary unresolved species retain the legacy point behavior. An enabled boss must
        // have real WZ geometry before the companion can claim that an attack reaches it.
        return body != null ? body : target.isBoss() ? null
                : new Rectangle(target.getPosition().x, target.getPosition().y, 1, 1);
    }

    static Point aim(Monster target) {
        Rectangle body = bounds(target);
        return body == null ? target.getPosition()
                : new Point(body.x + body.width / 2, body.y + body.height / 2);
    }

    static double distanceSq(Monster target, Point point) {
        Rectangle body = bounds(target);
        if (body == null) return target.getPosition().distanceSq(point);
        double dx = Math.max(0, Math.max(body.x - point.x, point.x - (body.x + body.width)));
        double dy = Math.max(0, Math.max(body.y - point.y, point.y - (body.y + body.height)));
        return dx * dx + dy * dy;
    }

    static boolean withinLeash(Monster target, Point leader, int x, int y) {
        Rectangle body = bounds(target);
        return body == null ? Math.abs(target.getPosition().x-leader.x)<=x
                && Math.abs(target.getPosition().y-leader.y)<=y
                : body.intersects(new Rectangle(leader.x-x,leader.y-y,2*x,2*y));
    }

    static boolean reaches(Rectangle attack, Monster target, Point attacker, int verticalReach) {
        Rectangle body = bounds(target);
        return !target.isEncounterMarker() && body != null && attack.intersects(body)
                && body.y < attacker.y + verticalReach
                && body.y + body.height > attacker.y - verticalReach;
    }
}
