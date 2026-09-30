package server.life;

import net.server.Server;
import org.junit.jupiter.api.Test;
import java.awt.Point;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real SpawnPoint deadlines/listener bookkeeping; no live map/server or actual time waits. */
class SpawnPointTimingTest {
    private void verifyTiming(int mobTime,int animation,long delay) {
        AtomicLong now=new AtomicLong(10000); AtomicReference<MonsterListener> listener=new AtomicReference<>();
        Server instance=mock(Server.class); when(instance.getCurrentTime()).thenAnswer(call->now.get());
        Monster template=mock(Monster.class); when(template.getId()).thenReturn(100100);
        try(var server=mockStatic(Server.class);var life=mockStatic(LifeFactory.class);
            var monsters=mockConstruction(Monster.class,(mob,context)->doAnswer(call->{listener.set(call.getArgument(0));return null;}).when(mob).addListener(any(MonsterListener.class)))) {
            server.when(Server::getInstance).thenReturn(instance); life.when(()->LifeFactory.getMonster(100100)).thenReturn(template);
            SpawnPoint point=new SpawnPoint(template,new Point(10,20),false,mobTime,5000,0);
            assertTrue(point.shouldSpawn()); point.getMonster(); assertEquals(1,point.getSpawned()); assertFalse(point.shouldSpawn());
            now.set(20000); listener.get().monsterKilled(animation); assertEquals(0,point.getSpawned());
            now.set(20000+delay-1); assertFalse(point.shouldSpawn());
            now.incrementAndGet(); assertTrue(point.shouldSpawn());
        }
    }
    @Test void ordinaryMonstersWaitForDeathAnimationAndNextPass() { verifyTiming(0,800,800); }
    @Test void bossTemplateCooldownRemainsSecondsNotGlobalPassTime() { verifyTiming(180,800,180000); }
    @Test void deniedAndNonRespawningPointsRemainExcluded() {
        Server instance=mock(Server.class); when(instance.getCurrentTime()).thenReturn(10000L);
        Monster template=mock(Monster.class);
        try(var server=mockStatic(Server.class)) {
            server.when(Server::getInstance).thenReturn(instance);
            SpawnPoint oneShot=new SpawnPoint(template,new Point(),false,-1,5000,0);
            assertFalse(oneShot.shouldSpawn()); assertFalse(oneShot.shouldForceSpawn());
            SpawnPoint denied=new SpawnPoint(template,new Point(),false,0,5000,0);
            denied.setDenySpawn(true); assertFalse(denied.shouldSpawn());
        }
    }
}
