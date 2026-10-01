package server.statistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsJournalTest {
    @TempDir Path directory;
    static StatisticsBatch batch(long sequence) {
        var key = new StatisticsAccumulator.Key(1,0,0,0,0,0,0,0,0,-1);
        return new StatisticsBatch("test",UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),sequence,1,2,
                List.of(new StatisticsAccumulator.Delta(key,BigInteger.TEN)));
    }
    @Test void deterministicCodecPreservesIdentityAndLargeValues() throws Exception {
        var original = batch(1);
        assertEquals(original,StatisticsBatch.decode(original.encode()));
        assertArrayEquals(original.encode(),StatisticsBatch.decode(original.encode()).encode());
        assertEquals(original.checksum(),StatisticsBatch.decode(original.encode()).checksum());
    }
    @Test void duplicateKeysAndOversizeRowBatchesAreRejected() {
        var original = batch(1);
        assertThrows(IllegalArgumentException.class,()->new StatisticsBatch(original.epoch(),original.producer(),original.boot(),1,1,2,
                List.of(original.deltas().getFirst(),original.deltas().getFirst())));
        assertThrows(IllegalArgumentException.class,()->new StatisticsBatch(original.epoch(),original.producer(),original.boot(),1,1,2,
                Collections.nCopies(1001,original.deltas().getFirst())));
    }
    @Test void malformedVersionAndTrailingPayloadAreRejected() {
        byte[] encoded = batch(1).encode(); encoded[3] = 99;
        assertThrows(IOException.class,()->StatisticsBatch.decode(encoded));
        byte[] trailing = Arrays.copyOf(batch(1).encode(),batch(1).encode().length+1);
        assertThrows(IOException.class,()->StatisticsBatch.decode(trailing));
    }
    @Test void durableFramesReplayAfterCloseAndReopen() throws Exception {
        Path path = directory.resolve("spool");
        try (var journal = new StatisticsJournal(path,4096)) { journal.appendAndSync(batch(1)); journal.appendAndSync(batch(2)); }
        try (var journal = new StatisticsJournal(path,4096)) {
            List<Long> sequences = new ArrayList<>(); journal.replay(b->sequences.add(b.sequence()));
            assertEquals(List.of(1L,2L),sequences); assertEquals(0,journal.repairedTailBytes());
        }
    }
    @Test void tornFinalFrameIsTrimmedToLastVerifiedBoundary() throws Exception {
        Path path = directory.resolve("spool");
        long good;
        try (var journal = new StatisticsJournal(path,4096)) { journal.appendAndSync(batch(1)); good = journal.size(); }
        Files.write(path,new byte[]{1,2,3},StandardOpenOption.APPEND);
        try (var journal = new StatisticsJournal(path,4096)) {
            assertEquals(good,journal.size()); assertEquals(3,journal.repairedTailBytes());
            List<Long> seen = new ArrayList<>(); journal.replay(b->seen.add(b.sequence())); assertEquals(List.of(1L),seen);
        }
    }
    @Test void checksumCorruptionIsQuarantinedRatherThanSilentlyTruncated() throws Exception {
        Path path = directory.resolve("spool");
        try (var journal = new StatisticsJournal(path,4096)) { journal.appendAndSync(batch(1)); }
        byte[] raw = Files.readAllBytes(path); raw[raw.length-1] ^= 1; Files.write(path,raw);
        assertThrows(IOException.class,()->new StatisticsJournal(path,4096));
        assertArrayEquals(raw,Files.readAllBytes(path));
    }
    @Test void spoolLimitRejectsBeforeOverwritingExistingFrame() throws Exception {
        Path path = directory.resolve("spool");
        int exact = batch(1).encode().length+12;
        try (var journal = new StatisticsJournal(path,exact)) {
            journal.appendAndSync(batch(1));
            assertThrows(IOException.class,()->journal.appendAndSync(batch(2)));
            List<Long> seen = new ArrayList<>(); journal.replay(b->seen.add(b.sequence())); assertEquals(List.of(1L),seen);
        }
    }
    @Test void exclusiveJournalOwnershipRejectsSecondWriter() throws Exception {
        Path path = directory.resolve("spool");
        try (var first = new StatisticsJournal(path,4096)) {
            assertThrows(Exception.class,()->new StatisticsJournal(path,4096));
            first.appendAndSync(batch(1));
        }
    }

    @Test void acknowledgedCompactionAcceptsNewFramesAndReopensCleanly() throws Exception {
        Path path=directory.resolve("compact");
        try(var journal=new StatisticsJournal(path,4096)){
            journal.appendAndSync(batch(1));journal.resetAcknowledged();assertEquals(0,journal.size());
            journal.appendAndSync(batch(2));
        }
        try(var journal=new StatisticsJournal(path,4096)){
            List<Long> frames=new ArrayList<>();journal.replay(b->frames.add(b.sequence()));
            assertEquals(List.of(2L),frames);
        }
    }
}
