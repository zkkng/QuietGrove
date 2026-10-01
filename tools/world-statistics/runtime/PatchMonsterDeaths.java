package statistics.build;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.implementation.StubMethod;
import net.bytebuddy.pool.TypePool;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Narrow patch for a JAR which already contains the original statistics overlay. */
public final class PatchMonsterDeaths {
 public static void main(String[] args)throws Exception {
  Path base=Path.of(args[0]),classes=Path.of(args[1]),output=Path.of(args[2]);
  try(JarFile check=new JarFile(base.toFile())) {
   if(check.getJarEntry("server/statistics/MonsterDeathTracking.class")!=null)throw new IllegalStateException("Monster patch already installed");
  }
  var locator=new ClassFileLocator.Compound(ClassFileLocator.ForJarFile.of(base.toFile()),ClassFileLocator.ForClassLoader.ofSystemLoader());
  var pool=TypePool.Default.of(locator);Map<String,byte[]> patches=new TreeMap<>();
  for(String name:List.of("server.life.Monster","server.maps.MapleMap","server.statistics.WorldStatistics")) {
   var builder=new ByteBuddy().redefine(pool.describe(name).resolve(),locator);
   if(name.equals("server.life.Monster"))builder=builder.visit(Advice.to(HookAdvice.Mob.class).on(named("disposeMapObject")));
   if(name.equals("server.maps.MapleMap"))builder=builder.visit(Advice.to(HookAdvice.MapKill.class).on(named("killMonster").and(takesArguments(5))));
   if(name.equals("server.statistics.WorldStatistics"))builder=builder.method(named("monster")).intercept(StubMethod.INSTANCE);
   patches.put(name.replace('.','/')+".class",builder.make().getBytes());
  }
  try(var files=Files.walk(classes.resolve("server/statistics"))) {
   for(Path file:files.filter(Files::isRegularFile).toList()) {
    String path=classes.relativize(file).toString().replace('\\','/');
    if(path.contains("MonsterDeathTracking"))patches.put(path,Files.readAllBytes(file));
   }
  }
  try(JarFile jar=new JarFile(base.toFile());JarOutputStream out=new JarOutputStream(Files.newOutputStream(output))) {
   Set<String> written=new HashSet<>();
   for(var entry:Collections.list(jar.entries())) {
    String path=entry.getName(); if(path.equals("META-INF/INDEX.LIST"))throw new IllegalStateException("Indexed baseline");
    if(path.matches("META-INF/[^/]+\\.(SF|RSA|DSA)"))throw new IllegalStateException("Signed baseline");
    JarEntry target=new JarEntry(path);target.setTime(entry.getTime());out.putNextEntry(target);
    if(patches.containsKey(path))out.write(patches.get(path));
    else if(!entry.isDirectory())try(var in=jar.getInputStream(entry)){in.transferTo(out);}
    out.closeEntry();written.add(path);
   }
   for(var patch:patches.entrySet())if(written.add(patch.getKey())) {
    var e=new JarEntry(patch.getKey());e.setTime(0);out.putNextEntry(e);out.write(patch.getValue());out.closeEntry();
   }
  }
  System.out.println("MONSTER_DEATH_PATCH_PASS changed="+patches.keySet());
 }
}
