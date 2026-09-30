package soloMapling.ArtificialPlayer.CompanionSystem;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RecruitIntentParserTest {
    private final Map<Integer, String> names = Map.of(11, "Mira", 12, "Party", 13, "TrainMaster");

    @ParameterizedTest
    @ValueSource(strings = {"R> party for grinding", "LFM to train", "anyone want to party up?",
            "help me level", "grind together?", "train together!", "LFM: TRAINING"})
    void publicRecruitment(String text) {
        assertEquals(RecruitIntentParser.Kind.RECRUIT, RecruitIntentParser.parse(text, Map.of()).kind());
    }

    @ParameterizedTest
    @ValueSource(strings = {"train", "help", "hi there", "!party up", "/help me level", "@LFM to train",
            "don't party up", "DO NOT help me level", "not looking to train together", "LFM shoes",
            "Mira", "Party", "TrainMaster", "Mira and Party come train with me"})
    void unrelatedNegatedCommandsAndAmbiguousNames(String text) {
        assertEquals(RecruitIntentParser.Kind.NONE, RecruitIntentParser.parse(text, names).kind());
    }

    @Test
    void nameMatchingIsCaseInsensitiveWholeTokenAndNameCannotBecomeIntent() {
        var intent = RecruitIntentParser.parse("MIRA, come train with me!", names);
        assertEquals(RecruitIntentParser.Kind.RECRUIT, intent.kind());
        assertEquals(11, intent.namedBotId());
        assertEquals(RecruitIntentParser.Kind.NONE, RecruitIntentParser.parse("Party up?", names).kind());
        assertNull(RecruitIntentParser.parse("Mirage, train together?", names).namedBotId());
        assertEquals(13, RecruitIntentParser.parse("TrainMaster: help me level", names).namedBotId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel recruitment", "stop recruiting!", "dismiss companions", "leave my party",
            "Mira stop following"})
    void cancellationIsDistinct(String text) {
        assertEquals(RecruitIntentParser.Kind.CANCEL, RecruitIntentParser.parse(text, Map.of(11, "Mira")).kind());
    }

    @Test
    void punctuationNormalizesCoalescingAndLongerNamesWin() {
        var first = RecruitIntentParser.parse("Mira, COME train with me!!!", names);
        var second = RecruitIntentParser.parse("mira come train with me", names);
        assertEquals(first, second);
        assertEquals(21, RecruitIntentParser.parse("Mira II come train with me",
                Map.of(11, "Mira", 21, "Mira II")).namedBotId());
        assertEquals(RecruitIntentParser.Kind.NONE, RecruitIntentParser.parse(null, names).kind());
    }
}
