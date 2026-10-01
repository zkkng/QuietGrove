package statistics.build;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static net.bytebuddy.matcher.ElementMatchers.*;
public final class BuildOverlay {
 public static void main(String[] args)throws Exception{
  Path base=Path.of(args[0]),classes=Path.of(args[1]),output=Path.of(args[2]),resources=Path.of(args[3]);
  var locator=new ClassFileLocator.Compound(ClassFileLocator.ForJarFile.of(base.toFile()),ClassFileLocator.ForClassLoader.ofSystemLoader());
  var pool=TypePool.Default.of(locator);Map<String,byte[]> patches=new TreeMap<>();List<String> hooks=new ArrayList<>();
  try(JarFile jar=new JarFile(base.toFile())){
   for(var entry:Collections.list(jar.entries())){
    String path=entry.getName();if(!path.endsWith(".class") || path.contains("$"))continue;
    String name=path.substring(0,path.length()-6).replace('/','.');
    if(!name.startsWith("net.server.channel.handlers.") && !List.of("client.processor.action.PetAutopotProcessor","soloMapling.ArtificialPlayer.CompanionSystem.CompanionCombat","server.StatEffect","client.inventory.Inventory","client.inventory.manipulator.InventoryManipulator","client.inventory.Item","client.command.CommandsExecutor","server.quest.Quest","server.life.Monster","server.maps.MapleMap","client.Character","scripting.event.EventInstanceManager","scripting.AbstractPlayerInteraction","database.DatabaseMigrations").contains(name))continue;
    var type=pool.describe(name).resolve();var builder=new ByteBuddy().redefine(type,locator);boolean changed=false;
    if(name.startsWith("net.server.channel.handlers.") && (type.getSimpleName().startsWith("Use") || List.of("ScrollHandler","SkillBookHandler","ItemRewardHandler","RangedAttackHandler","MakerSkillHandler").contains(type.getSimpleName()))){builder=builder.visit(Advice.to(HookAdvice.Packet.class).on(named("handlePacket")));changed=true;}
    if(name.equals("client.processor.action.PetAutopotProcessor")){builder=builder.visit(Advice.to(HookAdvice.PetScope.class).on(named("runAutopotAction")));changed=true;}
    if(name.equals("soloMapling.ArtificialPlayer.CompanionSystem.CompanionCombat")){builder=builder.visit(Advice.to(HookAdvice.ActorScope.class).on(namedOneOf("potion","cureItem","pay","payAttack")));changed=true;}
    if(name.equals("server.StatEffect")){builder=builder.visit(Advice.to(HookAdvice.ActorScope.class).on(named("applyTo").and(takesArgument(0,named("client.Character")))));changed=true;}
    if(name.equals("client.inventory.Inventory")){builder=builder.visit(Advice.to(HookAdvice.InventoryScope.class).on(named("removeItem").and(takesArguments(3))));changed=true;}
    if(name.equals("client.inventory.manipulator.InventoryManipulator")){builder=builder.visit(Advice.to(HookAdvice.ConsumeFlag.class).on(named("removeFromSlot").and(takesArguments(6))));changed=true;}
    if(name.equals("client.inventory.Item")){builder=builder.visit(Advice.to(HookAdvice.Quantity.class).on(named("setQuantity")));changed=true;}
    if(name.equals("client.command.CommandsExecutor")){builder=builder.visit(Advice.to(HookAdvice.Admin.class).on(named("handle")));changed=true;}
    if(name.equals("server.quest.Quest")){builder=builder.visit(Advice.to(HookAdvice.QuestComplete.class).on(named("forceComplete"))).visit(Advice.to(HookAdvice.ActorScope.class).on(namedOneOf("start","complete").and(takesArgument(0,named("client.Character")))));changed=true;}
    if(name.equals("server.life.Monster")){builder=builder.visit(Advice.to(HookAdvice.Mob.class).on(named("disposeMapObject")));changed=true;}
    if(name.equals("server.maps.MapleMap")){builder=builder.visit(Advice.to(HookAdvice.MapKill.class).on(named("killMonster").and(takesArguments(5))));changed=true;}
    if(name.equals("client.Character")){builder=builder.visit(Advice.to(HookAdvice.Death.class).on(named("hpChangeAction")));changed=true;}
    if(name.equals("scripting.event.EventInstanceManager")){builder=builder.visit(Advice.to(HookAdvice.Pq.class).on(named("setEventCleared")));changed=true;}
    if(name.equals("scripting.AbstractPlayerInteraction")){builder=builder.visit(Advice.to(HookAdvice.ScriptSpend.class).on(named("gainItem").and(takesArguments(6)))).visit(Advice.to(HookAdvice.Jq.class).on(named("warp").and(takesArguments(2)).and(takesArgument(1,int.class))));changed=true;}
    if(name.equals("database.DatabaseMigrations")){builder=builder.visit(Advice.to(HookAdvice.Startup.class).on(named("runDatabaseMigrations")));changed=true;}
    if(changed){patches.put(path,builder.make().getBytes());hooks.add(name);}
   }
   if(hooks.size()<20)throw new IllegalStateException("Missing hooks: "+hooks);
   for(Path dir:List.of(classes,resources))try(var files=Files.walk(dir)){
    for(Path file:files.filter(Files::isRegularFile).toList()){
     String rel=dir.relativize(file).toString().replace('\\','/');
     if(dir.equals(classes) && !rel.startsWith("server/statistics/"))continue;patches.put(rel,Files.readAllBytes(file));
    }
   }
   Set<String> written=new HashSet<>();
   try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(output))){
    for(var entry:Collections.list(jar.entries())){
     String path=entry.getName();if(path.equals("META-INF/INDEX.LIST"))continue;if(path.matches("META-INF/[^/]+\\.(SF|RSA|DSA)"))throw new IllegalStateException("Signed/indexed baseline");
     JarEntry target=new JarEntry(path);target.setTime(entry.getTime());out.putNextEntry(target);
     byte[] bytes=patches.get(path);if(bytes!=null)out.write(bytes);else if(!entry.isDirectory())try(var in=jar.getInputStream(entry)){in.transferTo(out);}
     out.closeEntry();written.add(path);
    }
    for(var patch:patches.entrySet())if(written.add(patch.getKey())){JarEntry e=new JarEntry(patch.getKey());e.setTime(0);out.putNextEntry(e);out.write(patch.getValue());out.closeEntry();}
   }
   Files.write(output.resolveSibling("hook-classes.txt"),hooks);
   System.out.println("OVERLAY_BUILT transformed="+hooks.size()+" added-or-replaced="+patches.size());
  }
 }
}
