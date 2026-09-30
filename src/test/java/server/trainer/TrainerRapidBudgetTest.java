package server.trainer;

import client.Character;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TrainerRapidBudgetTest {
    @Test void ordinarySessionsNeverAcquireTheRapidBudget() {
        TrainerLease lease = new TrainerLease(1000);
        for (int i = 0; i < 100; i++) assertTrue(lease.allowClientAttack(1000));
        assertTrue(TrainerService.getInstance().allowClientAttack(mock(Character.class)));
    }
    @Test void rapidUsesOneBudgetAndDoesNotCatchUpAfterAStall() {
        TrainerLease lease = new TrainerLease(1000);
        lease.configure(false, false, false, true, 150, 1000);
        assertTrue(lease.allowClientAttack(1000));
        assertFalse(lease.allowClientAttack(1149));
        assertTrue(lease.allowClientAttack(1150));
        assertFalse(lease.allowClientAttack(1150));
        assertTrue(lease.allowClientAttack(2000));
        assertFalse(lease.allowClientAttack(2000));
        lease.off();
        assertTrue(lease.allowClientAttack(2000));
    }
    @Test void reconfigureCannotResetAnAlreadySpentAttackBudget() {
        TrainerLease lease = new TrainerLease(1000);
        lease.configure(false, false, false, true, 150, 1000);
        assertTrue(lease.allowClientAttack(1000));
        lease.configure(false, false, false, true, 150, 1001);
        assertFalse(lease.allowClientAttack(1001));
    }
}
