package server.content;

import client.Character;
import client.QuestStatus;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.life.Monster;
import server.maps.MapleMap;
import server.partyquest.Pyramid;
import server.quest.Quest;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SurvivalIntegrationTest {
    private void field(Object o,String name,Object value) throws Exception {var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private Object get(Object o,String name) throws Exception {var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private Pyramid run(Character chr,MapleMap map,boolean bonus) throws Exception {
        var constructor=Pyramid.class.getDeclaredConstructor(List.class,int.class,boolean.class,boolean.class,EventInstanceManager.class);constructor.setAccessible(true);
        var run=constructor.newInstance(List.of(chr),0,true,bonus,mock(EventInstanceManager.class));field(run,"current",map);
        when(chr.getId()).thenReturn(0);when(chr.getPartyQuest()).thenReturn(run);when(chr.getMap()).thenReturn(map);
        return run;
    }
    @Test void onlyActualRunParticipantsEarnPersistentHuntingCredit() throws Exception {
        Character chr=mock(Character.class);var state=new ContentState();when(chr.getContentState()).thenReturn(state);
        var qs=mock(QuestStatus.class);when(qs.getStatus()).thenReturn(QuestStatus.Status.STARTED);when(chr.getQuest(any(Quest.class))).thenReturn(qs);
        MapleMap map=mock(MapleMap.class);Pyramid run=run(chr,map,false);Monster mob=mock(Monster.class,RETURNS_DEEP_STUBS);
        when(mob.getMap()).thenReturn(map);when(mob.getStats().getCool()).thenReturn(null);
        run.killed(chr,mob,100);assertEquals(1,state.get("survival.subway.kills"));verify(chr).setQuestProgress(29931,7662,"1");
        when(mob.getMap()).thenReturn(mock(MapleMap.class));run.killed(chr,mob,100);assertEquals(1,state.get("survival.subway.kills"));
    }
    @Test void yetiAndMissesLowerGaugeWithoutAwardingKills() throws Exception {
        Character chr=mock(Character.class);when(chr.getContentState()).thenReturn(new ContentState());MapleMap map=mock(MapleMap.class);Pyramid run=run(chr,map,false);
        Monster mob=mock(Monster.class);when(mob.getMap()).thenReturn(map);when(mob.getId()).thenReturn(9700038);
        assertTrue(run.blockAttack(chr,mob,99999));assertEquals(96,get(run,"gauge"));
        assertEquals(0,chr.getContentState().get("survival.subway.kills"));
        when(mob.getId()).thenReturn(9700000);assertTrue(run.blockAttack(chr,mob,0));assertFalse(run.blockAttack(chr,mob,1));
    }
    @Test void resultPacketReplayCannotDuplicateExperienceOrPass() throws Exception {
        Character chr=mock(Character.class);var state=new ContentState();when(chr.getContentState()).thenReturn(state);
        var run=run(chr,mock(MapleMap.class),false);((AtomicBoolean)get(run,"closed")).set(true);field(run,"cleared",true);
        run.sendScore(chr);run.sendScore(chr);assertEquals(1,state.get("survival.ticket.4001321"));verify(chr,times(1)).gainExp(anyInt(),eq(true),eq(true));
    }
    @Test void activeAndBonusRunsCannotClaimSurvivalResults() throws Exception {
        Character chr=mock(Character.class);when(chr.getContentState()).thenReturn(new ContentState());
        run(chr,mock(MapleMap.class),false).sendScore(chr);
        var bonus=run(chr,mock(MapleMap.class),true);((AtomicBoolean)get(bonus,"closed")).set(true);bonus.sendScore(chr);
        verify(chr,never()).gainExp(anyInt(),anyBoolean(),anyBoolean());assertTrue(chr.getContentState().snapshot().isEmpty());
    }
    @Test void entryRejectsWrongMapAndInsufficientLevelBeforeAllocatingAnInstance(){
        Character chr=mock(Character.class);
        assertFalse(Pyramid.enter(chr,0,true,false,false).isEmpty());
        when(chr.getMapId()).thenReturn(910320000);when(chr.getLevel()).thenReturn(24);
        assertFalse(Pyramid.enter(chr,0,true,false,false).isEmpty());verify(chr,never()).getClient();
    }
}
