package soloMapling.ArtificialPlayer.HybridPilot;

import client.Character;
import client.Client;
import client.inventory.WeaponType;
import client.status.MonsterStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionCombat;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionIncomingDamage;
import soloMapling.ArtificialPlayer.GCMoveSystem.BotMobHitboxProvider;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HybridPilotEffectsTest {
    final Character body = mock(Character.class), human = mock(Character.class);
    final MapleMap map = mock(MapleMap.class);
    final Monster mob = mock(Monster.class);
    final MonsterStats stats = mock(MonsterStats.class);
    final HybridPilotEffects effects = new HybridPilotEffects();
    @BeforeEach void setup() {
        when(body.getMap()).thenReturn(map); when(body.isAlive()).thenReturn(true);
        when(body.getPosition()).thenReturn(new Point(0, 0)); when(body.getHp()).thenReturn(100);
        when(human.getMap()).thenReturn(map); when(human.getClient()).thenReturn(mock(Client.class));
        when(map.getCharacters()).thenReturn(List.of(human)); when(map.getAllMonsters()).thenReturn(List.of(mob));
        when(mob.getMap()).thenReturn(map); when(mob.isAlive()).thenReturn(true);
        when(mob.getStats()).thenReturn(stats); when(mob.getPosition()).thenReturn(new Point(20, 0));
        when(mob.getObjectId()).thenReturn(7);
    }
    @Test void rejectsDeadFriendlyFakeBossIncidentAndOtherMapTargets() {
        assertTrue(HybridPilotEffects.targetAllowed(mob, map));
        when(mob.isAlive()).thenReturn(false); assertFalse(HybridPilotEffects.targetAllowed(mob, map));
        when(mob.isAlive()).thenReturn(true); when(mob.isFake()).thenReturn(true);
        assertFalse(HybridPilotEffects.targetAllowed(mob, map)); when(mob.isFake()).thenReturn(false);
        when(mob.isBoss()).thenReturn(true); assertFalse(HybridPilotEffects.targetAllowed(mob, map));
        when(mob.isBoss()).thenReturn(false); when(stats.isFriendly()).thenReturn(true);
        assertFalse(HybridPilotEffects.targetAllowed(mob, map)); when(stats.isFriendly()).thenReturn(false);
        when(mob.getIncidentOwner()).thenReturn("event"); assertFalse(HybridPilotEffects.targetAllowed(mob, map));
        when(mob.getIncidentOwner()).thenReturn(null); when(mob.getMap()).thenReturn(mock(MapleMap.class));
        assertFalse(HybridPilotEffects.targetAllowed(mob, map));
    }
    @Test void contactDamageChangesRealHpAndCannotSubtractMoreThanRemainingHp() {
        try (var geometry = mockStatic(BotMobHitboxProvider.class); var damage = mockStatic(CompanionIncomingDamage.class)) {
            geometry.when(() -> BotMobHitboxProvider.getMobBounds(mob)).thenReturn(new Rectangle(-5, -25, 25, 25));
            damage.when(() -> CompanionIncomingDamage.physical(body, mob)).thenReturn(300);
            assertTrue(effects.contact(body, map)); verify(body).addHP(-100);
        }
    }
    @Test void distantOrUnresolvedBodyCannotHitOrBeHit() {
        try (var geometry = mockStatic(BotMobHitboxProvider.class)) {
            assertFalse(effects.attack(body, map)); assertFalse(effects.contact(body, map));
            geometry.when(() -> BotMobHitboxProvider.getMobBounds(mob)).thenReturn(new Rectangle(1000, -25, 25, 25));
            assertFalse(effects.attack(body, map)); assertFalse(effects.contact(body, map));
            verify(body, never()).addHP(anyInt()); verify(map, never()).damageMonster(any(), any(), anyInt());
        }
    }
    @Test void meleeUsesCanonicalDamageOnceAndNoResourceFreeSkillPacket() {
        try (var geometry = mockStatic(BotMobHitboxProvider.class); var attack = mockStatic(BotAttack.class);
             var combat = mockStatic(CompanionCombat.class)) {
            geometry.when(() -> BotMobHitboxProvider.getMobBounds(mob)).thenReturn(new Rectangle(15, -25, 25, 25));
            attack.when(() -> BotAttack.resolveEquippedWeaponType(body)).thenReturn(WeaponType.SWORD1H);
            combat.when(() -> CompanionCombat.hitChance(body, mob, false)).thenReturn(1.0);
            when(body.calculateMaxBaseDamage(anyInt())).thenReturn(100);
            assertTrue(effects.attack(body, map));
            verify(map, times(1)).damageMonster(eq(body), eq(mob), intThat(d -> d >= 60 && d <= 100));
            verify(map, times(1)).broadcastMessage(eq(body), any(net.packet.Packet.class), eq(false));
        }
    }
    @Test void protectionAndUnsupportedWeaponsBlockAttack() {
        try (var geometry = mockStatic(BotMobHitboxProvider.class); var attack = mockStatic(BotAttack.class)) {
            geometry.when(() -> BotMobHitboxProvider.getMobBounds(mob)).thenReturn(new Rectangle(15, -25, 25, 25));
            for (var protection : new MonsterStatus[]{MonsterStatus.WEAPON_REFLECT, MonsterStatus.WEAPON_IMMUNITY, MonsterStatus.HARD_SKIN}) {
                when(mob.isBuffed(protection)).thenReturn(true); assertFalse(effects.attack(body, map));
                when(mob.isBuffed(protection)).thenReturn(false);
            }
            attack.when(() -> BotAttack.resolveEquippedWeaponType(body)).thenReturn(WeaponType.BOW);
            assertFalse(effects.attack(body, map)); verify(map, never()).damageMonster(any(), any(), anyInt());
        }
    }
    @Test void observerLeavingBeforeEffectPreventsHpAndMonsterMutation() {
        try (var geometry = mockStatic(BotMobHitboxProvider.class)) {
            geometry.when(() -> BotMobHitboxProvider.getMobBounds(mob)).thenReturn(new Rectangle(-5, -25, 25, 25));
            when(map.getCharacters()).thenReturn(List.of());
            assertFalse(effects.attack(body, map)); assertFalse(effects.contact(body, map));
            verify(body, never()).addHP(anyInt()); verify(map, never()).damageMonster(any(), any(), anyInt());
        }
    }
}
