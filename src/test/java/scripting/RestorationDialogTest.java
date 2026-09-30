package scripting;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import javax.script.Invocable;
import static org.junit.jupiter.api.Assertions.*;

class RestorationDialogTest {
    @BeforeAll static void quiet() { System.setProperty("polyglot.engine.WarnInterpreterOnly", "false"); }
    private GraalJSScriptEngine load(String file) throws Exception {
        var e = (GraalJSScriptEngine)new AbstractScriptManager(){}.getInvocableScriptEngine(file);
        e.eval("var disposed=false, granted=0, spent=0, destination=0, active=true, rep=0, total=0, family=true, opened='', selected=-1;"
            + "var entry={getReputation:function(){return rep},getTotalReputation:function(){return total}};"
            + "var cm={dispose:function(){disposed=true},sendOk:function(){},sendNext:function(){},sendSimple:function(){},sendYesNo:function(){},sendGetNumber:function(){},"
            + "getMapId:function(){return 193000000},getPcCafeStatus:function(){return 'status'},welcomePcCafe:function(){return 'welcome'},"
            + "openNpc:function(n,s){opened=s},warp:function(m){destination=m},enterPcCafeRoad:function(i){selected=i;return ''},"
            + "buyPcCafeReward:function(i){granted++;selected=i;return 'ok'},exchangePcCafeMice:function(i){spent+=i;return 'ok'},"
            + "getPlayer:function(){return {getFamilyEntry:function(){return family?entry:null}}},isQuestStarted:function(){return active},"
            + "forceCompleteQuest:function(){active=false;granted++}};var qm=cm;");
        e.put("cafeRoads", server.pccafe.PcCafe.roads()); e.put("cafeRewards", server.pccafe.PcCafe.rewards());
        e.eval("cm.getPcCafeRoads=function(){return cafeRoads};cm.getPcCafeRewards=function(){return cafeRewards};");
        return e;
    }
    private void reply(GraalJSScriptEngine e, String fn, int mode, int choice) throws Exception { ((Invocable)e).invokeFunction(fn,mode,0,choice); }
    @ParameterizedTest @CsvSource({"2233,999,999,false","2233,1000,1000,true","2234,500,2000,false","2234,499,1999,false","2234,499,2000,true"})
    void familyRequirementsAndRepeatedReplies(int quest,int rep,int total,boolean success) throws Exception {
        try(var e=load("quest/"+quest+".js")) {
            e.eval("rep="+rep+";total="+total+";"); reply(e,"end",1,0); reply(e,"end",1,0);
            assertEquals(success?1:0,((Number)e.eval("granted")).intValue());
        }
    }
    @ParameterizedTest @ValueSource(ints={2233,2234})
    void familyCancellationOrMissingFamilyCannotComplete(int quest) throws Exception {
        try(var e=load("quest/"+quest+".js")) {
            e.eval("rep=1000;total=2000;");reply(e,"end",-1,0);assertEquals(0,((Number)e.eval("granted")).intValue());
            e.eval("family=false;");reply(e,"end",1,0);assertEquals(0,((Number)e.eval("granted")).intValue());
        }
    }
    @Test void everyCafeRewardNeedsConfirmationAndGrantsAtMostOnce() throws Exception {
        var rewards=server.pccafe.PcCafe.rewards();
        for(int i=0;i<rewards.size();i++) try(var e=load("npc/pc_cafe_vending.js")) {
            ((Invocable)e).invokeFunction("start");reply(e,"action",1,rewards.get(i).currency().equals("COINS")?0:1);
            reply(e,"action",1,i);assertEquals(0,((Number)e.eval("granted")).intValue());
            reply(e,"action",1,0);reply(e,"action",1,0);assertEquals(1,((Number)e.eval("granted")).intValue());
            assertEquals(i,((Number)e.eval("selected")).intValue());
        }
    }
    @Test void cafeCancelAndOriginalMachineRoute() throws Exception {
        try(var e=load("npc/pc_cafe_vending.js")) {
            ((Invocable)e).invokeFunction("start");reply(e,"action",1,0);reply(e,"action",1,0);reply(e,"action",0,0);
            assertEquals(0,((Number)e.eval("granted")).intValue());
        }
        try(var e=load("npc/pc_cafe_vending.js")) {
            ((Invocable)e).invokeFunction("start");reply(e,"action",1,2);assertEquals("pc_cafe_pq_vending",e.eval("opened"));
        }
    }
    @Test void computerExposesAllFifteenRoadsAndPreservesPartyQuest() throws Exception {
        for(int i=0;i<15;i++) try(var e=load("npc/pc_cafe_computer.js")) {
            ((Invocable)e).invokeFunction("start");reply(e,"action",1,0);reply(e,"action",1,i);
            assertEquals(i,((Number)e.eval("selected")).intValue());
        }
        try(var e=load("npc/pc_cafe_computer.js")) {
            ((Invocable)e).invokeFunction("start");reply(e,"action",1,1);assertEquals("pc_cafe_pq_computer",e.eval("opened"));
        }
    }
    @Test void billyExchangesOnlyOnceAndKeepsOriginalPrizeList() throws Exception {
        try(var e=load("npc/pc_cafe_billy.js")) {
            ((Invocable)e).invokeFunction("start");reply(e,"action",1,0);reply(e,"action",1,10);reply(e,"action",1,10);
            assertEquals(10,((Number)e.eval("spent")).intValue());
        }
        try(var e=load("npc/pc_cafe_billy.js")) {
            ((Invocable)e).invokeFunction("start");reply(e,"action",1,1);assertEquals("pc_cafe_pq_billy",e.eval("opened"));
        }
    }
    @Test void partyQuestConsumesCouponsInsteadOfDuplicatingThem() throws Exception {
        try(var e=load("npc/pc_cafe_pq_computer.js")) {
            e.eval("var cleared=0;cm.getMapId=function(){return 190000000};cm.getEventInstance=function(){return {isEventCleared:function(){return false},getIntProperty:function(){return 400},clearPQ:function(){cleared++}}};cm.isEventLeader=function(){return true};cm.haveItem=function(){return true};cm.gainItem=function(id,n){spent+=n};");
            ((Invocable)e).invokeFunction("start");assertEquals(-400,((Number)e.eval("spent")).intValue());assertEquals(1,((Number)e.eval("cleared")).intValue());
        }
    }
}
