package server.trainer;

import client.inventory.Equip;
import org.junit.jupiter.api.*;
import server.maps.MapItem;
import server.maps.MapObject;
import server.life.Monster;
import java.awt.Point;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrainerPickupInspectorTest {
    private TrainerProfileSessionTest fixture;
    @BeforeEach void setup() throws Exception { fixture=new TrainerProfileSessionTest(); fixture.setup(); }
    @AfterEach void cleanup() { fixture.restore(); }
    static Map<String,String> options() {
        return TrainerBridge.decode("tubi=1&burst=0&interval=50&batch=10&scenarioAge=1&minimumAge=0&minWatk=0&minMatk=0&minSlots=0");
    }
    @Test void actualServiceRepeatsAtFeetWithoutExpandingReachOrBypassingOwnership() throws Exception {
        fixture.service.request("test","pickupoptions",options());
        MapItem close=drop(1,20),remote=drop(2,181),protectedDrop=drop(3,10);
        when(protectedDrop.canBePickedBy(fixture.actor)).thenReturn(false);
        when(fixture.map.getItems()).thenReturn(List.of(close,remote,protectedDrop));
        Method pickup=TrainerService.class.getDeclaredMethod("itemVac",fixture.session.getClass(),long.class); pickup.setAccessible(true);
        long now=System.currentTimeMillis();
        pickup.invoke(fixture.service,fixture.session,now);
        verify(fixture.actor).pickupItem(close,-1); verify(fixture.actor,never()).pickupItem(remote,-1); verify(fixture.actor,never()).pickupItem(protectedDrop,-1);
        // Failed targets back off; a new real drop is still eligible at the requested cadence.
        MapItem next=drop(4,30); when(fixture.map.getItems()).thenReturn(List.of(next));
        pickup.invoke(fixture.service,fixture.session,now+49); verify(fixture.actor,never()).pickupItem(next,-1);
        pickup.invoke(fixture.service,fixture.session,now+50); verify(fixture.actor).pickupItem(next,-1);
    }
    private MapItem drop(int oid,int x) {
        var d=mock(MapItem.class); when(d.getObjectId()).thenReturn(oid); when(d.getMeso()).thenReturn(10);
        when(d.getPosition()).thenReturn(new Point(x,0)); when(d.canBePickedBy(fixture.actor)).thenReturn(true);
        when(d.getDropTime()).thenReturn(System.currentTimeMillis()-1000); when(fixture.map.getMapObject(oid)).thenReturn(d); return d;
    }
    @Test void burstBudgetIsSharedAndCannotAccumulateAfterIdle() {
        var b=new TrainerPickupOptions.Budget();
        for(int i=0;i<25;i++) assertTrue(b.take(1000));
        assertFalse(b.take(1499)); assertTrue(b.take(1500));
        assertEquals(25,b.available(100000));
    }
    @Test void ageOverrideRequiresBothFiniteScenarioTagsAndCurrentSession() {
        fixture.service.request("test","pickupoptions",options()); var d=drop(1,0);
        assertEquals(400,fixture.service.minimumPickupAge(fixture.actor,d));
        when(d.getVenueAssetId()).thenReturn(UUID.randomUUID()); assertEquals(400,fixture.service.minimumPickupAge(fixture.actor,d));
        when(d.getVenueRoundId()).thenReturn(UUID.randomUUID()); assertEquals(0,fixture.service.minimumPickupAge(fixture.actor,d));
        fixture.service.request("test","off",Map.of()); assertEquals(400,fixture.service.minimumPickupAge(fixture.actor,d));
    }
    @Test void equipmentFiltersUseInstanceStatsAndExcludeNonEquipment() {
        var f=options(); f.put("minWatk","10"); f.put("minSlots","2"); var option=TrainerPickupOptions.parse(f);
        var item=mock(MapItem.class); var equip=new Equip(1082002,(short)1,(byte)2); equip.setWatk((short)9); when(item.getItem()).thenReturn(equip);
        assertFalse(option.accepts(item)); equip.setWatk((short)10); assertTrue(option.accepts(item));
        equip.setUpgradeSlots((byte)1); assertFalse(option.accepts(item)); when(item.getItem()).thenReturn(new client.inventory.Item(2040811,(short)1,(short)1)); assertFalse(option.accepts(item));
    }
    @Test void inspectorPagesRealObjectValuesAndDoesNotReadOffPageDetails() {
        List<MapObject> mobs=new ArrayList<>();
        for(int i=0;i<12;i++) { Monster m=mock(Monster.class); when(m.getObjectId()).thenReturn(i); when(m.isAlive()).thenReturn(true);
            when(m.getPosition()).thenReturn(new Point(i*10,20)); when(m.getName()).thenReturn("Test\tMob"); when(m.getHp()).thenReturn(30); when(m.getMaxHp()).thenReturn(100); mobs.add(m); }
        when(fixture.map.getMonsters()).thenReturn(mobs);
        var page=TrainerInspector.snapshot(fixture.actor,Map.of("map","100000000","kind","mobs","offset","8"));
        assertEquals("4",page.get("count")); assertEquals("12",page.get("total"));
        assertTrue(page.get("row0").contains("Test Mob\t80\t20\t30/100 HP"));
        verify((Monster)mobs.get(0),never()).getHp(); verify(fixture.map,never()).getAllPlayers();
        assertThrows(IllegalArgumentException.class,()->TrainerInspector.snapshot(fixture.actor,Map.of("map","999","kind","mobs","offset","0")));
    }
    @Test void invalidPickupSectionCannotPartiallyApplyProfile() {
        var profile=TrainerProfileSessionTest.profile(); profile.put("pickup","tubi=1&burst=1&interval=1&batch=25&scenarioAge=1&minimumAge=0&minWatk=0&minMatk=0&minSlots=0");
        assertThrows(IllegalArgumentException.class,()->fixture.service.request("test","profile",profile));
        assertEquals("0",fixture.service.request("test","status",Map.of()).get("vac"));
    }
}
