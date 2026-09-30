package server.content;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import server.ItemInformationProvider;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Always-open market, using v83 consumables and bounded hourly trading. */
public final class SevenDayMarket {
    private SevenDayMarket() {}
    public static final int[][] STOCK={{2001000,2001001,2001002},{2020012,2020013},{2020014,2020015},{2022000,2022001},{2022020,2022011,2022015}};
    public static long hour() { return System.currentTimeMillis()/3_600_000; }
    public static boolean inside(Character chr) {return chr.getMapId()>=680100000 && chr.getMapId()<=680100003;}
    public static int quote(int base,int item,long hour,boolean buy) {
        int change=Math.floorMod(Long.hashCode(hour*6364136223846793005L+item*1442695040888963407L),21)-10;
        return Math.max(1,(int)((long)base*(100+change+(buy?5:-5))/100));
    }
    public static int price(int item,long hour,boolean buy) {
        int base=Math.max(100,(int)Math.ceil(ItemInformationProvider.getInstance().getPrice(item,1)*2));
        return quote(base,item,hour,buy);
    }
    public static boolean stocked(int npc,int item) {
        int vendor=npc-9209002;
        if(vendor<0 || vendor>=STOCK.length)return false;
        for(int id:STOCK[vendor])if(id==item)return true;
        return false;
    }
    public static String menu(Character chr,int npc,long hour) {
        if(npc<9209002 || npc>9209006)return "This merchant has no market stock.";
        StringBuilder out=new StringBuilder("Today's regional supplies. Quotes change hourly. Choose an item to buy or sell market purchases. Daily buying limit: 500,000 mesos. Goods never expire.\r\n");
        for(int item:STOCK[npc-9209002])out.append("#L").append(item).append("##i").append(item).append("# #t").append(item).append("# - buy ").append(price(item,hour,true)).append(" / sell ").append(price(item,hour,false)).append(" mesos. Resell allowance: ").append(chr.getContentState().get("market.stock."+item)).append("#l\r\n");
        return out.toString();
    }
    public static String trade(Character chr,int npc,int item,int quantity,boolean buy,long quotedHour) {
        synchronized(chr) {
            if(!inside(chr) || !stocked(npc,item) || quantity<1 || quantity>1000)return "That market order is invalid.";
            if(quotedHour!=hour())return "The hourly quote changed. Reopen the shop to see the new price.";
            try (var locks = InventoryLocks.acquire(chr, InventoryType.USE)) {
            int total=Math.multiplyExact(price(item,quotedHour,buy),quantity);var s=chr.getContentState();String key="market.stock."+item;
            long day=LocalDate.now(ZoneOffset.UTC).toEpochDay();
            if(s.get("market.day")!=day){s.set("market.day",day);s.set("market.spent",0);}
            if(buy) {
                if(total>chr.getMeso() || s.get("market.spent")+total>500_000)return "You need enough mesos and room in today's 500,000-meso buying allowance.";
                if(!InventoryManipulator.checkSpace(chr.getClient(),item,quantity,"") || !InventoryManipulator.addById(chr.getClient(),item,(short)quantity))return "Make room in your Use inventory first.";
                chr.gainMeso(-total,false);s.add("market.spent",total);s.add(key,quantity);
            } else {
                if(s.get(key)<quantity || chr.getInventory(InventoryType.USE).countById(item)<quantity)return "You can sell only the number purchased through this market, and must still have the items.";
                if((long)chr.getMeso()+total>Integer.MAX_VALUE)return "Store some mesos before selling.";
                InventoryManipulator.removeById(chr.getClient(),InventoryType.USE,item,quantity,true,false);
                s.add(key,-quantity);chr.gainMeso(total,false);
            }
            return (buy?"Purchased ":"Sold ")+quantity+" #t"+item+"# for "+total+" mesos.";
            }
        }
    }
}
