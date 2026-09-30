var Market=Java.type("server.content.SevenDayMarket");
var status=0, item=0, buying=true, quotedHour=0;
var vendor=9209003;
function start(){quotedHour=Market.hour();cm.sendSimple(Market.menu(cm.getPlayer(),vendor,quotedHour));}
function action(mode,type,selection){
    if(mode!=1){cm.dispose();return;}
    if(status==0){
        if(!Market.stocked(vendor,selection)){cm.dispose();return;}
        item=selection;status=1;
        cm.sendSimple("#t"+item+"#\r\n#L0#Buy for "+Market.price(item,quotedHour,true)+" mesos each#l\r\n#L1#Sell market purchases for "+Market.price(item,quotedHour,false)+" mesos each#l");
    }else if(status==1){
        if(selection!=0 && selection!=1){cm.dispose();return;}
        buying=selection==0;status=2;cm.sendGetNumber("How many?",1,1,1000);
    }else if(status==2){
        status=3;cm.sendOk(Market.trade(cm.getPlayer(),vendor,item,selection,buying,quotedHour));cm.dispose();
    }else cm.dispose();
}
