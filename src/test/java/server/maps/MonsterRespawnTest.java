package server.maps;

import client.Character;
import config.YamlConfig;
import net.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import server.life.Monster;
import server.life.SpawnPoint;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Executes the real map respawn loop, replacing only spawnpoint/I/O dependencies. */
class MonsterRespawnTest {
    private boolean previousFullRespawn;
    @BeforeEach void setup() { previousFullRespawn=YamlConfig.config.server.USE_ENABLE_FULL_RESPAWN; YamlConfig.config.server.USE_ENABLE_FULL_RESPAWN=false; }
    @AfterEach void restore() { YamlConfig.config.server.USE_ENABLE_FULL_RESPAWN=previousFullRespawn; }

    private static Object field(MapleMap map,String name) throws Exception {
        Field field=MapleMap.class.getDeclaredField(name); field.setAccessible(true); return field.get(map);
    }
    private static final class Fixture {
        final MapleMap map;
        final AtomicInteger live;
        final List<AtomicBoolean> occupied=new ArrayList<>();
        final List<SpawnPoint> points=new ArrayList<>();
        @SuppressWarnings("unchecked") Fixture(int spawnPoints,int occupants,int initialLive,float wzMobRate) throws Exception {
            try(var server=mockStatic(Server.class)) {
                server.when(Server::getInstance).thenReturn(mock(Server.class));
                map=spy(new MapleMap(100000001,0,1,100000000,wzMobRate));
            }
            live=(AtomicInteger)field(map,"spawnedMonstersOnMap"); live.set(initialLive);
            Collection<Character> characters=(Collection<Character>)field(map,"characters");
            for(int i=0;i<occupants;i++) { Character actor=mock(Character.class); when(actor.getId()).thenReturn(i%2==0?i+1:20001+i); characters.add(actor); }
            Collection<SpawnPoint> spawns=(Collection<SpawnPoint>)field(map,"monsterSpawn");
            for(int i=0;i<spawnPoints;i++) {
                AtomicBoolean used=new AtomicBoolean(i<initialLive); SpawnPoint point=mock(SpawnPoint.class);
                when(point.shouldSpawn()).thenAnswer(call->!used.get());
                when(point.getMonster()).thenAnswer(call->{ used.set(true); return mock(Monster.class); });
                occupied.add(used); points.add(point); spawns.add(point);
            }
            doAnswer(call->{live.incrementAndGet();return null;}).when(map).spawnMonster(any(Monster.class));
        }
    }

    @ParameterizedTest @CsvSource({"1,1,1","2,1,2","7,1,6","20,1,15","20,2,16","20,3,17","20,4,18","20,5,19","20,6,20","20,30,20","1000,1,750"})
    void upstreamPopulationTargetIsBoundedByStaticPoints(int points,int actors,int expected) throws Exception {
        Fixture fixture=new Fixture(points,actors,0,1);
        fixture.map.respawn(); assertEquals(expected,fixture.live.get());
        fixture.map.respawn(); assertEquals(expected,fixture.live.get(),"Repeated passes must not add above the stock target");
    }
    @Test void sevenInitialMonstersWithOneSurvivorRefillToSixNotThree() throws Exception {
        Fixture fixture=new Fixture(7,1,7,1);
        fixture.live.set(1); for(int i=1;i<7;i++) fixture.occupied.get(i).set(false);
        fixture.map.respawn(); assertEquals(6,fixture.live.get());
        verify(fixture.map,times(5)).spawnMonster(any(Monster.class));
    }
    @Test void noPopulationDoesNotRespawnAndDisabledSummonsRemainDisabled() throws Exception {
        Fixture empty=new Fixture(20,0,0,1); empty.map.respawn(); assertEquals(0,empty.live.get());
        Fixture disabled=new Fixture(20,1,0,1); disabled.map.allowSummonState(false); disabled.map.respawn(); assertEquals(0,disabled.live.get());
    }
    @Test void deniedCooldownAndOneShotPointsAreStillAuthoritative() throws Exception {
        Fixture fixture=new Fixture(7,6,0,1);
        for(int i=0;i<3;i++) when(fixture.points.get(i).shouldSpawn()).thenReturn(false);
        fixture.map.respawn(); assertEquals(4,fixture.live.get());
        for(int i=0;i<3;i++) verify(fixture.points.get(i),never()).getMonster();
    }
    @Test void existingGlobalCountIncludingSummonsIsNotOverriddenOrWiped() throws Exception {
        Fixture fixture=new Fixture(7,1,0,1); fixture.live.set(8);
        fixture.map.respawn(); assertEquals(8,fixture.live.get()); verify(fixture.map,never()).spawnMonster(any(Monster.class));
    }
    @Test void fullRespawnIsStillAnExplicitSeparateOptInAndWzRateIsNotAnInventedMultiplier() throws Exception {
        Fixture ordinary=new Fixture(20,1,0,2); ordinary.map.respawn(); assertEquals(15,ordinary.live.get());
        YamlConfig.config.server.USE_ENABLE_FULL_RESPAWN=true;
        Fixture full=new Fixture(20,1,0,1); full.map.respawn(); assertEquals(20,full.live.get());
    }
    @Test void stockCadenceAndNonFullPolicyAreConfigured() throws Exception {
        String config=java.nio.file.Files.readString(java.nio.file.Path.of("config.yaml"));
        assertTrue(config.matches("(?s).*\\n\\s+RESPAWN_INTERVAL: 10000\\s+#[^\\n]*.*"));
        assertTrue(config.matches("(?s).*\\n\\s+USE_ENABLE_FULL_RESPAWN: false\\s+#[^\\n]*.*"));
    }
}
