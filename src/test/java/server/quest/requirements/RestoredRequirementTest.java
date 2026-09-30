package server.quest.requirements;

import client.Character;
import org.junit.jupiter.api.Test;
import provider.Data;
import server.quest.QuestRequirementType;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RestoredRequirementTest {
    private Data value(int number) { var data = mock(Data.class); when(data.getData()).thenReturn(number); return data; }
    @Test void skillAcquisitionIncludesUnlockedSkillsWithoutAssignedSp() {
        var root = mock(Data.class); var entry = mock(Data.class);
        when(root.iterator()).thenAnswer(i -> java.util.List.of(entry).iterator());
        var skillId = value(1121006);
        when(entry.getChildByPath("id")).thenReturn(skillId);
        var player = mock(Character.class);
        var unlearned = new SkillRequirement(null, root);
        assertTrue(unlearned.check(player, null));
        when(player.getMasterLevel(1121006)).thenReturn(10);
        assertFalse(unlearned.check(player, null));
        var acquire = value(1);
        when(entry.getChildByPath("acquire")).thenReturn(acquire);
        var learned = new SkillRequirement(null, root);
        assertTrue(learned.check(player, null));
        when(player.getMasterLevel(1121006)).thenReturn(0); when(player.getSkillLevel(1121006)).thenReturn(1);
        assertTrue(learned.check(player, null));
        when(player.getSkillLevel(1121006)).thenReturn(0); assertFalse(learned.check(player, null));
    }
    @Test void wzQuestChainActionsAndLevelAliasReachTheirExistingImplementations() {
        assertEquals(server.quest.QuestActionType.QUEST, server.quest.QuestActionType.getByWZName("quest"));
        assertEquals(QuestRequirementType.MIN_LEVEL, QuestRequirementType.getByWZName("level"));
        var requirement = new MinLevelRequirement(null, value(200)); var player = mock(Character.class);
        when(player.getLevel()).thenReturn(199,200);
        assertFalse(requirement.check(player,null)); assertTrue(requirement.check(player,null));
    }
    @Test void kingClangSpawnerCreditsTheMonsterCounterUsedByItsWzQuest() throws Exception {
        String event = java.nio.file.Files.readString(java.nio.file.Path.of("scripts/event/AreaBossKingClang.js"));
        var match = java.util.regex.Pattern.compile("getMonster\\((\\d+)\\)").matcher(event);
        assertTrue(match.find()); int spawned = Integer.parseInt(match.group(1));
        var path = java.nio.file.Path.of("wz/Quest.wz/Check.img.xml");
        try (var input = new java.io.FileInputStream(path.toFile())) {
            var data = new provider.wz.XMLDomMapleData(input, path.getParent());
            int required = provider.DataTool.getInt(data.getChildByPath("2161/1/mob/0/id"));
            assertEquals(required, constants.id.MobId.questCounterAlias(spawned));
        }
        assertEquals(9101000, constants.id.MobId.questCounterAlias(1110100));
        assertEquals(100100, constants.id.MobId.questCounterAlias(100100));
    }
    @Test void blackbullNeedsTenFame() {
        var requirement = new FameRequirement(null, value(10)); var player = mock(Character.class);
        when(player.getFame()).thenReturn(9, 10, 11);
        assertFalse(requirement.check(player, null)); assertTrue(requirement.check(player, null)); assertTrue(requirement.check(player, null));
        assertEquals(QuestRequirementType.FAME, QuestRequirementType.getByWZName("pop"));
    }
    @Test void endmesoDoesNotAllowCompletionWhenPoor() {
        assertEquals(QuestRequirementType.MESO, QuestRequirementType.getByWZName("endmeso"));
        var requirement = new MesoRequirement(null, value(1000000)); var player = mock(Character.class);
        when(player.getMeso()).thenReturn(999999,1000000);
        assertFalse(requirement.check(player,null)); assertTrue(requirement.check(player,null));
    }
}
