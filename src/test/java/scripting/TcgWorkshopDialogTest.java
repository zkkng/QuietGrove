package scripting;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.crafting.TcgCatalog;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.*;

class TcgWorkshopDialogTest {
    @BeforeAll static void quiet() { System.setProperty("polyglot.engine.WarnInterpreterOnly", "false"); }

    private ScriptEngine load(int npc, int map) throws Exception {
        var engine = new AbstractScriptManager() {}.getInvocableScriptEngine("npc/tcg_workshop.js");
        assertNotNull(engine);
        engine.put("catalog", TcgCatalog.offersForNpc(npc, map));
        engine.eval("""
            var calls = 0, lastIndex = -1, disposed = false, text = '';
            var cm = {
                getTcgOffers: function() { return catalog; },
                sendSimple: function(s) { text = s; },
                sendYesNo: function(s) { text = s; },
                sendOk: function(s) { text = s; },
                dispose: function() { disposed = true; },
                exchangeTcg: function(i) { calls++; lastIndex = i; return 'ok'; }
            };
            start();
            """);
        return engine;
    }

    @ParameterizedTest
    @CsvSource({"9201082,100000000", "9201082,600000000", "9201051,600000000", "9201052,600000000",
            "9201083,600000000", "9201094,600000000", "9201101,600000000", "9201102,600000000", "9201106,600000000"})
    void everyOfferIsReachableThroughItsActualMenuAndCanOnlyBeConfirmedOnce(int npc, int map) throws Exception {
        try (var engine = (GraalJSScriptEngine) load(npc, map)) {
            engine.eval("""
                for (var target = 0; target < catalog.size(); target++) {
                    stage = 0; finished = false; disposed = false; calls = 0; selected = -1;
                    var group = sections.indexOf(String(catalog.get(target).section()));
                    action(1, 5, group);
                    var choice = choices.indexOf(target);
                    while (page < Math.floor(choice / pageSize)) action(1, 5, 10001);
                    if (text.indexOf('#L' + choice + '#') < 0) throw Error('Missing offer ' + target);
                    action(1, 5, choice);
                    if (stage != 2 || selected != target) throw Error('Wrong confirmation ' + target);
                    if (text.indexOf('undefined') >= 0 || text.indexOf('NaN') >= 0) throw Error('Invalid confirmation');
                    action(1, 1, 0);
                    action(1, 1, 0);
                    if (calls != 1 || lastIndex != target || !disposed) throw Error('Duplicate or missing purchase ' + target);
                }
                """);
        }
    }

    @Test void cancellationAtEveryStageAndForgedSelectionsNeverBuy() throws Exception {
        try (var engine = (GraalJSScriptEngine) load(9201082, 100000000)) {
            engine.eval("""
                for (var at = 0; at <= 2; at++) {
                    for (var mode of [-1, 0]) {
                        stage = 0; finished = false; disposed = false; calls = 0;
                        if (at >= 1) action(1, 5, 0);
                        if (at >= 2) action(1, 5, 0);
                        action(mode, 1, 0); action(1, 1, 0);
                        if (calls != 0 || !disposed) throw Error('Cancel purchased an item');
                    }
                }
                for (var bad of [-1, 9999, 2147483647]) {
                    stage = 0; finished = false; disposed = false; calls = 0;
                    action(1, 5, bad);
                    if (calls || !disposed) throw Error('Invalid category accepted');
                    stage = 0; finished = false; disposed = false;
                    action(1, 5, 0); action(1, 5, bad);
                    if (calls || !disposed) throw Error('Invalid offer accepted');
                }
                """);
        }
    }
}
