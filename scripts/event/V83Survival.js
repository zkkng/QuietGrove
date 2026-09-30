function init() {}
function afterSetup(eim) {}
function playerEntry(eim,player) {}
function playerUnregistered(eim,player) {}
function getRun(eim){return eim.getObjectProperty("survival");}
function changedMap(eim,player,mapid){var run=getRun(eim);if(run!=null)run.changedMap(player);}
function playerDisconnected(eim,player){var run=getRun(eim);if(run!=null)run.leave(player,false);}
function playerDead(eim,player){var run=getRun(eim);if(run!=null)run.leave(player,true);}
function playerRevive(eim,player){playerDead(eim,player);return false;}
function playerExit(eim,player){var run=getRun(eim);if(run!=null)run.leave(player,true);}
function leftParty(eim,player){playerExit(eim,player);}
function disbandParty(eim){var run=getRun(eim);if(run!=null)run.finish(false);}
function scheduledTimeout(eim){disbandParty(eim);}
function monsterValue(eim,id){return 0;}
function monsterKilled(mob,eim,hasKiller){}
function allMonstersDead(eim){}
function cancelSchedule(){}
function dispose(eim){var run=getRun(eim);if(run!=null)run.disposed();}
