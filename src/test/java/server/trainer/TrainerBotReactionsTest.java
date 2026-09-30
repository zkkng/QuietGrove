package server.trainer;

import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.Rope;
import server.movement.AbsoluteLifeMovement;
import server.movement.RelativeLifeMovement;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class TrainerBotReactionsTest {
    private static FootholdTree terrain() {
        var terrain = new FootholdTree(new Point(-1_000, -1_000), new Point(10_000, 2_000));
        terrain.insert(new Foothold(new Point(-500, 0), new Point(9_000, 0), 1));
        terrain.insert(new Foothold(new Point(-500, 500), new Point(9_000, 500), 2));
        return terrain;
    }

    private static AbsoluteLifeMovement absolute(int x, int y) {
        return new AbsoluteLifeMovement(0, new Point(x, y), 200, 0);
    }

    @Test void walkingOnUpperPlatformsAndSlopesNeverCountsAsFlying() {
        var terrain = terrain();
        for (int x = 0; x < 1_000; x += 20) {
            assertFalse(TrainerFlightObservation.hasAirGap(terrain, new Point(x, 0)));
            assertFalse(TrainerFlightObservation.hasAirGap(terrain, new Point(x, -1)));
            assertFalse(TrainerFlightObservation.hasAirGap(terrain, new Point(x, 500)));
        }
        var slope = new FootholdTree(new Point(-1_000, -1_000), new Point(2_000, 2_000));
        slope.insert(new Foothold(new Point(0, 0), new Point(1_000, 200), 1));
        for (int x = 0; x <= 1_000; x += 20)
            assertFalse(TrainerFlightObservation.hasAirGap(slope, new Point(x, x / 5 - 1)));
    }

    @Test void jumpsTeleportSwimAndRopesAreExplainedMovement() {
        var terrain = terrain();
        var flight = absolute(300, -250);
        assertTrue(TrainerFlightObservation.hasUnexplainedAir(terrain, List.of(), false, List.of(flight)));
        assertFalse(TrainerFlightObservation.hasUnexplainedAir(terrain, List.of(), true, List.of(flight)));
        assertFalse(TrainerFlightObservation.hasUnexplainedAir(terrain,
                List.of(new Rope(300, -400, 0, false)), false, List.of(flight)));
        assertFalse(TrainerFlightObservation.hasUnexplainedAir(terrain, List.of(), false,
                List.of(flight, new RelativeLifeMovement(1, new Point(100, -400), 200, 12))));
        assertFalse(TrainerFlightObservation.hasUnexplainedAir(terrain, List.of(), false,
                List.of(flight, new server.movement.TeleportMovement(3, new Point(400, -250), 0))));
        assertFalse(TrainerFlightObservation.hasUnexplainedAir(terrain, List.of(), false,
                List.of(flight, absolute(400, -150))));
        assertFalse(TrainerFlightObservation.hasAirGap(terrain, new Point(20_000, -250)));
    }

    @Test void repeatedNormalJumpArcsDoNotAccumulateEvidence() {
        var flight = new TrainerFlightObservation();
        Object map = new Object();
        var terrain = terrain();
        int[] arc = {0, -60, -130, -175, -190, -175, -130, -60};
        for (int sample = 0; sample < 200; sample++) {
            Point at = new Point(sample * 20, arc[sample % arc.length]);
            assertFalse(flight.observe(map, at, TrainerFlightObservation.hasAirGap(terrain, at), sample * 200L));
        }
    }

    @Test void sustainedLevelAirTravelNeedsFourSecondsAndRealDistance() {
        var flight = new TrainerFlightObservation();
        Object map = new Object();
        for (int sample = 0; sample < 20; sample++)
            assertFalse(flight.observe(map, new Point(sample * 20, -250), true, sample * 200L));
        assertTrue(flight.observe(map, new Point(400, -250), true, 4_000));
        for (int sample = 0; sample < 40; sample++)
            assertFalse(flight.observe(map, new Point(400, -250), true, 4_200 + sample * 200L));
    }

    @Test void landingBetweenSamplesAndPacketGapsOrMapChangesResetEvidence() {
        var flight = new TrainerFlightObservation();
        Object map = new Object();
        for (int sample = 0; sample < 20; sample++)
            assertFalse(flight.observe(map, new Point(sample * 20, -250), true, sample * 200L));
        assertFalse(flight.observe(map, new Point(381, 0), false, 3_801));
        assertFalse(flight.observe(map, new Point(400, -250), true, 4_000));
        assertFalse(flight.observe(map, new Point(800, -250), true, 5_200));
        assertFalse(flight.observe(new Object(), new Point(1_200, -250), true, 5_400));
    }

    @Test void mapAndActorBudgetsStopWitnessRotationFromSpamming() {
        var policy = new TrainerReactionPolicy();
        Object map = new Object();
        Random random = new Random(7);
        List<Integer> bots = List.of(11, 12, 13, 14);
        assertNotNull(policy.reserve(map, 1, TrainerReactionPolicy.Kind.VAC, bots, 0, random));
        assertNull(policy.reserve(map, 2, TrainerReactionPolicy.Kind.FMA, bots, 30_000, random));
        assertNull(policy.reserve(new Object(), 1, TrainerReactionPolicy.Kind.FMA, bots, 60_000, random));
        assertNull(policy.reserve(map, 1, TrainerReactionPolicy.Kind.VAC, bots, 120_000, random));
        assertNotNull(policy.reserve(map, 1, TrainerReactionPolicy.Kind.VAC, bots, 180_000, random));
    }

    @Test void flightDiscussionHasTenMinuteActorCooldownAcrossMapsAndBots() {
        var policy = new TrainerReactionPolicy();
        Random random = new Random(8);
        assertNotNull(policy.reserve(new Object(), 1, TrainerReactionPolicy.Kind.FLIGHT, List.of(1), 0, random));
        assertNull(policy.reserve(new Object(), 1, TrainerReactionPolicy.Kind.FLIGHT, List.of(2), 599_999, random));
        assertNotNull(policy.reserve(new Object(), 1, TrainerReactionPolicy.Kind.FLIGHT, List.of(2), 600_000, random));
    }

    @Test void recentLinesAreNotRepeatedAndSomeReactionsStaySilent() {
        var policy = new TrainerReactionPolicy();
        Object map = new Object();
        List<String> lines = List.of("a", "b", "c", "d", "e", "f", "g", "h");
        ArrayDeque<String> recent = new ArrayDeque<>();
        Random random = new Random(9);
        int spoken = 0, silent = 0;
        for (int i = 0; i < 100; i++) {
            String line = policy.line(map, lines, random);
            assertFalse(recent.contains(line));
            recent.addLast(line);
            if (recent.size() > 6) recent.removeFirst();
            var decision = policy.reserve(map, 1, TrainerReactionPolicy.Kind.FLIGHT, List.of(1), i * 600_000L, random);
            assertNotNull(decision);
            if (decision.speak()) spoken++; else silent++;
        }
        assertTrue(spoken > 0);
        assertTrue(silent > spoken);
    }

    @Test void simultaneousIncidentsHaveOnlyOneWitnessSlot() throws Exception {
        var policy = new TrainerReactionPolicy();
        Object map = new Object();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<java.util.concurrent.Future<TrainerReactionPolicy.Decision>>();
            for (int actor = 1; actor <= 20; actor++) {
                int id = actor;
                tasks.add(executor.submit(() -> policy.reserve(map, id, TrainerReactionPolicy.Kind.FMA,
                        List.of(11, 12, 13, 14), 0, new Random(id))));
            }
            int reserved = 0;
            for (var task : tasks) if (task.get() != null) reserved++;
            assertEquals(1, reserved);
        }
    }
}
