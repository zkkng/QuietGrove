package server;

import org.junit.jupiter.api.Test;
import server.MakerItemFactory.MakerItemCreateEntry;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MakerCostTest {
    @Test void clientFeeIncludesBaseTenPercent() {
        assertEquals(11000, MakerCost.calculate(10000, false, Map.of()));
    }
    @Test void stimulantAndGemGradesUseBaseCost() {
        assertEquals(16000, MakerCost.calculate(10000, true, Map.of()));
        assertEquals(28000, MakerCost.calculate(10000, true, Map.of(4250000, (short) 1, 4250302, (short) 1)));
    }
    @Test void integerRoundingDoesNotMakeSmallRecipesFree() {
        assertEquals(275, MakerCost.calculate(250, false, Map.of()));
        assertEquals(0, MakerCost.calculate(0, true, Map.of()));
        assertThrows(ArithmeticException.class, () -> MakerCost.calculate(Integer.MAX_VALUE, true, Map.of()));
    }
    @Test void disassemblyAndCopiedRecipesRetainTheirFee() {
        var entry = new MakerItemCreateEntry(12345, 0, 1);
        assertEquals(12345, entry.getCost());
        assertEquals(12345, new MakerItemCreateEntry(entry).getCost());
    }
}
