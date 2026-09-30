package server.crafting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import server.crafting.TcgCatalog.Ingredient;
import server.crafting.TcgCatalog.Offer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TcgExchangeTest {
    static Stream<Offer> offers() { return TcgCatalog.offers().stream(); }

    @Test void shopContainsOnlyOriginalCodeExclusiveRewards() {
        // Provenance and exclusions: docs/tcg-shop-scope-2026-09-26.md.
        // This boundary excludes normal rewards, drop materials and crafted upgrades.
        var expected = Set.of(
                4031750,4031751,4031752,4031753,4031754,4031755,4031756,4031757,4031758,4031759,4031760,
                4031897,4031898,4031899,4031900,4031913,4031914,4031915,4031916,4031917,4031918,4031919,4031920,
                4031936,4032021,4032026,4032134,
                2022282,2022283,2022284,2022305,2022306,2022307,2022308,2002027,2002028,2002029,2022439,2022440,2022441,
                3010005,5010046,5010048,5010049,
                5000034,5000037,5000039,5000044,5000045,
                1452054,1462047,1472065,1332067,1332070,1382054,1102165,
                1102176,1060127,1061149,1302088,1102193,1302106,1102191,1002856);
        var shop = TcgCatalog.offers().stream().filter(o -> o.section().startsWith("Buy -")).toList();
        assertEquals(expected, Set.copyOf(shop.stream().map(Offer::itemId).toList()));
        assertEquals(expected.size(), shop.size());
        assertTrue(shop.stream().allMatch(o -> o.ingredients().isEmpty()));
        assertEquals(281, TcgCatalog.offers().stream().filter(o -> o.section().startsWith("Craft -")).count());
    }

    static class Bag implements TcgExchange.Inventory {
        int mesos = 100000000, petLevel = 30, grants;
        boolean space = true, grantSucceeds = true, throwOnGrant;
        Map<Integer, Integer> materials = new HashMap<>();
        Bag(Offer offer) { for (var i : offer.ingredients()) materials.put(i.itemId(), i.quantity()); }
        public int activePetLevel() { return petLevel; }
        public boolean has(Ingredient i) { return materials.getOrDefault(i.itemId(), 0) >= i.quantity(); }
        public boolean canReceive(Offer o) { return space; }
        public boolean spend(int amount) { if (mesos < amount) return false; mesos -= amount; return true; }
        public void refund(int amount) { mesos += amount; }
        public boolean give(Offer o) { if (throwOnGrant) throw new IllegalStateException("storage unavailable"); if (grantSucceeds) grants++; return grantSucceeds; }
        public void take(Ingredient i) { materials.compute(i.itemId(), (id, amount) -> amount - i.quantity()); }
    }

    @ParameterizedTest @MethodSource("offers")
    void everyOfferChargesExactPriceAndConsumesItsRecipe(Offer offer) {
        var bag = new Bag(offer);
        assertTrue(TcgExchange.execute(offer, bag).startsWith("All done!"));
        assertEquals(1, bag.grants);
        assertEquals(100000000 - offer.mesos(), bag.mesos);
        assertTrue(bag.materials.values().stream().allMatch(q -> q == 0));
    }

    @ParameterizedTest @MethodSource("offers")
    void fullInventoryCannotConsumeAnything(Offer offer) {
        var bag = new Bag(offer); bag.space = false;
        var before = Map.copyOf(bag.materials);
        TcgExchange.execute(offer, bag);
        assertEquals(100000000, bag.mesos);
        assertEquals(before, bag.materials);
        assertEquals(0, bag.grants);
    }

    @ParameterizedTest @MethodSource("offers")
    void insufficientMesosCannotConsumeIngredients(Offer offer) {
        var bag = new Bag(offer); bag.mesos = offer.mesos() - 1;
        var before = Map.copyOf(bag.materials);
        TcgExchange.execute(offer, bag);
        assertEquals(offer.mesos() - 1, bag.mesos);
        assertEquals(before, bag.materials);
        assertEquals(0, bag.grants);
    }

    @ParameterizedTest @MethodSource("offers")
    void failedRewardOrPetCreationRefundsPayment(Offer offer) {
        var bag = new Bag(offer); bag.grantSucceeds = false;
        var before = Map.copyOf(bag.materials);
        TcgExchange.execute(offer, bag);
        assertEquals(100000000, bag.mesos);
        assertEquals(before, bag.materials);
        assertEquals(0, bag.grants);
    }

    @Test void missingIngredientsAndPetLevelRejectWithoutPayment() {
        for (Offer offer : TcgCatalog.offers()) {
            for (Ingredient ingredient : offer.ingredients()) {
                var bag = new Bag(offer);
                bag.materials.put(ingredient.itemId(), ingredient.quantity() - 1);
                var before = Map.copyOf(bag.materials);
                TcgExchange.execute(offer, bag);
                assertEquals(0, bag.grants, offer.toString());
                assertEquals(100000000, bag.mesos);
                assertEquals(before, bag.materials);
            }
            if (offer.petLevel() > 0) {
                var bag = new Bag(offer); bag.petLevel = offer.petLevel() - 1;
                TcgExchange.execute(offer, bag);
                assertEquals(0, bag.grants);
                assertEquals(100000000, bag.mesos);
            }
        }
    }

    @Test void unexpectedGrantFailureAlsoRefunds() {
        var bag = new Bag(TcgCatalog.offers().getFirst()); bag.throwOnGrant = true;
        assertThrows(IllegalStateException.class, () -> TcgExchange.execute(TcgCatalog.offers().getFirst(), bag));
        assertEquals(100000000, bag.mesos);
    }

    @Test void crystalIlbiRecipeUsesOneWholeIlbiStackAndYieldsEightHundredStars() {
        var offer = TcgCatalog.offers().stream().filter(o -> o.itemId() == 2070016 && !o.ingredients().isEmpty()).findFirst().orElseThrow();
        assertEquals(800, offer.quantity());
        var ilbi = offer.ingredients().stream().filter(i -> i.itemId() == 2070006).findFirst().orElseThrow();
        assertTrue(ilbi.wholeStack());
        assertEquals(1, ilbi.quantity());
    }

    @Test void exactlyFiveTcgPetsHaveValidDurations() {
        var pets = TcgCatalog.offers().stream().filter(o -> o.petDays() > 0).toList();
        assertEquals(List.of(5000034, 5000037, 5000039, 5000044, 5000045), pets.stream().map(Offer::itemId).toList());
        assertTrue(pets.stream().allMatch(o -> o.petDays() == 90 && o.quantity() == 1));
    }

    @Test void malformedCatalogCannotCreateFreeItemsOrAmbiguousRecipes() {
        for (String line : List.of("Buy|5000034|1|1|0|0|", "Buy|2000000|1|-1|0|0|",
                "Buy|2000000|32768|1|0|0|", "Craft|2000000|1|1|0|0|4000000:1,4000000:2",
                "Craft|2000000|1|1|0|0|2000000:1")) {
            assertThrows(IllegalArgumentException.class, () -> TcgCatalog.parse(List.of(line)), line);
        }
    }
}
