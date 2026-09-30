package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BuffStat;
import client.Character;
import server.events.gm.EventBotRuntime;
import server.events.gm.EventMapObstacles;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionIncomingDamage;
import tools.PacketCreator;

import java.awt.Point;
import java.util.concurrent.ThreadLocalRandom;

/** Event map-object damage uses the same HP, Magic Guard and i-frame contract as other incoming hits. */
final class BotEventObstacles {
    private BotEventObstacles() {}
    static void tick(BotMovementState entry, Character bot) {
        if (!EventBotRuntime.physical(bot) || !bot.isAlive()) return;
        long now = System.currentTimeMillis();
        if (entry.obstacleMap != bot.getMap()) {
            entry.obstacleMap = bot.getMap(); entry.obstacleEnteredMs = now;
        }
        if (entry.mobHitCooldownMs > 0) return;
        Point position = bot.getPosition();
        Point previous = entry.lastMobTouchMapId == bot.getMapId() && entry.lastMobTouchCheckPos != null
                ? entry.lastMobTouchCheckPos : position;
        var collision = EventMapObstacles.collision(bot.getMap(), previous, position, now - entry.obstacleEnteredMs);
        if (collision == null) return;
        int damage = CompanionIncomingDamage.apply(bot, collision.damage(), false);
        if (damage < 0) return;
        int direction = collision.originX() > position.x ? 0 : 1;
        bot.getMap().broadcastMessage(bot, PacketCreator.damagePlayer(-4, 0, bot.getId(), damage,
                0, direction, false, 0, false, 0, 0, 0), false);
        entry.mobHitCooldownMs = BotMovementManager.delayAfterCurrentTick(1500);
        BotContactDamage.markAlerted(entry);
        if (damage == 0 || !bot.isAlive()) return;
        Integer stance = bot.getBuffedValue(BuffStat.STANCE);
        if (stance != null && ThreadLocalRandom.current().nextInt(100) < stance) return;
        entry.attackCooldownMs = 0;
        entry.resting = false;
        BotMovementManager.clearNavigationState(entry);
        entry.movementBroadcastValid = false;
        int horizontal = Math.round((direction == 0 ? -1.5f : 1.5f) * BotPhysicsEngine.cfg.TICK_MS / 8f);
        if (entry.inAir) BotPhysicsEngine.applyAirKnockback(entry, bot, horizontal);
        else BotPhysicsEngine.beginKnockback(entry, bot, position, -3.5f * BotPhysicsEngine.cfg.TICK_MS / 8f, horizontal);
        BotMovementManager.broadcastMovement(entry);
    }
}
