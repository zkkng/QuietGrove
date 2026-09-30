package server.trainer;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class TrainerObservationBudgetTest {
    @Test void repeatedOutcomesAreSampledAndBoundaryIsInclusive() {
        var budget = new TrainerObservationBudget();
        Object map = new Object();
        assertTrue(budget.admit(map, 1, TrainerReactionPolicy.Kind.FMA, 0));
        assertFalse(budget.admit(map, 1, TrainerReactionPolicy.Kind.FMA, 29_999));
        assertTrue(budget.admit(map, 1, TrainerReactionPolicy.Kind.FMA, 30_000));
    }

    @Test void mapInstanceActorAndObservationKindsAreIndependent() {
        var budget = new TrainerObservationBudget();
        Object map = new Object();
        assertTrue(budget.admit(map, 1, TrainerReactionPolicy.Kind.FMA, 0));
        assertTrue(budget.admit(new Object(), 1, TrainerReactionPolicy.Kind.FMA, 0));
        assertTrue(budget.admit(map, 2, TrainerReactionPolicy.Kind.FMA, 0));
        assertTrue(budget.admit(map, 1, TrainerReactionPolicy.Kind.FLIGHT, 0));
    }

    @Test void concurrentBatchesCannotRecordTheSameCastRepeatedly() throws Exception {
        var budget = new TrainerObservationBudget();
        Object map = new Object();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 100; i++) results.add(pool.submit(
                    () -> budget.admit(map, 1, TrainerReactionPolicy.Kind.FMA, 0)));
            int accepted = 0;
            for (var result : results) if (result.get()) accepted++;
            assertEquals(1, accepted);
        }
    }
}
