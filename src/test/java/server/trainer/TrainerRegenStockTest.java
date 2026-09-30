package server.trainer;

import client.inventory.Equip;
import org.junit.jupiter.api.Test;
import server.ItemInformationProvider;
import soloMapling.itemPool.ItemDatabase;
import soloMapling.itemPool.ItemUtilities;
import soloMapling.itemPool.ScrollNode;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrainerRegenStockTest {
    @Test void venuePlanIsStableVariedAndChaosOccursOncePerWeek() {
        var day=LocalDate.of(2026,9,30); int chaos=0; Set<UUID> ids=new HashSet<>();
        for(int d=0;d<7;d++) {
            var date=day.plusDays(d); var plan=TrainerVenueStockPlan.recipes(105040401,1,date);
            assertEquals(plan,TrainerVenueStockPlan.recipes(105040401,1,date)); assertEquals(31,plan.size());
            assertTrue(plan.stream().anyMatch(r->r.itemId()==2070006));
            assertTrue(plan.stream().anyMatch(r->r.itemId()==1472026));
            assertTrue(plan.stream().anyMatch(r->r.successfulGloveScrolls()==2));
            chaos+=plan.stream().filter(r->r.itemId()==2049100).count();
            for(int i=0;i<plan.size();i++) assertTrue(ids.add(TrainerVenueStockPlan.assetId(105040401,1,date,i)));
        }
        assertEquals(1,chaos);
        assertNotEquals(TrainerVenueStockPlan.assetId(105040401,1,day,0),TrainerVenueStockPlan.assetId(105040401,2,day,0));
    }
    @Test void scrolledRecipePreservesRealStatsSlotsAndDeterministicSnapshotAndChargesExpectedCost() {
        var catalog=mock(ItemDatabase.class); var provider=mock(ItemInformationProvider.class); var scroll=mock(ScrollNode.class);
        when(catalog.getItemPrice(1082002)).thenReturn(1000); when(catalog.getScrollData(2040811)).thenReturn(scroll);
        when(scroll.getSuccessRate()).thenReturn(30); when(scroll.getStatBonus()).thenReturn(3); when(scroll.getCurrentPrice()).thenReturn(1600000);
        when(provider.getEquipById(1082002)).thenAnswer(call->{ var e=new Equip(1082002,(short)0,(byte)5); e.setWdef((short)2); return e; });
        try(var db=mockStatic(ItemDatabase.class); var ii=mockStatic(ItemInformationProvider.class); var value=mockStatic(ItemUtilities.class)) {
            db.when(ItemDatabase::getInstance).thenReturn(catalog); ii.when(ItemInformationProvider::getInstance).thenReturn(provider);
            value.when(()->ItemUtilities.getItemMarketValue(any())).thenReturn(5_000_000);
            var recipe=new TrainerVenueStockPlan.Recipe(1082002,2);
            var stock=TrainerVenueStockPlan.materialize(recipe); var e=(Equip)stock.item();
            assertEquals(6,e.getWatk()); assertEquals(2,e.getWdef()); assertEquals(3,e.getUpgradeSlots()); assertEquals(2,e.getLevel());
            assertEquals(10_667_667,stock.budgetValue());
            assertArrayEquals(TrainerVenueItemCodec.encode(e),TrainerVenueItemCodec.encode(TrainerVenueStockPlan.materialize(recipe).item()));
            when(catalog.getItemPrice(1082002)).thenReturn(null);
            assertThrows(IllegalStateException.class,()->TrainerVenueStockPlan.materialize(recipe));
        }
    }
    @Test void boundedCadenceDoesNotCatchUpAndStopsOnOffOrExpiry() {
        var lease=new TrainerLease(1000);
        lease.configure(false,false,false,false,0,1,false,false,false,true,true,300,1000);
        assertTrue(lease.takeRegen(1000,250)); assertFalse(lease.takeRegen(1249,250)); assertTrue(lease.takeRegen(3000,250));
        assertFalse(lease.takeRegen(3000,250)); lease.off(); assertFalse(lease.takeRegen(4000,250));
        assertEquals(536870911,TrainerRegenOptions.amount(Integer.MAX_VALUE,25));
        assertThrows(IllegalArgumentException.class,()->new TrainerRegenOptions(26,10,250));
    }
    @Test void actualServiceAppliesIndependentRatesAndRejectsWholeInvalidProfile() throws Exception {
        var f=new TrainerProfileSessionTest(); f.setup();
        try {
            var profile=TrainerProfileSessionTest.profile(); profile.put("core",profile.get("core").replace("vac=1","vac=0").replace("hpRegen=0","hpRegen=1").replace("mpRegen=0","mpRegen=1"));
            profile.put("potions",profile.get("potions").replace("hp=1","hp=0"));
            profile.put("regen","hpPercent=7&mpPercent=19&interval=250");
            f.service.request("test","profile",profile);
            when(f.actor.getCurrentMaxHp()).thenReturn(1000); when(f.actor.getCurrentMaxMp()).thenReturn(2000);
            var tick=TrainerService.class.getDeclaredMethod("tick"); tick.setAccessible(true); tick.invoke(f.service);
            verify(f.actor).addHP(70); verify(f.actor).addMP(380);
            profile.put("regen","hpPercent=25&mpPercent=26&interval=250");
            assertThrows(IllegalArgumentException.class,()->f.service.request("test","profile",profile));
            assertEquals("7",f.service.request("test","status",Map.of()).get("hpPercent"));
            f.service.request("test","off",Map.of()); clearInvocations(f.actor); tick.invoke(f.service);
            verify(f.actor,never()).addHP(anyInt()); verify(f.actor,never()).addMP(anyInt());
        } finally { f.restore(); }
    }
}
