package server.content;

import org.junit.jupiter.api.Test;
import provider.Data;
import provider.DataTool;
import provider.wz.XMLDomMapleData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ContentAssetTest {
    @org.junit.jupiter.api.BeforeAll static void itemData() throws Exception { GameplayTestData.initializeItems(); }
    private Data read(String file) throws Exception {
        Path path=Path.of(file);assertTrue(Files.isRegularFile(path),file);
        try(var in=new java.io.FileInputStream(path.toFile())){return new XMLDomMapleData(in,path.getParent());}
    }
    private Data map(int id) throws Exception{return read("wz/Map.wz/Map/Map"+(id/100000000)+"/"+id+".img.xml");}
    @Test void allFortySixSurvivalStagesHaveGaugeAndMonsterMetadata() throws Exception {
        Set<Integer> maps=new HashSet<>();
        for(int base:new int[]{926010000,926020000})for(int mode=0;mode<4;mode++)for(int stage=1;stage<=5;stage++)maps.add(base+mode*1000+stage*100);
        for(int base:new int[]{910320000,910330000})for(int stage=1;stage<=3;stage++)maps.add(base+stage*100);
        assertEquals(46,maps.size());Set<Integer> mobs=new HashSet<>();
        for(int id:maps){
            Data data=map(id);assertNotNull(data.getChildByPath("mobMassacre/gauge"),"gauge "+id);
            int count=0;
            for(Data life:data.getChildByPath("life"))if(DataTool.getString("type",life,"").equals("m")){mobs.add(Integer.parseInt(DataTool.getString("id",life)));count++;}
            assertTrue(count>0,"stage has no monsters: "+id);
        }
        mobs.addAll(Set.of(9700019,9700020,9700029,9700038));
        for(int id:mobs)assertNotNull(read(String.format("wz/Mob.wz/%07d.img.xml",id)).getChildByPath("info"));
    }
    @Test void allResultsAndEntrancesHaveAnExitNpcAndValidPortalScripts() throws Exception {
        for(int id:new int[]{926010000,926010001,926020001,910320000,910320001,910330001}){
            Data data=map(id);int npc=id>=926000000?2103013:1052115;boolean found=false;
            for(Data life:data.getChildByPath("life"))if(DataTool.getString("id",life,"").equals(Integer.toString(npc)))found=true;
            assertTrue(found,"result/entry NPC "+id);
            for(Data portal:data.getChildByPath("portal")){
                String script=DataTool.getString("script",portal,"");
                if(!script.isEmpty())assertTrue(Files.isRegularFile(Path.of("scripts/portal/"+script+".js")),script);
            }
        }
    }
    @Test void marketStockEggPrizesAndPqEquipmentExistInV83() throws Exception {
        Data consumables=read("wz/Item.wz/Consume/0200.img.xml"), foods=read("wz/Item.wz/Consume/0202.img.xml");
        for(int[] stock:SevenDayMarket.STOCK)for(int id:stock)assertNotNull((id/10000==200?consumables:foods).getChildByPath("0"+id+"/info"),"stock "+id);
        for(int id:new int[]{4220089,4220125,4032135,4290000})assertNotNull(read("wz/Item.wz/Etc/0"+(id/10000)+".img.xml").getChildByPath("0"+id+"/info"),"egg item "+id);
        Data equip=read("wz/String.wz/Eqp.img.xml");String xml=Files.readString(Path.of("wz/String.wz/Eqp.img.xml"));
        for(var rule:PqRanks.RULES.values())assertTrue(xml.contains("name=\""+rule.item()+"\""),"PQ equipment "+rule.item());
    }
}
