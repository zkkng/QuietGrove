package scripting;

import org.junit.jupiter.api.Test;
import scripting.npc.NPCConversationManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class NpcApiContractTest {
    @Test void everyNpcScriptAndCraftingFixtureCallsExistingConversationMethods() throws Exception {
        Set<String> methods = Arrays.stream(NPCConversationManager.class.getMethods()).map(m -> m.getName()).collect(Collectors.toSet());
        // Audited orphan/foreign-version scripts, intentionally not rewritten into unrelated features.
        // See docs/world-restoration-2026-09-26.md for their reachability and missing backends.
        Map<String, Set<String>> legacy = Map.of(
                "1096003.js", Set.of("sendDirectionInfo"),
                "1096005.js", Set.of("sendDirectionInfo", "removeNPC", "updateInfo"),
                "2141000.js", Set.of("removeNpc", "forceStartReactor"),
                "9000004.js", Set.of("mapMobCount", "clear", "warpMembers"));
        Pattern call = Pattern.compile("\\bcm\\.(\\w+)\\s*\\(");
        try (var files = Files.walk(Path.of("scripts/npc"))) {
            for (var path : files.filter(p -> p.toString().endsWith(".js")).toList()) {
                String source = Files.readString(path).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\\r\\n]*", "");
                var matcher = call.matcher(source);
                while (matcher.find()) assertTrue(methods.contains(matcher.group(1)) || legacy.getOrDefault(path.getFileName().toString(), Set.of()).contains(matcher.group(1)), path + ": missing cm." + matcher.group(1));
            }
        }
        String fixture = Files.readString(Path.of("src/test/resources/scripting/crafting-fixture.js"));
        var matcher = Pattern.compile("(?m)^    (\\w+): function").matcher(fixture);
        while (matcher.find()) assertTrue(methods.contains(matcher.group(1)), "Fixture invents method " + matcher.group(1));
    }
}
