var Egg=Java.type("server.content.MarketEgg");
var status=0;
function start(){cm.sendSimple(Egg.status(cm.getPlayer()));}
function action(mode,type,selection){
    if(mode!=1 || status++>0){cm.dispose();return;}
    var reply;
    if(selection==0)reply=Egg.take(cm.getPlayer());
    else if(selection==1)reply=Egg.powder(cm.getPlayer());
    else if(selection==2)reply=Egg.feed(cm.getPlayer());
    else if(selection==3)reply=Egg.claim(cm.getPlayer());
    else{cm.dispose();return;}
    cm.sendOk(reply);cm.dispose();
}
