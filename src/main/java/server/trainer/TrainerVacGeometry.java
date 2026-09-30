package server.trainer;

import java.awt.Point;
import java.awt.Rectangle;

/** Ground probe positions kept in front of the player, with a visible gap. */
final class TrainerVacGeometry {
    private TrainerVacGeometry() {}

    static Point target(Point actor, int facing, int index) {
        int column = index % 5;
        int row = (index / 5) % 3;
        int distance = 84 + column * 17;
        return new Point(actor.x + (facing < 0 ? -distance : distance), actor.y - 20 - row * 3);
    }
    static Point target(Point actor, int facing, int index, Rectangle area, String mode) {
        if ("front".equals(mode)) return target(actor, facing, index);
        int column = index % 5;
        int row = (index / 5) % 3;
        int offset = 84 + column * 17;
        int x = "left wall".equals(mode) ? area.x + offset : area.x + area.width - offset;
        return new Point(x, actor.y - 20 - row * 3);
    }
}
