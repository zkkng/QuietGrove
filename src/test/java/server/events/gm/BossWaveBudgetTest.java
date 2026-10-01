package server.events.gm;

import org.junit.jupiter.api.Test;
import java.util.Random;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class BossWaveBudgetTest {
    @Test void randomInitialSizesAndAllMushmomFlavorsExcludeCrimson() {
        var random=new Random(37);var seen=new HashSet<Integer>();var sizes=new HashSet<Integer>();
        for(int i=0;i<1000;i++) {sizes.add(BossWaveBudget.initial(random));seen.add(BossWaveBudget.monster(random));}
        assertEquals(java.util.Set.of(5,6,7,8,9,10),sizes);
        assertEquals(java.util.Set.of(6130101,6300005,9400205,8130100),seen);
        assertFalse(seen.contains(8150000));
    }
    @Test void threeMinuteReplenishmentCountsSurvivorsAndAlreadyQueuedArrivals() {
        var budget=new BossWaveBudget(1000);var random=new Random(13);
        assertEquals(0,budget.replenish(180999,2,0,random));
        int added=budget.replenish(181000,2,2,random);
        assertTrue(added>=3 && added<=6);assertTrue(2+2+added<=10);
        assertEquals(0,budget.replenish(181001,0,0,random));
        assertEquals(0,budget.replenish(361000,10,0,random));
        int next=budget.replenish(541000,1,0,random);assertTrue(next>=6 && next<=9);
    }
}
