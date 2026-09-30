package server.events.gm;

import client.Character;
import org.junit.jupiter.api.Test;
import server.TimerManager;
import server.maps.MapleMap;
import java.awt.Point;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OxQuizTest {
    @Test void validWzKeysIncludingLastGroupAndCorrectRegionRules() {
        var catalog=OxQuiz.loadQuestions(); assertTrue(catalog.size()>=10);
        assertTrue(catalog.stream().allMatch(q->q.group()>0 && q.key()>0 && (q.answer()==0 || q.answer()==1)));
        assertTrue(catalog.stream().anyMatch(q->q.group()==9));
        assertTrue(OxQuiz.correct(0,0,0)); assertTrue(OxQuiz.correct(-500,0,1));
        assertFalse(OxQuiz.correct(-234,0,0)); assertFalse(OxQuiz.correct(-234,0,1));
        assertFalse(OxQuiz.correct(0,-100,0)); assertFalse(OxQuiz.correct(0,0,1));
    }
    @Test void finiteRoundsWithOneSurvivorDoNotEndEarlyAndIgnoreUnregisteredPresence() {
        var map=mock(MapleMap.class); var entrant=mock(Character.class); var outsider=mock(Character.class);
        when(entrant.getId()).thenReturn(1); when(entrant.getMap()).thenReturn(map); when(entrant.isAlive()).thenReturn(true);
        when(outsider.getId()).thenReturn(2); when(outsider.getMap()).thenReturn(map);
        when(map.getCharacters()).thenReturn(List.of(entrant,outsider));
        var manager=mock(TimerManager.class); var future=mock(ScheduledFuture.class); var callbacks=new ArrayDeque<Runnable>();
        when(manager.schedule(any(Runnable.class),anyLong())).thenAnswer(invocation->{ callbacks.add(invocation.getArgument(0)); return future; });
        var finished=new AtomicInteger();
        var game=new OxQuiz(map,List.of(new OxQuiz.Question(1,1,0),new OxQuiz.Question(1,2,1)),2,42,
                ()->true,id->id==1,id->fail("survivor should not be eliminated"),finished::incrementAndGet);
        when(map.getOx()).thenReturn(game);
        try(var timer=mockStatic(TimerManager.class)) {
            timer.when(TimerManager::getInstance).thenReturn(manager);
            game.sendQuestion();
            for(var question:game.selectedQuestions()) {
                when(entrant.getPosition()).thenReturn(new Point(question.answer()==0 ? 0 : -500,0));
                Runnable resolve=callbacks.remove();resolve.run();resolve.run();
            }
            assertEquals(2,game.completedRounds()); assertEquals(1,finished.get()); assertTrue(callbacks.isEmpty());
            game.sendQuestion(); assertEquals(1,finished.get());
            verify(entrant,times(2)).gainExp(200,true,true); verify(outsider,never()).gainExp(anyInt(),anyBoolean(),anyBoolean());
        }
    }
    @Test void cancelledAndReplacedGenerationCallbacksApplyNoLateExpOrResults() {
        var map=mock(MapleMap.class); var manager=mock(TimerManager.class); var future=mock(ScheduledFuture.class);
        var callbacks=new ArrayDeque<Runnable>(); var valid=new AtomicBoolean(true); var results=new AtomicInteger();
        when(manager.schedule(any(Runnable.class),anyLong())).thenAnswer(i->{callbacks.add(i.getArgument(0));return future;});
        var game=new OxQuiz(map,List.of(new OxQuiz.Question(1,1,0)),1,1,valid::get,id->true,id->results.incrementAndGet(),results::incrementAndGet);
        when(map.getOx()).thenReturn(game);
        try(var timer=mockStatic(TimerManager.class)) {
            timer.when(TimerManager::getInstance).thenReturn(manager); game.sendQuestion();
            valid.set(false); game.cancel(); callbacks.remove().run();
            verify(future).cancel(false); assertEquals(0,results.get()); assertEquals(0,game.completedRounds());
        }
    }
}
