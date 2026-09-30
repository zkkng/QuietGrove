package server.trainer;

import client.Character;
import client.Client;
import org.junit.jupiter.api.*;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;
import java.awt.Point;
import java.awt.Rectangle;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrainerProfileSessionTest {
    final TrainerService service=new TrainerService();
    final Character actor=mock(Character.class);
    final Client client=mock(Client.class);
    final MapleMap map=mock(MapleMap.class);
    Object session;
    String previousEnabled;
    @BeforeEach void setup() throws Exception {
        previousEnabled=System.getProperty("solo.trainer.enabled"); System.setProperty("solo.trainer.enabled","true");
        when(actor.getClient()).thenReturn(client); when(client.getPlayer()).thenReturn(actor);
        when(actor.getMap()).thenReturn(map); when(actor.isLoggedinWorld()).thenReturn(true); when(actor.isAlive()).thenReturn(true);
        when(actor.getId()).thenReturn(123); when(actor.getName()).thenReturn("ProfileTest"); when(actor.getLevel()).thenReturn(10);
        when(actor.getPosition()).thenReturn(new Point()); when(map.getId()).thenReturn(100000000);
        when(map.getMapArea()).thenReturn(new Rectangle(-1000,-1000,2000,2000));
        Class<?> pending=Class.forName(TrainerService.class.getName()+"$Pending"), type=Class.forName(TrainerService.class.getName()+"$Session");
        Constructor<?> pc=pending.getDeclaredConstructors()[0], sc=type.getDeclaredConstructors()[0]; pc.setAccessible(true); sc.setAccessible(true);
        long now=System.currentTimeMillis(); session=sc.newInstance(pc.newInstance(actor,client,now),now);
        Field sessions=TrainerService.class.getDeclaredField("sessions"); sessions.setAccessible(true);
        ((Map<String,Object>)sessions.get(service)).put("test",session);
    }
    @AfterEach void restore() { if(previousEnabled==null) System.clearProperty("solo.trainer.enabled"); else System.setProperty("solo.trainer.enabled",previousEnabled); }
    static Map<String,String> profile() {
        return new HashMap<>(Map.of(
            "core","vac=1&vacMode=front&itemVac=0&mesoVac=0&lootOnKey=0&lootRadius=0&lootBatch=8&lootOrder=nearest&includeIds=&excludeIds=&minMeso=0&maxMeso=2147483647&fma=0&fmaDamage=3&fmaOneHit=0&rapid=0&hpGod=0&hpRegen=0&mpRegen=0&interval=300",
            "potions","hp=1&mp=0&hpThreshold=50&mpThreshold=30&hpItem=2000002&mpItem=2000006&reserve=5&interval=1000",
            "powers","noMpCost=1&noAmmo=0&zeroCooldown=0&cooldownSkills=&immunity=POISON&damageMultiplier=3&oneHit=0&accuracy=0&roll=normal",
            "loot","nearbyAuto=0&petItems=0&petMesos=0&petIndex=0&feeder=0&feedThreshold=50&name=&category=all&minValue=0&source=all&itemQuota=25&mesoQuota=25",
            "mobs","freeze=0&disarm=0&aggro=0&radius=500&include=100&exclude=&pointVac=1&x=300&y=100&spacing=17&pullStep=0",
            "pointMap","100000000"));
    }
    @Test void invalidLastSectionCannotPartiallyApplyEarlierControls() {
        var f=profile(); f.put("mobs",f.get("mobs").replace("pullStep=0","pullStep=501"));
        assertThrows(IllegalArgumentException.class,()->service.request("test","profile",f));
        var state=service.request("test","status",Map.of());
        assertEquals("0",state.get("vac")); assertEquals("0",state.get("autoHp")); assertEquals("0",state.get("noMpCost"));
    }
    @Test void previewAndWrongMapPreservePriorEffectiveState() {
        var f=profile(); var preview=service.request("test","profileValidate",f);
        assertEquals("0",preview.get("vac"));
        var applied=service.request("test","profile",f); assertEquals("1",applied.get("vac")); assertEquals("1",applied.get("noMpCost"));
        f.put("pointMap","100000001"); f.put("core",f.get("core").replace("vac=1","vac=0"));
        assertThrows(IllegalArgumentException.class,()->service.request("test","profile",f));
        assertEquals("1",service.request("test","status",Map.of()).get("vac"));
    }
    @Test void actualVacUsesSavedPointAndFiltersInsteadOfLegacyFront() throws Exception {
        service.request("test","profile",profile());
        Monster wanted=monster(100), excluded=monster(101), boss=monster(100); when(boss.isBoss()).thenReturn(true);
        when(map.getMonsters()).thenReturn(List.of(excluded,boss,wanted));
        when(map.getPointBelow(any(Point.class))).thenAnswer(call->call.getArgument(0));
        Method vac=TrainerService.class.getDeclaredMethod("vac",session.getClass()); vac.setAccessible(true);
        try(var reactions=mockStatic(TrainerBotReactions.class)) { vac.invoke(service,session); }
        verify(wanted).resetMobPosition(new Point(300,80)); verify(excluded,never()).resetMobPosition(any()); verify(boss,never()).resetMobPosition(any());
    }
    private Monster monster(int id) {
        Monster m=mock(Monster.class); when(m.getId()).thenReturn(id); when(m.isAlive()).thenReturn(true);
        when(m.getMap()).thenReturn(map); when(m.getStats()).thenReturn(mock(MonsterStats.class)); when(m.getPosition()).thenReturn(new Point()); return m;
    }
    @Test void killCounterRequiresDeadMobAndCurrentClientSession() {
        service.request("test","profile",profile()); Monster dead=monster(100);
        service.onMonsterKilled(actor,dead); when(dead.getHp()).thenReturn(1); service.onMonsterKilled(actor,dead);
        assertEquals("1",service.request("test","status",Map.of()).get("finishingKills"));
        when(client.getPlayer()).thenReturn(null); when(dead.getHp()).thenReturn(0); service.onMonsterKilled(actor,dead);

    }
    @Test void experienceAcrossLevelBoundaryAndLossUsesLongTotals() {
        long previous=TrainerSessionStats.totalExp(199,100), next=TrainerSessionStats.totalExp(200,25);
        assertEquals(constants.game.ExpTable.getExpNeededForLevel(199)-75L,next-previous);
        assertTrue(next>Integer.MAX_VALUE); assertEquals(-3600,TrainerSessionStats.hourly(-10,10000));
    }
}
