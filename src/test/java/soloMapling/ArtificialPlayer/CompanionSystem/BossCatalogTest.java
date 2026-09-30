package soloMapling.ArtificialPlayer.CompanionSystem;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BossCatalogTest {
    @Test void everyEnabledEntryValidatesAgainstLocalWzAndAdapterSource() {
        assertEquals(List.of(),BossRegistry.validate());
    }
    @ParameterizedTest @CsvSource({"R> Jr Balrog,jr-balrog", "anyone want to kill Mushmom,mushmom",
        "help me fight Stumpy,stumpy", "lets hunt Bigfoot,bigfoot", "Mira come help with Manon,manon",
        "need a healer for Zakum,zakum", "LFM zombie mushmom,zombie-mushmom", "hunt BMM,blue-mushmom",
        "hunt Female Boss,anego", "hunt Anego,anego", "need HS for HT,horntail", "fight Jr. Balrog's minions,jr-balrog",
        "lets hunt boat balrog,boat-balrog", "kill instanced Balrog,instance-balrog", "fight easy Balrog,easy-balrog",
        "hunt Papulatus Clock,papulatus", "hunt Headless Horseman,headless-horseman", "R> King Clang,king-clang"})
    void actionableWholeNames(String text, String key) {
        var intent = BossIntentParser.parse(text,Map.of(1,"Mira"));
        assertEquals(BossIntentParser.Kind.RECRUIT,intent.kind()); assertEquals(key,intent.bossKey());
    }
    @ParameterizedTest @ValueSource(strings={"don't recruit for Balrog", "I remember fighting Balrog", "Mushmom is cute",
        "R> Mushmommie", "!hunt Mushmom", "\"R> Jr Balrog\"", "never help me kill Mano", "I used to fight Manon"})
    void discussionAndFragmentsDoNotRecruit(String text) { assertEquals(BossIntentParser.Kind.NONE,BossIntentParser.parse(text,Map.of()).kind()); }
    @Test void balrogRequiresExplicitVariantAndRecipientCannotContainAnIntent() {
        assertEquals(BossIntentParser.Kind.CLARIFY,BossIntentParser.parse("R> Balrog",Map.of()).kind());
        assertEquals(BossIntentParser.Kind.NONE,BossIntentParser.parse("MushmomHunter says hello",Map.of(9,"MushmomHunter")).kind());
        var parsed = BossIntentParser.parse("Mira need a healer for Zakum gather at 211042400",Map.of(7,"Mira"));
        assertEquals(7,parsed.recipient()); assertEquals(BossIntentParser.Role.SUPPORT,parsed.role()); assertEquals(211042400,parsed.gatherMap());
    }
    @Test void variantsAreExplicitAndRestrictedAdaptersNeverBecomeFieldWarps() {
        assertEquals(Set.of(5220001),BossRegistry.get("king-clang").roots());
        assertEquals(Set.of(9400205),BossRegistry.get("blue-mushmom").roots());
        assertFalse(BossRegistry.get("headless-horseman").phases().contains(9400571));
        assertTrue(BossRegistry.get("papulatus").restricted()); assertTrue(BossRegistry.get("horntail").restricted());
        assertEquals(BossDefinition.Type.BOAT,BossRegistry.get("boat-balrog").type());
        assertFalse(BossRegistry.get("instance-balrog").combatTemplate(8830006));
        assertFalse(BossRegistry.get("instance-balrog").combatTemplate(8830004));
        assertTrue(BossRegistry.get("instance-balrog").combatTemplate(8830001));
        assertFalse(BossRegistry.get("easy-balrog").combatTemplate(8830013));
        assertTrue(BossRegistry.get("easy-balrog").combatTemplate(8830008));
    }
    @ParameterizedTest @ValueSource(strings={"R> Pianus","help kill Dojo Jr Balrog","hunt chaos Zakum","fight quest variant Balrog","R> Pink Bean","hunt Krexel"})
    void unsupportedBossesCannotFallThroughToGenericRecruitment(String text) {
        assertEquals(BossIntentParser.Kind.UNSUPPORTED,BossIntentParser.parse(text,Map.of()).kind());
    }
}
