package client;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SkillFactoryStartupTest {
    @Test void completeLocalCatalogLoadsIncludingNumericStringPrerequisites() {
        // Startup loads every job file. Single-skill mocks cannot expose this WZ type mismatch.
        assertDoesNotThrow(SkillFactory::loadAllSkills);
        for (var entry : Map.of(15101005,15100004,15111001,15100004,
                5111002,5110001,5111004,5110001).entrySet()) {
            Skill skill = SkillFactory.getSkill(entry.getKey());
            assertNotNull(skill, "Local string-prerequisite skill " + entry.getKey());
            assertEquals(Map.of(entry.getValue(),1),skill.getPrerequisites());
            assertTrue(skill.getMaxLevel() > 0);
        }
        assertEquals(Map.of(1000000,5),SkillFactory.getSkill(1000001).getPrerequisites());
        assertNotNull(SkillFactory.getSkill(5221008));
    }
}
