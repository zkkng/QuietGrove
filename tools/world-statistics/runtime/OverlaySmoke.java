package statistics.build;
import client.Character;
import client.Client;
import client.inventory.*;
import server.statistics.WorldStatistics;
import org.mockito.Mockito;
import java.util.*;
public final class OverlaySmoke {
 public static void main(String[] args)throws Exception{
  List<String> hooks=java.nio.file.Files.readAllLines(java.nio.file.Path.of(args[0]));
  for(String type:hooks)Class.forName(type,false,OverlaySmoke.class.getClassLoader());
  Character chr=Mockito.mock(Character.class);Client client=Mockito.mock(Client.class);
  Mockito.when(chr.getClient()).thenReturn(client);Mockito.when(chr.getWorld()).thenReturn(0);Mockito.when(chr.getMapId()).thenReturn(100000000);
  WorldStatistics.RECORDER.setEnabled(true);
  Item item=new Item(2000000,(short)1,(short)10);
  item.setQuantity((short)9);
  if(WorldStatistics.RECORDER.health().queued()!=0)throw new AssertionError("Unclassified decrement counted");
  var ctx=WorldStatistics.context();ctx.code=257;ctx.actor=chr;
  item.setQuantity((short)7);
  List<Long> amounts=new ArrayList<>();
  WorldStatistics.RECORDER.drain((metric,world,pop,assist,entity,region,reason,method,time,units)->{
   if(metric!=1 || entity!=2000000 || units!=2 || region!=100000000)throw new AssertionError("Wrong injected quantity fact");amounts.add(units);
  },100);
  if(amounts.size()!=1)throw new AssertionError("Injected hook absent");
  ctx.code=65536;item.setQuantity((short)6);
  if(WorldStatistics.RECORDER.health().queued()!=0)throw new AssertionError("Admin decrement counted");
  ctx.code=257;ctx.actor=chr;
  Inventory inventory=new Inventory(chr,InventoryType.USE,(byte)8);
  Item second=new Item(2000000,(short)1,(short)3);inventory.addItem(second);inventory.removeItem((short)1,(short)5,false);
  List<Long> removed=new ArrayList<>();
  WorldStatistics.RECORDER.drain((m,w,p,a,e,r,why,how,t,n)->removed.add(n),100);
  if(!removed.equals(List.of(3L)))throw new AssertionError("Actual removal/clamp counted incorrectly: "+removed);
  ctx.code=0;ctx.actor=null;
  System.out.println("OVERLAY_SMOKE_PASS verified-types="+hooks.size()+" quantity-hook/context/admin/actual-removal");
 }
}
