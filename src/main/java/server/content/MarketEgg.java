package server.content;

import client.Character;
import client.QuestStatus;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import java.time.LocalDate;
import java.time.ZoneOffset;
import server.quest.Quest;

public final class MarketEgg {
    private MarketEgg() {}
    public static final int EGG=4220089, GOLD=4220125, POWDER=4032135, HAT=1002851, EFFECT=4290000, INFO=8252;
    public static long day() {return LocalDate.now(ZoneOffset.UTC).toEpochDay();}
    public static boolean owns(Character chr) {return chr.getContentState().get("egg.active")==1 && (chr.haveItem(EGG)||chr.haveItem(GOLD));}
    public static String status(Character chr) {
        var s=chr.getContentState();
        return "Raise an egg with hunting EXP (3,000 total). A deposit costs 1,000 mesos; a grown chicken returns 10,000 mesos. One chicken may be returned per UTC day. Feed Growth Powder on five different days for a golden chicken and a Golden Rooster Comb or Golden Chicken Effect. No weekday deadline or expiry.\r\nGrowth: "+s.get("egg.growth")+" / 3,000. Care days: "+s.get("egg.feeds")+" / 5.\r\n#L0#Take an egg / recover my egg#l\r\n#L1#Collect today's Growth Powder#l\r\n#L2#Feed Growth Powder#l\r\n#L3#Return my grown chicken#l";
    }
    public static String take(Character chr) {
        synchronized(chr) {
          try (var locks = InventoryLocks.acquire(chr, InventoryType.EQUIPPED, InventoryType.EQUIP, InventoryType.ETC)) {
            var s=chr.getContentState();
            if(owns(chr))return "Your egg is already in your Etc inventory. Double-click it to view its growth.";
            if(chr.haveItem(EGG)||chr.haveItem(GOLD))return "You already have an egg. Finish caring for it first.";
            boolean recover=s.get("egg.active")==1;
            if(!recover && (chr.getLevel()<10 || chr.getMeso()<1000 || s.get("egg.claimed")==day()))return "You need level 10, 1,000 mesos, and must wait until the next UTC day after returning a chicken.";
            if(!InventoryManipulator.addById(chr.getClient(),EGG,(short)1))return "Make room in Etc for your egg.";
            if(!recover){chr.gainMeso(-1000,false);s.set("egg.growth",0);s.set("egg.feeds",0);s.set("egg.fed",0);}
            s.set("egg.active",1);Quest.getInstance(INFO).forceStart(chr,9209007);sync(chr);
            return recover?"Here is your replacement egg. Its progress is preserved.":"Care for your egg while hunting. Return when it is fully grown, or feed it on five days for the golden reward.";
          }
        }
    }
    public static String powder(Character chr) {
        synchronized(chr) {
          try (var locks = InventoryLocks.acquire(chr, InventoryType.EQUIPPED, InventoryType.EQUIP, InventoryType.ETC)) {
            var s=chr.getContentState();
            if(!owns(chr) || s.get("egg.powderday")==day())return "I supply one Growth Powder per UTC day while you are raising an egg.";
            if(!InventoryManipulator.addById(chr.getClient(),POWDER,(short)1))return "Make room in Etc first.";
            s.set("egg.powderday",day());return "Here is today's Growth Powder. You may feed once per UTC day.";
          }
        }
    }
    public static String feed(Character chr) {
        synchronized(chr) {
          try (var locks = InventoryLocks.acquire(chr, InventoryType.EQUIPPED, InventoryType.EQUIP, InventoryType.ETC)) {
            var s=chr.getContentState();
            if(!owns(chr) || !chr.haveItem(POWDER) || s.get("egg.fed")==day() || s.get("egg.feeds")>=5)return "You need your egg and Growth Powder. Feeding counts once per UTC day, up to five care days.";
            InventoryManipulator.removeById(chr.getClient(),InventoryType.ETC,POWDER,1,true,false);
            s.set("egg.fed",day());s.add("egg.feeds",1);s.set("egg.growth",Math.min(3000,s.get("egg.growth")+600));sync(chr);
            return "Your egg enjoyed its Growth Powder. Care days: "+s.get("egg.feeds")+" / 5.";
          }
        }
    }
    public static void huntingExp(Character chr,long exp) {
        synchronized(chr) {
            if(exp<=0 || !owns(chr))return;
            var s=chr.getContentState();
            long old=s.get("egg.growth");long next=Math.min(3000,old+(long)exp);
            if(next!=old){s.set("egg.growth",next);sync(chr);}
        }
    }
    public static void sync(Character chr) {chr.setQuestProgress(INFO,0,Long.toString(chr.getContentState().get("egg.growth")));}
    public static String claim(Character chr) {
        synchronized(chr) {
          try (var locks = InventoryLocks.acquire(chr, InventoryType.EQUIPPED, InventoryType.EQUIP, InventoryType.ETC)) {
            var s=chr.getContentState();
            if(!owns(chr) || s.get("egg.growth")<3000 || s.get("egg.claimed")==day())return "Raise your egg to 3,000 growth first. Returns are limited to one per UTC day.";
            if((long)chr.getMeso()+10000>Integer.MAX_VALUE)return "Store some mesos first.";
            int reward=s.get("egg.feeds")>=5 ? (chr.haveItem(HAT)?EFFECT:HAT) : 0;
            if(reward>0 && !InventoryManipulator.checkSpace(chr.getClient(),reward,1,""))return "Make room for your golden chicken's reward first.";
            if(reward>0 && !InventoryManipulator.addById(chr.getClient(),reward,(short)1))return "The reward could not be placed in your inventory. Your egg is safe.";
            int egg=chr.haveItem(EGG)?EGG:GOLD;
            InventoryManipulator.removeById(chr.getClient(),InventoryType.ETC,egg,1,true,false);
            s.set("egg.active",0);s.set("egg.claimed",day());chr.gainMeso(10000,false);
            Quest.getInstance(INFO).reset(chr);
            return "Thank you! Here are 10,000 mesos"+(reward>0?" and #t"+reward+"#.":". Five care days also earn a golden reward next time.");
          }
        }
    }
}
