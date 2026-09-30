package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class CompanionMovementTeardownTest {
    @Test void alreadyStoppedMovementCannotExecuteALateTick() throws Exception {
        Character bot=mock(Character.class);
        BotMovementState state=new BotMovementState(bot,null);
        GCMovementDriver.stop(state);
        var tick=GCMovementDriver.class.getDeclaredMethod("safeTick",BotMovementState.class);
        tick.setAccessible(true); tick.invoke(null,state);
        verifyNoInteractions(bot);
    }
}
