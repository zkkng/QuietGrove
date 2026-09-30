package scripting;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.script.Invocable;
import static org.junit.jupiter.api.Assertions.*;

class GameplayDialogTest {
    @BeforeAll static void quiet(){System.setProperty("polyglot.engine.WarnInterpreterOnly","false");}
    private GraalJSScriptEngine load(String file) throws Exception {
        var e=(GraalJSScriptEngine)new AbstractScriptManager(){}.getInvocableScriptEngine(file);
        e.eval("var calls=0, spent=0, destination=0, menu='', opened=0, level=200, map=926010000;"
            +"var player={getPartyQuest:function(){return null},getLevel:function(){return level},saveLocation:function(){},getSavedLocation:function(){return -1}};"
            +"var cm={getPlayer:function(){return player},getLevel:function(){return level},getMapId:function(){return map},getQuest:function(){return 29400},"
            +"sendOk:function(){},sendSimple:function(s){menu=s},sendDimensionalMirror:function(s){menu=s},sendYesNo:function(){},sendGetNumber:function(){},dispose:function(){},"
            +"warp:function(m){destination=m},openNpc:function(n){opened=n},playPortalSound:function(){},playerMessage:function(){}};var qm=cm;var pi=cm;");
        return e;
    }
    private void call(GraalJSScriptEngine e,String fn,int mode,int selection) throws Exception {((Invocable)e).invokeFunction(fn,mode,0,selection);}
    private int calls(GraalJSScriptEngine e) throws Exception{return ((Number)e.eval("calls")).intValue();}
    @ParameterizedTest @ValueSource(strings={"start","end"})
    void medalCancelAndRepeatedReplyCannotStartOrReward(String function) throws Exception {
        for(int cancel:new int[]{-1,0})try(var e=load("quest/medalQuest.js")){
            e.eval("Medals={interact:function(){calls++;return ''}};");call(e,function,cancel,0);call(e,function,1,0);assertEquals(0,calls(e));
        }
        try(var e=load("quest/medalQuest.js")){
            e.eval("Medals={interact:function(){calls++;return ''}};");call(e,function,1,0);call(e,function,1,0);assertEquals(1,calls(e));
        }
    }
    @ParameterizedTest @ValueSource(ints={9209002,9209003,9209004,9209005,9209006})
    void merchantsRejectForgedChoicesAndDoOneTrade(int npc) throws Exception {
        String stub="Market={hour:function(){return 1},menu:function(){return ''},stocked:function(v,i){return i==2001000},price:function(){return 100},trade:function(){calls++;return ''}};";
        try(var e=load("npc/"+npc+".js")){
            e.eval(stub);((Invocable)e).invokeFunction("start");call(e,"action",1,2001000);call(e,"action",1,0);call(e,"action",1,10);call(e,"action",1,10);assertEquals(1,calls(e));
        }
        try(var e=load("npc/"+npc+".js")){
            e.eval(stub);call(e,"action",1,1142000);assertEquals(0,calls(e));
        }
        try(var e=load("npc/"+npc+".js")){
            e.eval(stub);call(e,"action",1,2001000);call(e,"action",1,0);call(e,"action",0,0);assertEquals(0,calls(e));
        }
    }
    @ParameterizedTest @ValueSource(ints={9209007,9209008})
    void daisyClaimsOnlyOnce(int npc) throws Exception {
        try(var e=load("npc/"+npc+".js")){
            e.eval("Egg={claim:function(){calls++;return ''}};");call(e,"action",1,3);call(e,"action",1,3);assertEquals(1,calls(e));
        }
    }
    @ParameterizedTest @ValueSource(ints={1052115,2103013})
    void survivalEntryAndBonusCollectionsDoNotReplay(int npc) throws Exception {
        for(int mode:new int[]{0,1,2,5})try(var e=load("npc/"+npc+".js")){
            e.eval("Survival={enter:function(){calls++;return ''},collectTickets:function(){calls++;return ''}};");
            call(e,"action",1,mode);if(npc==2103013 && mode!=5)call(e,"action",1,0);
            call(e,"action",1,0);assertEquals(1,calls(e));
        }
        try(var e=load("npc/"+npc+".js")){
            e.eval("Survival={enter:function(){calls++;return ''}};");call(e,"action",0,0);assertEquals(0,calls(e));
        }
    }
    @Test void dalairDonationAndRetryAreSingleActions() throws Exception {
        try(var e=load("npc/9000040.js")){
            e.eval("Rankings={donate:function(){calls++;return ''}};");call(e,"action",1,3);call(e,"action",1,3);assertEquals(1,calls(e));
        }
        try(var e=load("npc/9000040.js")){
            e.eval("Medals={retryMenu:function(){return ''},retryMissing:function(){calls++;return ''}};");call(e,"action",1,4);assertEquals(0,calls(e));call(e,"action",1,129400);call(e,"action",1,129400);assertEquals(1,calls(e));
        }
    }
    @Test void mirrorExposesRestoredModesAndChecksMinimumLevel() throws Exception {
        try(var e=load("npc/9010022.js")){
            ((Invocable)e).invokeFunction("start");assertTrue(e.eval("menu").toString().contains("Nett's Pyramid"));assertTrue(e.eval("menu").toString().contains("Construction Site"));
            call(e,"action",1,5);assertEquals(926010000,((Number)e.eval("destination")).intValue());
        }
        try(var e=load("npc/9010022.js")){
            e.eval("level=20");((Invocable)e).invokeFunction("start");call(e,"action",1,6);assertEquals(0,((Number)e.eval("destination")).intValue());
        }
    }
    @Test void entrancesReachTheEventNpcAndExitsNeverUseMinusOne() throws Exception {
        for(String file:new String[]{"met_in","piramid_in00","nets_out","met_out"})try(var e=load("portal/"+file+".js")){
            ((Invocable)e).invokeFunction("enter",e.eval("pi"));
            if(file.equals("piramid_in00"))assertEquals(2103013,((Number)e.eval("opened")).intValue());
            else assertTrue(((Number)e.eval("destination")).intValue()>0);
        }
    }
    @Test void everyNativeMarketLayoutIsAccessible() throws Exception {
        for(int i=0;i<4;i++)try(var e=load("npc/9209001.js")){
            call(e,"action",1,i);assertEquals(680100000+i,((Number)e.eval("destination")).intValue());
        }
    }
}
