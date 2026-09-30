package server.events.gm;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class OxBotKnowledgeTest {
    @Test void evaluatesOnlyPublicStatementAgainstIndependentMonsterData() {
        var names = Set.of("JR BALROG", "CRIMSON BALROG");
        assertEquals(new OxBotKnowledge.Fact(true, 80), OxBotKnowledge.interpret(
                "The JR. BALROG that lives in the cursed altar is a LV. 80 monster.", names, name -> 80));
        assertEquals(new OxBotKnowledge.Fact(false, 80), OxBotKnowledge.interpret(
                "The JR. BALROG is a LV. 20 monster.", names, name -> 80));
        assertNull(OxBotKnowledge.interpret("A pig drops a ribbon.", names, name -> fail()));
        assertNull(OxBotKnowledge.interpret("UNKNOWN is a LV. 20 monster.", names, name -> fail()));
    }
    @Test void variedKnowledgeImprovesRecallWithoutPerfectAnswersAndIsStable() {
        var fact = new OxBotKnowledge.Fact(true, 80);
        int experienced = 0, inexperienced = 0;
        for (int id = 1; id <= 1000; id++) {
            boolean answer = OxBotKnowledge.choose(10016, id, 120, .9, fact);
            assertEquals(answer, OxBotKnowledge.choose(10016, id, 120, .9, fact));
            if (answer) experienced++;
            if (OxBotKnowledge.choose(10016, id, 10, .2, fact)) inexperienced++;
        }
        assertTrue(experienced > inexperienced + 100);
        assertTrue(experienced < 1000); assertTrue(inexperienced > 0);
    }
}
