package statistics.build;
import client.Character;
import client.Client;
import client.BotClient;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;
import server.statistics.WorldStatistics;
import server.statistics.MonsterDeathTracking;
import org.mockito.Mockito;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;

public final class MonsterDeathSmoke {
 static final List<String> facts=new ArrayList<>();
 static void drain(){WorldStatistics.RECORDER.drain((m,w,p,a,e,r,why,how,t,n)->facts.add(m+":"+p+":"+e+":"+r+":"+n),100);}
 static Monster monster(){MonsterStats stats=new MonsterStats();stats.setHp(10);return new Monster(100100,stats);}
 static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 static boolean remove(MapleMap map,Monster mob)throws Exception {
  Method method=MapleMap.class.getDeclaredMethod("removeKilledMonsterObject",Monster.class);method.setAccessible(true);
  return (boolean)method.invoke(map,mob);
 }
 static void dispose(MapleMap map,Monster mob,Character killer)throws Exception {
  var scope=MonsterDeathTracking.enter(map,mob,killer);
  try{remove(map,mob);}finally{MonsterDeathTracking.exit(scope);}
 }
 public static void main(String[] args)throws Exception {
  WorldStatistics.RECORDER.setEnabled(true);
  Character human=mock(Character.class);when(human.getClient()).thenReturn(mock(Client.class));when(human.getMapId()).thenReturn(999);
  Character bot=mock(Character.class);when(bot.getClient()).thenReturn(mock(BotClient.class));
  MapleMap map=mock(MapleMap.class);when(map.getId()).thenReturn(100000000);
  Field count=MapleMap.class.getDeclaredField("spawnedMonstersOnMap");count.setAccessible(true);count.set(map,new AtomicInteger(100));
  // Private removeKilledMonsterObject executes its real lock, registry removal and HP disposal.
  Monster dead=monster();check(dead.applyAndGetHpDamage(3,false)==3 && dead.getHp()==7,"nonlethal HP");
  var nonlethal=MonsterDeathTracking.enter(map,dead,human);MonsterDeathTracking.afterDispose(dead,MonsterDeathTracking.beforeDispose(dead));MonsterDeathTracking.exit(nonlethal);
  drain();check(facts.isEmpty(),"nonlethal counted");
  check(dead.applyAndGetHpDamage(99,false)==7 && dead.getHp()==0,"lethal clamp");
  dispose(map,dead,human);check(dead.getHp()==-1,"real disposal ordering");
  WorldStatistics.monster(dead,human);dispose(map,dead,human);drain();
  check(facts.equals(List.of("2:0:100100:100000000:1")),"missing/duplicate death or killer's wrong map: "+facts);
  Monster botDead=monster();botDead.applyAndGetHpDamage(10,false);dispose(map,botDead,bot);drain();
  check(facts.size()==2 && facts.get(1).equals("2:1:100100:100000000:1"),"bot classification");
  Monster cleanup=monster();cleanup.applyAndGetHpDamage(10,false);dispose(map,cleanup,null);
  Monster forced=monster();dispose(map,forced,human);
  WorldStatistics.context().code=65536;Monster admin=monster();admin.applyAndGetHpDamage(10,false);dispose(map,admin,human);WorldStatistics.context().code=0;
  Monster unrelated=monster();unrelated.applyAndGetHpDamage(10,false);
  var parent=MonsterDeathTracking.enter(map,monster(),human);unrelated.disposeMapObject();
  var inner=MonsterDeathTracking.enter(map,unrelated,null);check(MonsterDeathTracking.beforeDispose(unrelated)==null,"null scope inherits actor");MonsterDeathTracking.exit(inner);MonsterDeathTracking.exit(parent);
  drain();check(facts.size()==2,"cleanup/forced/admin/unrelated counted");
  // Execute the actual advised canonical map kill method; removal returns before rewards if it was already disposed.
  doCallRealMethod().when(map).killMonster(any(Monster.class),any(Character.class),anyBoolean(),anyInt(),anyShort());
  map.killMonster(dead,human,false,1,(short)0);
  try(var bosses=mockStatic(soloMapling.ArtificialPlayer.CompanionSystem.BossRuntime.class);
      var incidents=mockStatic(server.events.gm.IncidentService.class);
      var trainers=mockStatic(server.trainer.TrainerService.class)) {
   incidents.when(server.events.gm.IncidentService::getInstance).thenReturn(mock(server.events.gm.IncidentService.class));
   trainers.when(server.trainer.TrainerService::getInstance).thenReturn(mock(server.trainer.TrainerService.class));
   Monster canonical=spy(monster());doReturn(-1).when(canonical).getBuffToGive();doReturn(human).when(canonical).killBy(human);doNothing().when(canonical).dispatchMonsterKilled(anyBoolean());
   canonical.applyAndGetHpDamage(10,false);map.killMonster(canonical,human,false,1,(short)0);drain();
   check(facts.size()==3 && canonical.getHp()==-1,"canonical map-kill advice absent: "+facts);
   map.killMonster(canonical,human,false,1,(short)0);drain();check(facts.size()==3,"canonical repeat counted");
  }
  Monster outside=monster();outside.applyAndGetHpDamage(10,false);outside.disposeMapObject();drain();check(facts.size()==3,"map scope leaked");
  System.out.println("MONSTER_DEATH_SMOKE_PASS real-HP-damage/real-map-removal/disposal-minus-one/once/human/bot/map/admin/cleanup/forced/nesting/scope-exit");
 }
}
