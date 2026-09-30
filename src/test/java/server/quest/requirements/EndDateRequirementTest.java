package server.quest.requirements;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import provider.wz.XMLDomMapleData;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

class EndDateRequirementTest {
    @TempDir
    Path tempDir;

    private EndDateRequirement requirement(String date) throws Exception {
        Path xml = tempDir.resolve("Check.img.xml");
        Files.writeString(xml, "<imgdir name=\"Check.img\"><string name=\"end\" value=\"" + date + "\"/></imgdir>");
        try (var input = new FileInputStream(xml.toFile())) {
            var data = new XMLDomMapleData(input, tempDir).getChildByPath("end");
            return new EndDateRequirement(null, data);
        }
    }

    @Test
    void questThatExpiredAtTheStartOfThisMonthCannotStillBeAccepted() throws Exception {
        String expired = YearMonth.now().atDay(1).format(DateTimeFormatter.BASIC_ISO_DATE) + "00";
        assertFalse(requirement(expired).check(null, null));
    }

    @Test
    void questExpiringNextMonthCanStillBeAccepted() throws Exception {
        String future = YearMonth.now().plusMonths(1).atDay(1).format(DateTimeFormatter.BASIC_ISO_DATE) + "00";
        assertTrue(requirement(future).check(null, null));
    }
}
