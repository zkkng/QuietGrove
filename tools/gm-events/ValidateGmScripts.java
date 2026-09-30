package gmevents;
import java.nio.file.*;
import javax.script.Compilable;
import javax.script.ScriptEngineManager;
/** Syntax gate for the exact deploy payload; does not pretend to verify NPC gameplay. */
public final class ValidateGmScripts {
    public static void main(String[] scripts) throws Exception {
        Compilable compiler=(Compilable)new ScriptEngineManager().getEngineByName("graal.js");
        for(String script:scripts)try(var reader=Files.newBufferedReader(Path.of(script))){compiler.compile(reader);}
        System.out.println("GraalJS compiled "+scripts.length+" exact runtime scripts");
    }
}
