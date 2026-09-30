package server.events.gm;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.maps.MapleMap;
import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TeamEventTest {
    private static final class Timers implements AutoCloseable {
        final ArrayDeque<Runnable> callbacks=new ArrayDeque<>();
        final MockedStatic<TimerManager> mock;
        Timers() {
            var manager=mock(TimerManager.class); var future=mock(ScheduledFuture.class);
            when(manager.schedule(any(Runnable.class),anyLong())).thenAnswer(i->{callbacks.add(i.getArgument(0)); return future;});
            when(manager.register(any(Runnable.class),anyLong(),anyLong())).thenReturn(future);
            mock=mockStatic(TimerManager.class); mock.when(TimerManager::getInstance).thenReturn(manager);
        }
        @Override public void close() { mock.close(); }
    }
    @Test void actualCoconutGeometryRejectsBadIdsReachCadenceAndScoresEachTargetOnce() {
        try(var timers=new Timers()) {
            var game=new CoconutGame(mock(MapleMap.class),()->true,id->true,team->{},73); game.start();
            assertEquals(501,game.coconutCount()); assertNull(game.position(0)); assertNull(game.position(506));
            Point target=game.position(1);
            assertNull(game.apply(1,2,target,1,1000)); assertNull(game.apply(1,0,target,-1,1000));
            assertNull(game.apply(1,0,new Point(90000,90000),1,1000));
            assertNotNull(game.apply(1,0,target,1,1000)); assertNull(game.apply(1,0,target,1,1001));
            for(int hit=1;hit<5;hit++) assertNotNull(game.apply(1,0,target,1,1000+hit*750));
            assertNull(game.apply(2,1,target,1,10000));
            long now=20_000;
            for(int id=2;id<=501;id++) for(int hit=0;hit<5;hit++) {
                assertNotNull(game.apply(id,id%2,game.position(id),id,now)); now+=750;
            }
            assertEquals(401,game.getMapleScore()+game.getStoryScore());
            game.cancel(); assertNull(game.apply(1000,0,target,1,now));
        }
    }
    @Test void simultaneousLastHitsCommitOnlyOneOutcome() throws Exception {
        try(var timers=new Timers()) {
            var game=new CoconutGame(mock(MapleMap.class),()->true,id->true,team->{},2); game.start();
            Point point=game.position(1);
            for(int hit=0;hit<4;hit++) game.apply(1,0,point,1,1000+hit*750);
            var start=new CountDownLatch(1);
            try(var pool=Executors.newFixedThreadPool(10)) {
                List<Future<CoconutGame.Hit>> results=new ArrayList<>();
                for(int id=2;id<=101;id++) {int actor=id; results.add(pool.submit(()->{start.await();return game.apply(actor,actor%2,point,1,5000);}));}
                start.countDown(); int accepted=0;
                for(var result:results) if(result.get()!=null) accepted++;
                assertEquals(1,accepted); assertTrue(game.getMapleScore()+game.getStoryScore()<=1);
            }
        }
    }
    @Test void coconutTieGetsOneOvertimeAndOldDeadlineCannotFinishAfterCancel() {
        try(var timers=new Timers()) {
            AtomicInteger finishes=new AtomicInteger(); AtomicInteger winner=new AtomicInteger(99);
            var game=new CoconutGame(mock(MapleMap.class),()->true,id->true,team->{winner.set(team);finishes.incrementAndGet();},1);
            game.start(); Runnable first=timers.callbacks.remove(); first.run(); first.run(); assertEquals(0,finishes.get());
            Runnable overtime=timers.callbacks.remove(); overtime.run(); overtime.run();
            assertEquals(1,finishes.get()); assertEquals(-1,winner.get());
            var cancelled=new CoconutGame(mock(MapleMap.class),()->true,id->true,team->finishes.incrementAndGet(),2);
            cancelled.start(); Runnable stale=timers.callbacks.remove(); cancelled.cancel(); stale.run();
            assertEquals(1,finishes.get());
        }
    }
    @Test void snowballBothTeamsPushAndOwnLaneSnowmanStopsOpposingBallUntilRecovery() {
        try(var timers=new Timers()) {
            var game=new SnowballGame(mock(MapleMap.class),()->true,id->true,team->{},1); game.start();
            assertTrue(game.team(0).isHittable()); assertTrue(game.team(1).isHittable());
            assertNull(game.apply(1,0,new Point(400,155),1,1000));
            assertNull(game.apply(1,0,new Point(9000,155),0,1000));
            assertNotNull(game.apply(1,0,new Point(400,155),0,1000));
            assertNull(game.apply(1,0,new Point(400,155),0,1001));
            game.apply(1,0,new Point(400,155),0,1500); game.apply(1,0,new Point(400,155),0,2000);
            assertEquals(1,game.team(0).getPosition());
            long now=3000;
            while(game.team(0).getSnowmanHP()>0) {assertNotNull(game.apply(1,0,new Point(-440,155),2,now));now+=500;}
            assertNull(game.apply(2,1,new Point(400,-84),1,now));
            assertNotNull(game.apply(1,0,new Point(403,155),0,now));
            assertFalse(game.recoverTeam(0,now)); assertTrue(game.recoverTeam(0,now+10000));
            assertNotNull(game.apply(2,1,new Point(400,-84),1,now+10000));
            game.cancel(); assertFalse(game.team(0).isHittable()); assertFalse(game.team(1).isHittable());
            assertNull(game.apply(2,1,new Point(400,-84),1,now+11000));
        }
    }
    @Test void snowballTimeoutReportsActualWinnerAndDrawExactlyOnce() {
        try(var timers=new Timers()) {
            AtomicInteger winner=new AtomicInteger(99),finishes=new AtomicInteger();
            var game=new SnowballGame(mock(MapleMap.class),()->true,id->true,team->{winner.set(team);finishes.incrementAndGet();},1);
            game.start(); for(int hit=0;hit<3;hit++) game.apply(1,1,new Point(400,-84),1,1000+hit*500);
            Runnable timeout=timers.callbacks.remove(); timeout.run(); timeout.run();
            assertEquals(0,finishes.get(),"the result must remain visible before the return warp");
            Runnable result=timers.callbacks.remove(); result.run(); result.run();
            assertEquals(1,winner.get()); assertEquals(1,finishes.get());
            var draw=new SnowballGame(mock(MapleMap.class),()->true,id->true,winner::set,2);
            draw.start(); timers.callbacks.remove().run(); timers.callbacks.remove().run(); assertEquals(-1,winner.get());
            var cancelled=new SnowballGame(mock(MapleMap.class),()->true,id->true,team->finishes.incrementAndGet(),3);
            cancelled.start(); timers.callbacks.remove().run();
            Runnable stale=timers.callbacks.remove(); cancelled.cancel(); stale.run();
            assertEquals(1,finishes.get(),"a cancelled result must never settle the next event");
        }
    }
    @Test void snowballCrossingBetweenSamplesStillTouchesActualWzBody() {
        var body=new SnowballGeometry();
        Point ball=new Point(400,155),before=new Point(250,155),after=new Point(550,155);
        assertFalse(body.touches(ball,before,before));
        assertFalse(body.touches(ball,after,after));
        assertTrue(body.touches(ball,before,after));
    }
}
