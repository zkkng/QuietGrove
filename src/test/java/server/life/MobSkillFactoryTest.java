package server.life;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobSkillFactoryTest {

    @TempDir
    private Path wzPath;

    private provider.Data fixture;

    @BeforeEach
    void setWzPath() {
        MockitoAnnotations.openMocks(this);
        writeTestFileToTempDir();
        fixture = new provider.wz.XMLWZFile(wzPath.resolve("wz/Skill.wz")).getData("MobSkill.img");
    }

    private void writeTestFileToTempDir() {
        try {
            String testFileContents = readTestFileContents();
            writeTempDirFile(testFileContents);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String readTestFileContents() throws IOException {
        return new String(getClass()
                .getClassLoader()
                .getResourceAsStream("MobSkill-test.img.xml")
                .readAllBytes()
        );
    }

    private void writeTempDirFile(String fileContents) throws IOException {
        Path tempDirDirectory = wzPath.resolve("wz/Skill.wz");
        Files.createDirectories(tempDirDirectory);
        Path tempDirFile = Files.createFile(tempDirDirectory.resolve("MobSkill.img.xml"));
        Files.writeString(tempDirFile, fileContents);
    }

    @Test
    void shouldLoadExistingMobSkill() {
        Optional<MobSkill> possibleSkill = MobSkillFactory.loadMobSkill(MobSkillType.ATTACK_UP, 1,fixture);

        assertTrue(possibleSkill.isPresent());
        MobSkill mobSkill = possibleSkill.get();
        assertAll("MobSkill",
                () -> assertEquals(115, mobSkill.getX()),
                () -> assertEquals(5, mobSkill.getMpCon()),
                () -> assertEquals(40_000, mobSkill.getCoolTime()),
                () -> assertEquals(30_000, mobSkill.getDuration())
        );
    }

    @Test
    void shouldThrowExceptionOnNonExisting() {
        assertTrue(MobSkillFactory.loadMobSkill(MobSkillType.DEFENSE_UP,1,fixture).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> MobSkillFactory.getMobSkillOrThrow(MobSkillType.DEFENSE_UP,Integer.MAX_VALUE));
    }

}
