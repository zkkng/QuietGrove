package statistics.build;
import net.bytebuddy.asm.Advice;
import server.statistics.WorldStatistics;
import server.statistics.MonsterDeathTracking;
import client.inventory.Item;
public final class HookAdvice {
 public static class Packet {
  @Advice.OnMethodEnter(suppress=Throwable.class) static int enter(@Advice.Argument(1) Object client,@Advice.Origin("#t") String type,@Advice.Local("oldActor") Object oldActor){var ctx=WorldStatistics.context();oldActor=ctx.actor;int old=ctx.code;int reason=type.endsWith("ScrollHandler")?4:type.endsWith("RangedAttackHandler")?2:type.endsWith("MakerSkillHandler")?5:1;WorldStatistics.scope(reason|(1<<8),WorldStatistics.actor(client));return old;}
  @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class) static void exit(@Advice.Enter int old,@Advice.Local("oldActor") Object oldActor){var ctx=WorldStatistics.context();ctx.code=old;ctx.actor=oldActor;}
 }
 public static class ActorScope {
  @Advice.OnMethodEnter(suppress=Throwable.class) static int enter(@Advice.Argument(0) Object player,@Advice.Origin("#m") String operation,@Advice.Local("oldActor") Object oldActor){var ctx=WorldStatistics.context();oldActor=ctx.actor;int old=ctx.code;int reason=operation.equals("potion")||operation.equals("cureItem")?1:operation.equals("payAttack")?2:operation.equals("start")||operation.equals("complete")?6:3;int method=((client.Character)player).getClient() instanceof client.BotClient?3:1;WorldStatistics.scope(reason|(method<<8),player);return old;}
  @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class) static void exit(@Advice.Enter int old,@Advice.Local("oldActor") Object oldActor){var ctx=WorldStatistics.context();ctx.code=old;ctx.actor=oldActor;}
 }
 public static class PetScope {
  @Advice.OnMethodEnter(suppress=Throwable.class) static int enter(@Advice.Argument(0) Object client,@Advice.Local("oldActor") Object oldActor){var ctx=WorldStatistics.context();oldActor=ctx.actor;int old=ctx.code;WorldStatistics.scope(1|(2<<8),WorldStatistics.actor(client));return old;}
  @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class) static void exit(@Advice.Enter int old,@Advice.Local("oldActor") Object oldActor){var ctx=WorldStatistics.context();ctx.code=old;ctx.actor=oldActor;}
 }
 public static class InventoryScope {
  @Advice.OnMethodEnter(suppress=Throwable.class) static Object enter(@Advice.FieldValue("owner") Object owner){var ctx=WorldStatistics.context();Object old=ctx.actor;if(owner!=null)ctx.actor=owner;return old;}
  @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class) static void exit(@Advice.Enter Object old){WorldStatistics.context().actor=old;}
 }
 public static class ConsumeFlag {
  @Advice.OnMethodEnter(suppress=Throwable.class) static int enter(@Advice.Argument(0) Object client,@Advice.Argument(5) boolean consume,@Advice.Local("oldActor") Object oldActor){var ctx=WorldStatistics.context();oldActor=ctx.actor;int old=ctx.code;if(consume)WorldStatistics.scope(2|(1<<8),WorldStatistics.actor(client));return old;}
  @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class) static void exit(@Advice.Enter int old,@Advice.Local("oldActor") Object oldActor){var ctx=WorldStatistics.context();ctx.code=old;ctx.actor=oldActor;}
 }
 public static class ScriptSpend {
  @Advice.OnMethodEnter(suppress=Throwable.class) static int enter(@Advice.This Object script,@Advice.Argument(1) short quantity,@Advice.Local("oldActor") Object oldActor){var ctx=WorldStatistics.context();oldActor=ctx.actor;int old=ctx.code;if(quantity<0)WorldStatistics.scope(5<<8,WorldStatistics.scriptActor(script));return old;}
  @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class) static void exit(@Advice.Enter int old,@Advice.Local("oldActor") Object oldActor){var ctx=WorldStatistics.context();ctx.code=old;ctx.actor=oldActor;}
 }
 public static class Admin {
  @Advice.OnMethodEnter(suppress=Throwable.class) static int enter(@Advice.Argument(1) String message){var ctx=WorldStatistics.context();int old=ctx.code;if(message.startsWith("!"))ctx.code=old|65536;return old;}
  @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class) static void exit(@Advice.Enter int old){WorldStatistics.context().code=old;}
 }
 public static class Quantity {
  @Advice.OnMethodEnter(suppress=Throwable.class) static int enter(@Advice.This Object item){return ((Item)item).getQuantity();}
  @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class) static void exit(@Advice.This Object item,@Advice.Enter int before){WorldStatistics.quantityChanged(item,before);}
 }
 public static class QuestComplete {
  @Advice.OnMethodEnter(suppress=Throwable.class) static int enter(@Advice.This Object quest,@Advice.Argument(0) Object chr){return WorldStatistics.questBefore(quest,chr);}
  @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class) static void exit(@Advice.This Object quest,@Advice.Argument(0) Object chr,@Advice.Enter int before){WorldStatistics.questAfter(quest,chr,before);}
 }
 public static class Mob {
  @Advice.OnMethodEnter(suppress=Throwable.class) static MonsterDeathTracking.Scope enter(@Advice.This Object mob){return MonsterDeathTracking.beforeDispose(mob);}
  @Advice.OnMethodExit(suppress=Throwable.class) static void exit(@Advice.This Object mob,@Advice.Enter MonsterDeathTracking.Scope scope){MonsterDeathTracking.afterDispose(mob,scope);}
 }
 public static class MapKill {
  @Advice.OnMethodEnter(suppress=Throwable.class) static MonsterDeathTracking.Scope enter(@Advice.This Object map,@Advice.Argument(0) Object mob,@Advice.Argument(1) Object killer){return MonsterDeathTracking.enter(map,mob,killer);}
  @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class) static void exit(@Advice.Enter MonsterDeathTracking.Scope scope){if(scope!=null)MonsterDeathTracking.exit(scope);}
 }
 public static class Death {
  @Advice.OnMethodEnter(suppress=Throwable.class) static void enter(@Advice.This Object chr,@Advice.Argument(0) int oldHp){WorldStatistics.death(chr,oldHp);}
 }
 public static class Pq {
  @Advice.OnMethodEnter(suppress=Throwable.class) static Object enter(@Advice.This Object eim){return WorldStatistics.pqBefore(eim);}
  @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class) static void exit(@Advice.This Object eim,@Advice.Enter Object state){WorldStatistics.pqAfter(eim,state);}
 }
 public static class Jq {
  @Advice.OnMethodEnter(suppress=Throwable.class) static int enter(@Advice.This Object api,@Advice.Local("oldMap") int map){map=((scripting.AbstractPlayerInteraction)api).getPlayer().getMapId();return WorldStatistics.jqBefore(api);}
  @Advice.OnMethodExit(suppress=Throwable.class) static void exit(@Advice.This Object api,@Advice.Enter int npc,@Advice.Local("oldMap") int map){WorldStatistics.jqAfter(api,npc,map);}
 }
 public static class Startup {
  @Advice.OnMethodExit(suppress=Throwable.class) static void exit(){WorldStatistics.start();}
 }
}
