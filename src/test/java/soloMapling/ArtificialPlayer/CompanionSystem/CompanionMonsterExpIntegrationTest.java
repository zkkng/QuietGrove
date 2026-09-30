package soloMapling.ArtificialPlayer.CompanionSystem;

import client.BuffStat;
import client.Character;
import config.YamlConfig;
import net.server.world.Party;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;
import java.util.*;
import static org.mockito.Mockito.*;

/** Exercise the actual Monster sharing and final modifier/rounding path, not just policy math. */
class CompanionMonsterExpIntegrationTest {
    private final CompanionTaskService tasks = new CompanionTaskService(System::currentTimeMillis,30);
    private final Party party = mock(Party.class);
    private final MapleMap map = mock(MapleMap.class);
    private Character member(int id, boolean companion, CompanionTaskService.State state) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id); when(c.getLevel()).thenReturn(80);
        when(c.isAlive()).thenReturn(true); when(c.isLoggedinWorld()).thenReturn(!companion);
        when(c.getParty()).thenReturn(party); when(c.getMap()).thenReturn(map);
        when(c.getPosition()).thenReturn(new java.awt.Point()); when(c.getExpRate()).thenReturn(2);
        if (companion) {
            var r = tasks.reserve(id,id,new CompanionTaskService.PartyKey(0,7),1,1,1,
                    System.currentTimeMillis()+15000,new CompanionTaskService.PriorActivity("TRAINING_BOT",100,101)).orElseThrow();
            var t = tasks.commit(r,()->true).orElseThrow(); tasks.transition(id,t.generation(),1,state);
        }
        return c;
    }
    private void distribute(List<Character> members, Map<Character,Long> damage) throws Exception {
        for (Character c : members) when(c.getPartyMembersOnSameMap()).thenReturn(members);
        MonsterStats stats = new MonsterStats(); stats.hp=1000; stats.level=80;
        Monster monster = new Monster(100100,stats);
        var method = Monster.class.getDeclaredMethod("distributePartyExperience", Map.class,float.class,
                Set.class,Map.class,double.class);
        method.setAccessible(true);
        method.invoke(monster,damage,1f,new HashSet<Character>(),Map.of(),1.0);
    }
    @Test void actualSixMemberPathAppliesHsRatesAndSeparateRoundingExactlyOnce() throws Exception {
        boolean oldRange=YamlConfig.config.server.USE_ENFORCE_MOB_LEVEL_RANGE;
        boolean oldHs=YamlConfig.config.server.USE_FULL_HOLY_SYMBOL;
        float oldBonus=YamlConfig.config.server.PARTY_BONUS_EXP_RATE;
        try (var leases=mockStatic(CompanionTaskService.class); var runtime=mockStatic(CompanionRuntime.class);
             var cafe=mockStatic(server.pccafe.PcCafe.class); var family=mockStatic(server.content.FamilyBenefits.class);
             var egg=mockStatic(server.content.MarketEgg.class); var medals=mockStatic(server.content.Medals.class)) {
            leases.when(CompanionTaskService::shared).thenReturn(tasks);
            runtime.when(()->CompanionRuntime.active(any())).thenAnswer(call->tasks.task(((Character)call.getArgument(0)).getId()).isPresent());
            cafe.when(()->server.pccafe.PcCafe.expMultiplier(any())).thenReturn(1.25);
            family.when(()->server.content.FamilyBenefits.expMultiplier(any())).thenReturn(1.2);
            YamlConfig.config.server.USE_ENFORCE_MOB_LEVEL_RANGE=false;
            YamlConfig.config.server.USE_FULL_HOLY_SYMBOL=false;
            YamlConfig.config.server.PARTY_BONUS_EXP_RATE=.75f;
            Character human=member(1,false,null); List<Character> members=new ArrayList<>(List.of(human));
            Map<Character,Long> damage=new LinkedHashMap<>(); damage.put(human,500L);
            when(human.getBuffedValue(BuffStat.HOLY_SYMBOL)).thenReturn(50);
            when(human.getBuffedValue(BuffStat.EXP_INCREASE)).thenReturn(7);
            for(int i=0;i<5;i++) {Character c=member(21001+i,true,CompanionTaskService.State.ENGAGE); members.add(c); damage.put(c,100L);}
            distribute(members,damage);
            verify(human).gainExp(1507,731,true,false,false);
            for(Character c:members.subList(1,6)) verify(c).gainExp(400,195,false,false,false);
        } finally {
            YamlConfig.config.server.USE_ENFORCE_MOB_LEVEL_RANGE=oldRange;
            YamlConfig.config.server.USE_FULL_HOLY_SYMBOL=oldHs;
            YamlConfig.config.server.PARTY_BONUS_EXP_RATE=oldBonus;
        }
    }
    @Test void fiveFollowBotsNeverDiluteHumanOrEnableFullHolySymbol() throws Exception {
        boolean oldRange=YamlConfig.config.server.USE_ENFORCE_MOB_LEVEL_RANGE;
        boolean oldHs=YamlConfig.config.server.USE_FULL_HOLY_SYMBOL;
        try(var leases=mockStatic(CompanionTaskService.class); var runtime=mockStatic(CompanionRuntime.class);
            var cafe=mockStatic(server.pccafe.PcCafe.class); var family=mockStatic(server.content.FamilyBenefits.class);
            var egg=mockStatic(server.content.MarketEgg.class); var medals=mockStatic(server.content.Medals.class)) {
            leases.when(CompanionTaskService::shared).thenReturn(tasks);
            runtime.when(()->CompanionRuntime.active(any())).thenAnswer(call->tasks.task(((Character)call.getArgument(0)).getId()).isPresent());
            cafe.when(()->server.pccafe.PcCafe.expMultiplier(any())).thenReturn(1.0);
            family.when(()->server.content.FamilyBenefits.expMultiplier(any())).thenReturn(1.0);
            YamlConfig.config.server.USE_ENFORCE_MOB_LEVEL_RANGE=false; YamlConfig.config.server.USE_FULL_HOLY_SYMBOL=false;
            Character human=member(1,false,null); List<Character> members=new ArrayList<>(List.of(human));
            when(human.getBuffedValue(BuffStat.HOLY_SYMBOL)).thenReturn(50);
            for(int i=0;i<5;i++) members.add(member(21001+i,true,CompanionTaskService.State.FOLLOW));
            distribute(members,Map.of(human,1000L));
            verify(human).gainExp(2200,0,true,false,false);
            for(Character c:members.subList(1,6)) verify(c,never()).gainExp(anyInt(),anyInt(),anyBoolean(),anyBoolean(),anyBoolean());
        } finally {YamlConfig.config.server.USE_ENFORCE_MOB_LEVEL_RANGE=oldRange; YamlConfig.config.server.USE_FULL_HOLY_SYMBOL=oldHs;}
    }
}
