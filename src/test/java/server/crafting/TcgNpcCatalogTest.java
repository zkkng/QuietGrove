package server.crafting;

import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class TcgNpcCatalogTest {
    @ParameterizedTest
    @CsvSource({"9201051,16", "9201052,4", "9201082,25", "9201083,1", "9201094,12",
            "9201101,12", "9201102,24", "9201106,12"})
    void originalCraftersKeepTheirOwnRecipesAndNeverSellCodeRewards(int npc, int recipeCount) {
        var offers = TcgCatalog.offersForNpc(npc, 600000000);
        assertEquals(recipeCount, offers.size());
        assertTrue(offers.stream().allMatch(o -> o.section().startsWith("Craft -")));
    }

    @Test void henesysReplacesPhysicalCodesAndKeepsEveryExistingRecipeReachable() {
        var henesys = TcgCatalog.offersForNpc(9201082, 100000000);
        assertEquals(64, henesys.stream().filter(o -> o.section().startsWith("Buy -")).count());
        assertEquals(175, henesys.stream().filter(o -> o.section().startsWith("Craft - Ridley (")).count());
        assertTrue(henesys.containsAll(TcgCatalog.offersForNpc(9201082, 600000000)));
        var reachable = new HashSet<>(henesys);
        for (int npc : new int[]{9201051,9201052,9201083,9201094,9201101,9201102,9201106}) {
            reachable.addAll(TcgCatalog.offersForNpc(npc, 600000000));
        }
        assertEquals(new HashSet<>(TcgCatalog.offers()), reachable);
    }

    @Test void glimmerManMakesTheOrbAndSpindleUsesIt() {
        var orb = TcgCatalog.offersForNpc(9201083, 600000000).getFirst();
        assertEquals(4031761, orb.itemId());
        assertTrue(TcgCatalog.offersForNpc(9201082, 600000000).stream()
                .anyMatch(o -> o.ingredients().stream().anyMatch(i -> i.itemId() == 4031761)));
        assertTrue(TcgCatalog.offersForNpc(9201082, 600000000).stream().noneMatch(o -> o.itemId() == 4031761));
    }

    @Test void unknownNpcsAndThePreservedPqGuideCannotExchangeCatalogItems() {
        assertTrue(TcgCatalog.offersForNpc(0, 100000000).isEmpty());
        assertTrue(TcgCatalog.offersForNpc(9201103, 100000000).isEmpty());
    }
}
