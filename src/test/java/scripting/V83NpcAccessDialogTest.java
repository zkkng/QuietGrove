package scripting;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import javax.script.Invocable;
import static org.junit.jupiter.api.Assertions.*;

class V83NpcAccessDialogTest {
    @BeforeAll static void quiet() { System.setProperty("polyglot.engine.WarnInterpreterOnly", "false"); }

    private GraalJSScriptEngine load(int npc) throws Exception {
        var e = (GraalJSScriptEngine) new AbstractScriptManager(){}.getInvocableScriptEngine("npc/" + npc + ".js");
        assertNotNull(e);
        e.eval("""
            var state = {mesos:5000, items:{}, capacity:true, event:true, entry:'true', warp:0, warps:0,
                         disposed:false, gender:0, hair:30003, styles:[], changes:0, filtered:false, couponSpent:0};
            var player = {
                getMeso:function(){return state.mesos}, haveItem:function(i){return (state.items[i]||0)>0},
                canHold:function(){return state.capacity}, getGender:function(){return state.gender},
                getHair:function(){return state.hair}
            };
            var cm = {
                getPlayer:function(){return player}, dispose:function(){state.disposed=true},
                sendSimple:function(){}, sendOk:function(){}, sendYesNo:function(){},
                sendStyle:function(s,a){state.styles=a.slice()},
                haveItem:function(i){return player.haveItem(i)}, canHold:function(){return state.capacity},
                itemQuantity:function(i){return state.items[i]||0},
                gainMeso:function(n){state.mesos+=n}, gainItem:function(i,n){
                    n=n===undefined?1:n; state.items[i]=(state.items[i]||0)+n;
                    if(state.items[i]<0) throw Error('Item debit without inventory');
                    if(Math.floor(i/10000)==515 && n<0) state.couponSpent-=n;
                },
                getEventManager:function(){return state.event?{getProperty:function(){return state.entry}}:null},
                warp:function(m){state.warp=m;state.warps++},
                getCosmeticItem:function(i){return state.filtered?-1:i}, isCosmeticEquipped:function(){return false},
                setHair:function(i){if(!Number.isInteger(i)||i<30000)throw Error('Invalid hair');state.hair=i;state.changes++}
            };
            """);
        return e;
    }
    private void start(GraalJSScriptEngine e) throws Exception { ((Invocable)e).invokeFunction("start"); }
    private void reply(GraalJSScriptEngine e,int mode,int choice) throws Exception {
        ((Invocable)e).invokeFunction("action", mode, 0, choice);
    }
    private int value(GraalJSScriptEngine e,String expression) throws Exception { return ((Number)e.eval(expression)).intValue(); }

    @ParameterizedTest @CsvSource({"9270041,4031731,540010100", "9270038,4031732,540010001"})
    void airlineSellsAndBoardsExactlyOnce(int npc,int ticket,int destination) throws Exception {
        try(var e=load(npc)) {
            start(e);reply(e,1,0);reply(e,1,0);reply(e,1,0);
            assertEquals(0,value(e,"state.mesos"));assertEquals(1,value(e,"state.items["+ticket+"]"));
        }
        try(var e=load(npc)) {
            e.eval("state.items["+ticket+"]=1;");start(e);reply(e,1,1);reply(e,1,0);reply(e,1,0);
            assertEquals(destination,value(e,"state.warp"));assertEquals(1,value(e,"state.warps"));
            assertEquals(0,value(e,"state.items["+ticket+"]"));
        }
    }

    @ParameterizedTest @CsvSource({"9270041,4031731", "9270038,4031732"})
    void airlineFailuresNeverChargeOrConsumeTicket(int npc,int ticket) throws Exception {
        for(String setup:new String[]{"state.mesos=4999", "state.capacity=false", "state.items["+ticket+"]=1"}) {
            try(var e=load(npc)) {
                e.eval(setup);int before=value(e,"state.mesos");start(e);reply(e,1,0);reply(e,1,0);
                assertEquals(before,value(e,"state.mesos"));assertEquals(0,value(e,"state.warps"));
            }
        }
        for(String setup:new String[]{"state.event=false", "state.entry='false'"}) {
            try(var e=load(npc)) {
                e.eval("state.items["+ticket+"]=1;"+setup);start(e);reply(e,1,1);reply(e,1,0);
                assertEquals(1,value(e,"state.items["+ticket+"]"));assertEquals(0,value(e,"state.warps"));
            }
        }
        try(var e=load(npc)) {
            start(e);reply(e,1,1);reply(e,1,0);assertEquals(0,value(e,"state.warps"));
        }
        for(int mode:new int[]{-1,0}) for(int stage=0;stage<2;stage++) try(var e=load(npc)) {
            start(e);if(stage==1)reply(e,1,0);reply(e,mode,0);reply(e,1,0);reply(e,1,0);
            assertEquals(5000,value(e,"state.mesos"));assertEquals(0,value(e,"state.warps"));
        }
        for(int bad:new int[]{-1,2,Integer.MAX_VALUE}) try(var e=load(npc)) {
            start(e);reply(e,1,bad);reply(e,1,0);assertEquals(5000,value(e,"state.mesos"));
        }
    }

    @ParameterizedTest @CsvSource({"2012025,4031576,200000152", "2102000,4031045,260000110"})
    void genieRechecksTicketAndDepartureBeforeBoarding(int npc,int ticket,int destination) throws Exception {
        try(var e=load(npc)) {
            e.eval("state.items["+ticket+"]=1;");start(e);reply(e,1,0);reply(e,1,0);
            assertEquals(destination,value(e,"state.warp"));assertEquals(1,value(e,"state.warps"));
            assertEquals(0,value(e,"state.items["+ticket+"]"));
        }
        for(String setup:new String[]{"state.items["+ticket+"]=0", "state.entry='false'", "state.event=false"}) {
            try(var e=load(npc)) {
                e.eval("state.items["+ticket+"]=1;");start(e);e.eval(setup);reply(e,1,0);
                assertEquals(0,value(e,"state.warps"));
                assertEquals(setup.startsWith("state.items")?0:1,value(e,"state.items["+ticket+"]"));
            }
        }
        for(String setup:new String[]{"state.items["+ticket+"]=0", "state.entry='false'", "state.event=false"}) {
            try(var e=load(npc)) {
                e.eval("state.items["+ticket+"]=1;"+setup);start(e);reply(e,1,0);
                assertEquals(0,value(e,"state.warps"));
            }
        }
        for(int mode:new int[]{-1,0}) try(var e=load(npc)) {
            e.eval("state.items["+ticket+"]=1;");start(e);reply(e,mode,0);reply(e,1,0);
            assertEquals(0,value(e,"state.warps"));assertEquals(1,value(e,"state.items["+ticket+"]"));
        }
    }

    @ParameterizedTest @CsvSource({"0,1,5150033", "1,1,5150033", "0,2,5151028", "1,2,5151028"})
    void vipEveryHairAndColorChoiceWorksIncludingIndicesOneAndTwo(int gender,int service,int coupon) throws Exception {
        for(int i=0;i<(service==1?9:8);i++) try(var e=load(9270036)) {
            e.eval("state.gender="+gender+";state.hair="+(gender==0?30003:31013)+";state.items["+coupon+"]=2;");
            start(e);reply(e,1,service);int expected=value(e,"state.styles["+i+"]");
            reply(e,1,i);reply(e,1,i);
            assertEquals(expected,value(e,"state.hair"));assertEquals(1,value(e,"state.changes"));
            assertEquals(1,value(e,"state.items["+coupon+"]"));
        }
    }

    @ParameterizedTest @CsvSource({"0,1,5150032", "1,1,5150032", "0,2,5151027", "1,2,5151027"})
    void regularSalonChoosesOnlyAvailableHairOrColorAndConsumesOneCoupon(int gender,int service,int coupon) throws Exception {
        for(double random:new double[]{0,0.5,0.999999}) try(var e=load(9270037)) {
            e.eval("state.gender="+gender+";state.hair="+(gender==0?30003:31013)+";state.items["+coupon+"]=2;Math.random=function(){return "+random+"};");
            start(e);reply(e,1,service);
            int expected=value(e,(service==1?"hairnew":"haircolor")+"[Math.floor(Math.random()*"+(service==1?"hairnew":"haircolor")+".length)]");
            reply(e,1,0);reply(e,1,0);
            assertEquals(expected,value(e,"state.hair"));assertEquals(1,value(e,"state.changes"));
            assertEquals(1,value(e,"state.items["+coupon+"]"));
        }
    }

    @ParameterizedTest @CsvSource({"9270036,1,5150033", "9270036,2,5151028", "9270037,1,5150032", "9270037,2,5151027"})
    void salonsRejectCancellationMissingCouponsAndEmptyStyleLists(int npc,int service,int coupon) throws Exception {
        for(String setup:new String[]{"state.items["+coupon+"]=0", "state.filtered=true"}) try(var e=load(npc)) {
            e.eval("state.items["+coupon+"]=2;"+setup);start(e);reply(e,1,service);reply(e,1,0);
            assertEquals(0,value(e,"state.changes"));assertEquals(0,value(e,"state.couponSpent"));
        }
        for(int mode:new int[]{-1,0}) for(int stage=0;stage<2;stage++) try(var e=load(npc)) {
            e.eval("state.items["+coupon+"]=2;");start(e);if(stage==1)reply(e,1,service);
            reply(e,mode,0);reply(e,1,service);reply(e,1,0);
            assertEquals(0,value(e,"state.changes"));assertEquals(2,value(e,"state.items["+coupon+"]"));
        }
        for(int bad:new int[]{-1,0,3,Integer.MAX_VALUE}) try(var e=load(npc)) {
            e.eval("state.items["+coupon+"]=2;");start(e);reply(e,1,bad);reply(e,1,0);
            assertEquals(0,value(e,"state.changes"));assertEquals(0,value(e,"state.couponSpent"));
        }
        if(npc==9270036) for(int bad:new int[]{-1,99,Integer.MAX_VALUE}) try(var e=load(npc)) {
            e.eval("state.items["+coupon+"]=2;");start(e);reply(e,1,service);reply(e,1,bad);
            assertEquals(0,value(e,"state.changes"));assertEquals(0,value(e,"state.couponSpent"));
        }
    }
}
