package scripting;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class QuestDialogRegressionTest {
    private final AbstractScriptManager manager = new AbstractScriptManager() {};
    private ScriptEngine engine;

    @BeforeAll
    static void muteInterpreterWarning() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    @AfterEach
    void closeEngine() {
        if (engine instanceof GraalJSScriptEngine graal) {
            graal.close();
        }
    }

    private void load(String script, int quest) throws Exception {
        engine = manager.getInvocableScriptEngine(script);
        assertNotNull(engine, "Script must load: " + script);
        try (var reader = new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/scripting/quest-dialog-fixture.js")), StandardCharsets.UTF_8)) {
            engine.eval(reader);
        }
        engine.eval("questId = " + quest + "; questStates[questId] = 1;");
    }

    private void reply(String entry, int mode, int type) throws Exception {
        ((Invocable) engine).invokeFunction(entry, mode, type, 0);
    }

    private void finish(int replies) throws Exception {
        for (int i = 0; i < replies; i++) {
            reply("end", 1, 0);
        }
    }

    private void expect(String expression) throws Exception {
        assertEquals(Boolean.TRUE, engine.eval(expression), expression);
    }

    @ParameterizedTest
    @CsvSource({"6225,6226", "6315,6316"})
    void floAcceptsEitherMageWithTheirOwnKeyQuest(int activeQuest, int keyQuest) throws Exception {
        load("npc/2041023.js", activeQuest);
        engine.eval("questStates[" + keyQuest + "] = 2;");
        ((Invocable) engine).invokeFunction("start");
        expect("eventRequests == 1 && !disposed");
    }

    @ParameterizedTest
    @CsvSource({"6225,6316", "6315,6226", "6225,0", "6315,0", "0,6226", "0,6316", "0,0"})
    void floRejectsMissingOrMismatchedQuestPrerequisites(int activeQuest, int keyQuest) throws Exception {
        load("npc/2041023.js", activeQuest);
        engine.eval("questStates[" + keyQuest + "] = 2;");
        ((Invocable) engine).invokeFunction("start");
        expect("eventRequests == 0 && disposed");
    }

    @Test
    void rogerDoesNotConsumeOneFreeSlotThenLoseTheOtherReward() throws Exception {
        load("quest/1021.js", 1021);
        engine.eval("slots[2] = 1;");
        finish(4);
        expect("questStates[1021] == 1 && itemChanges == 0 && exp == 0 && disposed");
    }

    @Test
    void rogerChecksAllThreeItemsAgainstPartiallyFilledStacks() throws Exception {
        load("quest/1021.js", 1021);
        engine.eval("slots[2] = 2; items[2010000] = 98; items[2010009] = 98;");
        finish(4);
        expect("questStates[1021] == 1 && items[2010000] == 98 && items[2010009] == 98 && exp == 0");
    }

    @Test
    void rogerGivesTheEntireRewardOnceWhenItFits() throws Exception {
        load("quest/1021.js", 1021);
        engine.eval("slots[2] = 2;");
        finish(4);
        expect("questStates[1021] == 2 && items[2010000] == 3 && items[2010009] == 3 && exp == 10");
        // Back/Next must not pay a completed quest again.
        reply("end", 0, 0);
        reply("end", 1, 0);
        expect("items[2010000] == 3 && items[2010009] == 3 && exp == 10");
    }

    @Test
    void kiaKeepsMaterialsAndQuestWhenChairInventoryIsFull() throws Exception {
        load("quest/20013.js", 20013);
        engine.eval("slots[3] = 0; items[4032267] = 1; items[4032268] = 1;");
        finish(3);
        expect("questStates[20013] == 1 && items[4032267] == 1 && items[4032268] == 1 && itemChanges == 0 && exp == 0");
    }

    @Test
    void kiaRechecksMaterialsLostDuringTheConversation() throws Exception {
        load("quest/20013.js", 20013);
        engine.eval("items[4032267] = 1; items[4032268] = 1;");
        finish(2);
        engine.eval("items[4032268] = 0;");
        finish(1);
        expect("questStates[20013] == 1 && items[4032267] == 1 && itemChanges == 0 && exp == 0");
    }

    @Test
    void kiaExchangesMaterialsForOneChairAndDoesNotRepeatTheReward() throws Exception {
        load("quest/20013.js", 20013);
        engine.eval("items[4032267] = 2; items[4032268] = 2;");
        finish(3);
        expect("questStates[20013] == 2 && items[4032267] == 1 && items[4032268] == 1 && items[3010060] == 1 && exp == 95");
        reply("end", 0, 0);
        reply("end", 1, 0);
        expect("items[4032267] == 1 && items[4032268] == 1 && items[3010060] == 1 && exp == 95");
    }

    @Test
    void windArcherReceivesTheFullArrowGiftWithOneFreeUseSlot() throws Exception {
        load("quest/20103.js", 20103);
        engine.eval("slots[2] = 1;");
        finish(2);
        expect("questStates[20103] == 2 && jobId == 1300 && items[2060000] == 2000 && items[1452051] == 1 && items[1142066] == 1");
    }

    @Test
    void windArcherWaitsWhenAllTwoThousandArrowsDoNotFit() throws Exception {
        load("quest/20103.js", 20103);
        engine.eval("slots[2] = 0;");
        finish(2);
        expect("questStates[20103] == 1 && jobId == 1000 && itemChanges == 0 && jobChanges == 0");
    }

    @ParameterizedTest
    @ValueSource(ints = {20101, 20102, 20103, 20104, 20105})
    void closingTheFirstJobConfirmationDoesNotAdvanceTheCharacter(int quest) throws Exception {
        load("quest/" + quest + ".js", quest);
        reply("end", 1, 0);
        reply("end", -1, 1);
        expect("disposed && jobId == 1000 && jobChanges == 0 && itemChanges == 0 && questStates[questId] == 1");
    }

    @ParameterizedTest
    @ValueSource(ints = {20101, 20102, 20103, 20104, 20105})
    void decliningTheFirstJobConfirmationDoesNotAdvanceTheCharacter(int quest) throws Exception {
        load("quest/" + quest + ".js", quest);
        reply("end", 1, 0);
        reply("end", 0, 1);
        expect("disposed && jobId == 1000 && itemChanges == 0 && questStates[questId] == 1");
    }

    @ParameterizedTest
    @CsvSource({"20101,1100", "20102,1200", "20103,1300", "20104,1400", "20105,1500"})
    void acceptingTheFirstJobConfirmationStillAdvancesTheCharacter(int quest, int expectedJob) throws Exception {
        load("quest/" + quest + ".js", quest);
        finish(2);
        expect("jobId == " + expectedJob + " && jobChanges == 1 && questStates[questId] == 2");
    }
}
