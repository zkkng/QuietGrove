package net.server.channel.handlers;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MoveLifeActivityTest {
    @Test void packedFacingDoesNotChangeAttackIndexAndWireActivityIsPreserved() {
        for (int index = 0; index <= 8; index++) {
            assertEquals(12 + index, MoveLifeHandler.decodeActivity((byte)(24 + index * 2)));
            assertEquals(12 + index, MoveLifeHandler.decodeActivity((byte)(25 + index * 2)));
        }
        assertEquals(21, MoveLifeHandler.decodeActivity((byte)42));
        assertEquals(29, MoveLifeHandler.decodeActivity((byte)59));
        assertEquals(-1, MoveLifeHandler.decodeActivity((byte)-1));
    }
    @Test void inclusiveRangesExcludeOrdinaryMovementAndSkillsFromAttacks() {
        assertTrue(MoveLifeHandler.inRangeInclusive((byte)12, 12, 20));
        assertTrue(MoveLifeHandler.inRangeInclusive((byte)20, 12, 20));
        assertFalse(MoveLifeHandler.inRangeInclusive((byte)11, 12, 20));
        assertFalse(MoveLifeHandler.inRangeInclusive((byte)21, 12, 20));
    }
}
