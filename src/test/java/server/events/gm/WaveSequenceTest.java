package server.events.gm;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WaveSequenceTest {
    @Test void introAndIntermissionDoNotAdvancePastLivingBosses() {
        var sequence=new WaveSequence(WaveSequence.bosses(),1000);
        assertNull(sequence.next(20_999));
        var first=sequence.next(21_000);assertEquals(3,first.wave().count());
        assertNull(sequence.next(3_000_000)); // A clock cannot kill a surviving wave.
        assertTrue(sequence.complete(first,true,3_000_000));
        assertFalse(sequence.complete(first,true,3_000_001));
        assertNull(sequence.next(3_029_999));
        var second=sequence.next(3_030_000);assertEquals("jr-balrog",second.wave().encounter());
        assertTrue(sequence.complete(second,false,3_040_000));assertTrue(sequence.closed());
        assertNull(sequence.next(Long.MAX_VALUE));
    }
    @Test void capValidationAndStaleCompletionAfterCancel() {
        assertThrows(IllegalArgumentException.class,()->new WaveSequence.Wave("mushmom",4,true));
        assertThrows(IllegalArgumentException.class,()->new WaveSequence.Wave("snail",31,false));
        assertDoesNotThrow(()->new WaveSequence.Wave("snail",30,false));
        var sequence=new WaveSequence(WaveSequence.bosses(),0);var first=sequence.next(20_000);
        sequence.cancel();assertFalse(sequence.complete(first,true,25_000));assertEquals(0,sequence.completed());
    }
    @Test void finiteFinalVictoryAndMobPlan() {
        var sequence=new WaveSequence(List.of(new WaveSequence.Wave("snail",30,false)),0);
        var token=sequence.next(20_000);assertTrue(sequence.complete(token,true,50_000));
        assertTrue(sequence.closed());assertEquals(1,sequence.completed());assertNull(sequence.next(100_000));
    }
}
