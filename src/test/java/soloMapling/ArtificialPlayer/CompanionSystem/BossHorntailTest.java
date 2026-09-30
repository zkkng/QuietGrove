package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import constants.id.MobId;
import org.junit.jupiter.api.Test;
import server.life.*;
import server.maps.MapleMap;
import java.awt.Point;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BossHorntailTest {
    @Test void defeatedPartsAndAggregateBodyCannotConsumeAttacksOrRecreateFightCredit() {
        var stats=new MonsterStats();stats.setHp(100);
        for(int id:new int[]{MobId.HORNTAIL,MobId.DEAD_HORNTAIL_MIN,MobId.DEAD_HORNTAIL_MAX}) {
            var marker=new Monster(id,stats);assertTrue(marker.isEncounterMarker());
            assertFalse(marker.damage(mock(Character.class),1000,false));assertEquals(100,marker.getHp());
            assertFalse(BossRegistry.get("horntail").attackTemplate(id));
        }
        var part=new Monster(MobId.HORNTAIL_HEAD_A,stats);assertFalse(part.isEncounterMarker());
        assertTrue(BossRegistry.get("horntail").attackTemplate(part.getId()));
        assertTrue(BossRegistry.get("horntail").combatTemplate(MobId.HORNTAIL));
    }
    @Test void canonicalIntroCannotDieForRewardsOrDuplicateItsAlreadySpawnedParts() {
        var map=mock(MapleMap.class);
        doCallRealMethod().when(map).spawnHorntailOnGroundBelow(any());
        Map<Integer,Monster> spawned=new HashMap<>();
        try(var factory=mockStatic(LifeFactory.class)) {
            factory.when(()->LifeFactory.getMonster(anyInt())).thenAnswer(call->{
                int id=call.getArgument(0);var stats=new MonsterStats();stats.setHp(100);
                stats.setRevives(List.of(MobId.HORNTAIL_HEAD_A));
                var monster=new Monster(id,stats);spawned.put(id,monster);return monster;
            });
            map.spawnHorntailOnGroundBelow(new Point());
            Monster intro=spawned.get(MobId.SUMMON_HORNTAIL);
            assertTrue(intro.isFake());assertTrue(intro.dropsDisabled());
            assertFalse(intro.damage(mock(Character.class),1000,false));assertEquals(100,intro.getHp());
            assertNull(intro.killBy(mock(Character.class)));
            assertEquals(List.of(MobId.HORNTAIL_HEAD_A),intro.getStats().getRevives());
            for(var monster:spawned.values()) {
                assertEquals(intro.getEncounterId(),monster.getEncounterId());
                assertEquals(MobId.SUMMON_HORNTAIL,monster.getEncounterRootTemplate());
                verify(map,times(1)).spawnMonsterOnGroundBelow(eq(monster),any());
            }
            assertEquals(10,spawned.size()); // Intro, reward body and eight attackable parts.
        }
    }
}
