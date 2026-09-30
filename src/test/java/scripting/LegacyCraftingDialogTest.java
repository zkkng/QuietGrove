package scripting;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.script.Invocable;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class LegacyCraftingDialogTest {
    @BeforeAll static void quiet() { System.setProperty("polyglot.engine.WarnInterpreterOnly", "false"); }

    @ParameterizedTest
    @ValueSource(ints={1012002,1022003,1022004,1032002,1032100,1052002,1052003,1061000,1091003,
            2010003,2020000,2020002,2040014,2040016,2040020,2040021,2040022,2080000,2090004,
            2100001,9201000,9201095,9201096})
    void enumerateRecipesAndCheckSuccessFullInventoryMissingMaterialsAndCancellation(int npc) throws Exception {
        var manager = new AbstractScriptManager() {};
        try (var engine = (GraalJSScriptEngine) manager.getInvocableScriptEngine("npc/"+npc+".js")) {
            assertNotNull(engine);
            try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/scripting/crafting-fixture.js")), StandardCharsets.UTF_8)) {
                engine.eval(reader);
            }
            int count = ((Number) ((Invocable)engine).invokeFunction("walkCrafting")).intValue();
            assertTrue(count > 0, "No crafting route exercised for " + npc);
            Files.createDirectories(Path.of("target/crafting-routes"));
            Files.writeString(Path.of("target/crafting-routes/"+npc+".json"),
                    (String)engine.eval("JSON.stringify(craftingTransfers)"));
            System.out.println("Crafting NPC " + npc + ": " + count + " recipe routes verified");
            // Exercise the upper bulk limit too: item transfers use signed shorts.
            assertTrue(((Number) ((Invocable) engine).invokeFunction("walkCrafting", 100)).intValue() > 0,
                    "No maximum-quantity crafting route exercised for " + npc);
        }
    }
}
