package server;

import java.util.Map;

/** Recovered from the documented v83 client fee formula and intact MakerCostTest fixtures. */
public final class MakerCost {
    private MakerCost() {}

    public static int calculate(int baseCost, boolean stimulant, Map<Integer, Short> reagents) {
        if (baseCost < 0 || reagents == null) throw new IllegalArgumentException("Maker cost");
        long multiplier = stimulant ? 6 : 1;
        for (var reagent : reagents.entrySet()) {
            if (reagent.getValue() < 0) throw new IllegalArgumentException("Maker reagent count");
            multiplier = Math.addExact(multiplier, 3L * ((reagent.getKey() % 10) + 1) * reagent.getValue());
        }
        return Math.toIntExact(Math.addExact(baseCost, Math.multiplyExact(baseCost / 10L, multiplier)));
    }
}
