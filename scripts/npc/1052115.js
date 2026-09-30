var Survival=Java.type("server.partyquest.Pyramid");
var Medals=Java.type("server.content.Medals");
var subway=true, status=0, chosen=0, party=false;
function start(){
    var run=cm.getPlayer().getPartyQuest();
    if(run!=null){cm.sendYesNo("Leave this survival challenge?");status=10;return;}
    var lobby=subway?910320000:926010000;
    if(cm.getMapId()!=lobby){
        cm.sendSimple("Your results and uncollected bonus passes are saved.\r\n#L5#Collect earned bonus passes#l\r\n#L6#Return to the waiting area#l");return;
    }
    cm.sendSimple(""+(subway?"Dusty Platform: three two-minute stages. Level 25+.":"Nett's Pyramid: five stages, two minutes then three minutes each. Level 40+.")+" Keep hunting to maintain the Act Gauge; misses reduce it. Solo or parties of up to four are welcome. Higher-level players may revisit.\r\n"+(subway?"":"Avoid Pharaoh Yetis unless using Rage of Pharaoh. Earn a charge every 500 points, up to six; use the native skill or type @pharaoh.\r\n")+"#L0#Enter alone#l\r\n#L1#Enter with my party#l\r\n#L2#Use a bonus pass (solo, 60 seconds)#l\r\n#L3#Claim the hunting medal#l\r\n#L5#Collect earned bonus passes#l");
}
function action(mode,type,selection){
    if(mode!=1){cm.dispose();return;}
    if(status==10){status=2;var run=cm.getPlayer().getPartyQuest();cm.dispose();if(run!=null)run.leave(cm.getPlayer(),true);return;}
    if(status==0){
        if(selection==5){status=2;cm.sendOk(Survival.collectTickets(cm.getPlayer(),subway));cm.dispose();return;}
        if(selection==6){status=2;cm.warp(subway?910320000:926010000,0);cm.dispose();return;}
        if(selection==3){status=2;cm.sendOk(Medals.interact(cm.getPlayer(),subway?29931:29932));cm.dispose();return;}
        if(selection<0 || selection>2){cm.dispose();return;}
        chosen=selection;party=selection==1;
        if(!subway){status=1;cm.sendSimple("Select difficulty. Bonus passes must match.\r\n#L0#Easy (40+)#l\r\n#L1#Normal (46+)#l\r\n#L2#Hard (51+)#l\r\n#L3#Hell (61+)#l");return;}
        selection=0;
    }
    if(status==0 || status==1){
        status=2;var result=Survival.enter(cm.getPlayer(),selection,subway,party,chosen==2);
        if(result.length>0)cm.sendOk(result);cm.dispose();
    }
}
