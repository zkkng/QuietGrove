package server.partyquest;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import org.slf4j.LoggerFactory;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import scripting.event.EventInstanceManager;
import server.ItemInformationProvider;
import server.TimerManager;
import server.content.SurvivalState;
import server.life.LifeFactory;
import server.life.Monster;
import server.maps.MapleMap;
import server.quest.Quest;
import tools.PacketCreator;
import java.awt.Point;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/** Private v83 timed survival instances shared by Nett's Pyramid and Dusty Platform. */
public final class Pyramid extends PartyQuest {
    public enum PyramidMode {EASY,NORMAL,HARD,HELL; public int getMode(){return ordinal();}}
    private final boolean subway,bonus;
    private final int difficulty,base;
    private final EventInstanceManager instance;
    private final Map<Integer,SurvivalState> scores=new ConcurrentHashMap<>();
    private final AtomicBoolean closed=new AtomicBoolean();
    private volatile MapleMap current;
    private volatile boolean transitioning;
    private ScheduledFuture<?> timer;
    private int stage,seconds,gauge=100,gaugeCounter,decrease=1,hitAdd=1,coolAdd=5,missSub=4;
    private volatile boolean cleared;

    private Pyramid(List<Character> players,int mode,boolean subway,boolean bonus,EventInstanceManager instance){
        super(players);this.difficulty=mode;this.subway=subway;this.bonus=bonus;this.instance=instance;
        this.base=subway?(players.size()>1?910330000:910320000):(players.size()>1?926020000:926010000)+mode*1000;
        for(Character chr:players)scores.put(chr.getId(),new SurvivalState());
    }
    public static String enter(Character leader,int mode,boolean subway,boolean withParty,boolean bonus){
        if(mode<0 || mode>3)return "Choose a valid difficulty.";
        int lobby=subway?910320000:926010000;
        if(leader.getMapId()!=lobby)return "Enter from the waiting area first.";
        List<Character> players=new ArrayList<>();
        if(withParty && !bonus){
            if(leader.getParty()==null || leader.getParty().getLeaderId()!=leader.getId())return "The party leader must start the challenge.";
            for(var member:leader.getParty().getMembers()){
                Character chr=leader.getMap().getCharacterById(member.getId());
                if(chr==null)return "Gather every party member in this waiting area on the same channel.";
                players.add(chr);
            }
        }else players.add(leader);
        if(players.size()>4)return "At most four players may enter.";
        int minimum=subway?25:new int[]{40,46,51,61}[mode];
        for(Character chr:players)if(chr.getLevel()<minimum || !chr.isAlive() || chr.getEventInstance()!=null || chr.getPartyQuest()!=null)return "Everyone must be alive, level "+minimum+" or higher, and outside another event.";
        int ticket=subway?4001321:4001322+mode;
        if(bonus && !leader.haveItem(ticket))return "You need #t"+ticket+"# from a successful challenge.";
        var manager=leader.getClient().getEventManager("V83Survival");
        if(manager==null || !manager.getName().equals("V83Survival"))return "The survival event is not loaded. Please restart the server after installing its scripts.";
        EventInstanceManager eim=null;Pyramid pq=null;boolean ticketSpent=false;
        try{
            eim=manager.newInstance("survival-"+leader.getId()+"-"+UUID.randomUUID());
            pq=new Pyramid(players,mode,subway,bonus,eim);eim.setObjectProperty("survival",pq);
            // Allocate and validate the first map before spending the ticket.
            pq.prepareMap(bonus?(subway?910320010:(mode==3?926010070:926010010)):pq.base+100);
            for(Character chr:players){chr.setPartyQuest(pq);eim.registerPlayer(chr,false);}
            eim.startEvent();
            if(bonus){
                synchronized(leader){
                  try (var locks = server.content.InventoryLocks.acquire(leader, InventoryType.ETC)) {
                    if(!leader.haveItem(ticket))throw new IllegalStateException("The bonus ticket is no longer in your inventory.");
                    InventoryManipulator.removeById(leader.getClient(),InventoryType.ETC,ticket,1,true,false);
                    ticketSpent=true;
                  }
                }
            }
            pq.start();return "";
        }catch(Exception ex){
            LoggerFactory.getLogger(Pyramid.class).error("Could not start survival instance",ex);
            if(ticketSpent) leader.getContentState().add("survival.ticket."+ticket,1);
            if(pq!=null)pq.finish(false);else if(eim!=null)eim.dispose();
            return "The challenge could not start. Please try again.";
        }
    }
    private void prepareMap(int id){
        current=instance.getInstanceMap(id);
        if(current==null)throw new IllegalStateException("Missing survival map "+id);
        current.setOnUserEnter("");current.setOnFirstUserEnter("");
        current.setMobCapacity(70);current.setMobInterval((short)3000);
        if(!bonus){
            var data=DataProviderFactory.getDataProvider(WZFiles.MAP).getData("Map/Map9/"+id+".img").getChildByPath("mobMassacre/gauge");
            if(data==null)throw new IllegalStateException("Missing survival gauge "+id);
            decrease=DataTool.getInt("decrease",data,1);hitAdd=DataTool.getInt("hitAdd",data,1);coolAdd=DataTool.getInt("coolAdd",data,5);missSub=DataTool.getInt("missSub",data,4);
        }
    }
    private void start(){
        stage=1;seconds=bonus?60:120;warpStage();
        if(bonus){
            int mob=subway?9700020:(difficulty==3?9700029:9700019);
            for(int i=0;i<(subway?30:50);i++)current.spawnMonsterOnGroundBelow(LifeFactory.getMonster(mob),new Point(-220+i*440/(subway?30:50),-200));
        }
        timer=TimerManager.getInstance().register(()->{
            try{tick();}catch(Exception ex){LoggerFactory.getLogger(Pyramid.class).error("Survival timer failed",ex);finish(false);}
        },1000);
    }
    private void warpStage(){
        transitioning=true;
        try{
            for(Character chr:List.copyOf(participants)){
                if(chr.getPartyQuest()!=this || !chr.isLoggedinWorld()){leave(chr,false);continue;}
                chr.changeMap(current,current.getPortal(0));
                chr.sendPacket(PacketCreator.getClock(seconds));
                chr.sendPacket(PacketCreator.getEnergy("massacre_party",participants.size()>1?1:0));
                chr.sendPacket(PacketCreator.getEnergy("massacre_laststage",!bonus && stage==(subway?3:5)?1:0));
                chr.sendPacket(PacketCreator.mapEffect(bonus?"killing/bonus/bonus":"killing/first/stage"));
                if(!bonus)chr.sendPacket(PacketCreator.mapEffect("killing/first/number/"+stage));
                sendStats(chr);
            }
            current.respawn();
        }finally{transitioning=false;}
    }
    private void tick(){
        if(closed.get())return;
        for(Character chr:List.copyOf(participants))if(!chr.isLoggedinWorld() || !chr.isAlive() || chr.getMap()!=current)leave(chr,chr.isLoggedinWorld());
        if(participants.isEmpty()){finish(false);return;}
        boolean failed;
        synchronized(this){if(!bonus)gauge=Math.max(0,gauge-decrease);failed=!bonus && gauge==0;seconds--;}
        if(failed){finish(false);return;}
        if(seconds<=0){
            if(bonus){finish(true);return;}
            if(stage==(subway?3:5)){finish(true);return;}
            stage++;seconds=subway?120:180;gauge=100;gaugeCounter=0;
            prepareMap(base+stage*100);warpStage();
        }else if(!bonus){
            if(seconds%3==0)current.respawn();
            if(!subway && stage>=2 && seconds==90){
                int count=stage==5?2:1;
                for(int i=0;i<count;i++)current.spawnMonsterOnGroundBelow(LifeFactory.getMonster(9700038),new Point(i*100,-200));
            }
        }
    }
    public boolean handles(Character chr,Monster mob){return !closed.get() && !transitioning && chr.getPartyQuest()==this && participants.contains(chr) && chr.getMap()==current && mob.getMap()==current;}
    public boolean blockAttack(Character chr,Monster mob,int damage){
        if(!handles(chr,mob) || bonus)return false;
        if(damage<=0 || mob.getId()==9700021 || mob.getId()==9700022 || mob.getId()==9700023 || mob.getId()==9700038){
            scores.get(chr.getId()).miss();synchronized(this){gauge=Math.max(0,gauge-missSub);gaugeCounter-=missSub;}sendStats(chr);return true;
        }
        return false;
    }
    public void killed(Character chr,Monster mob,int damage){
        if(!handles(chr,mob))return;
        if(bonus){
            int reward=subway?2022615:(difficulty==3?2022618:2022613);
            current.spawnItemDrop(mob,chr,new Item(reward,(short)0,(short)1),mob.getPosition(),true,false);return;
        }
        var score=scores.get(chr.getId());var info=mob.getStats().getCool();
        boolean cool=info!=null && damage>=info.getLeft() && ThreadLocalRandom.current().nextInt(100)<info.getRight();
        score.hit(cool);
        synchronized(this){int gain=Math.min(100-gauge,hitAdd+(cool?coolAdd:0));gauge+=gain;gaugeCounter+=gain;}
        int quest=subway?29931:29932,infoQuest=subway?7662:7760;
        long total=chr.getContentState().add(subway?"survival.subway.kills":"survival.pyramid.kills",1);
        Quest q=Quest.getInstance(quest);
        if(chr.getQuest(q).getStatus()==client.QuestStatus.Status.NOT_STARTED)q.start(chr,9000066);
        chr.setQuestProgress(quest,infoQuest,Long.toString(total));
        int buff=score.nextBuff(subway);
        if(buff>0)ItemInformationProvider.getInstance().getItemEffect(buff).applyTo(chr);
        sendStats(chr);
    }
    private void sendStats(Character chr){
        var s=scores.get(chr.getId());if(s==null)return;
        chr.sendPacket(PacketCreator.getEnergy("massacre_hit",s.kills()));chr.sendPacket(PacketCreator.getEnergy("massacre_cool",s.cools()));chr.sendPacket(PacketCreator.getEnergy("massacre_miss",s.misses()));
        chr.sendPacket(PacketCreator.getEnergy("massacre_skill",subway?0:s.skillUses()));
        for(Character participant:participants)participant.sendPacket(PacketCreator.pyramidGauge(gaugeCounter));
    }
    public boolean useSkill(Character chr){
        if(subway || bonus || closed.get() || transitioning || !chr.isAlive() || chr.getMap()!=current || chr.getPartyQuest()!=this)return false;
        var score=scores.get(chr.getId());if(score==null || !score.useSkill())return false;
        for(Monster mob:current.getAllMonsters()){
            if(!mob.isAlive())continue;
            boolean died=mob.damage(chr,mob.getHp(),false);
            if(died){killed(chr,mob,Integer.MAX_VALUE);current.killMonster(mob,chr,false,(short)0);}
        }
        sendStats(chr);return true;
    }
    public void sendScore(Character chr){
        var s=scores.get(chr.getId());if(s==null || !closed.get() || bonus)return;
        int exp=s.experience(cleared,subway,difficulty);
        chr.sendPacket(PacketCreator.pyramidScore(s.rank(cleared,subway),exp));
        if(s.claim()){
            chr.gainExp(exp,true,true);
            if(cleared)chr.getContentState().add("survival.ticket."+(subway?4001321:4001322+difficulty),1);
        }
    }
    public void leave(Character chr,boolean warp){
        if(!participants.remove(chr))return;
        chr.setPartyQuest(null);if(chr.getEventInstance()==instance)instance.unregisterPlayer(chr);
        cancelBuffs(chr);
        if(warp){if(!chr.isAlive())chr.respawn(subway?910320000:926010000);else chr.changeMap(subway?910320000:926010000,0);}
        if(participants.isEmpty())finish(false);
    }
    private void cancelBuffs(Character chr){
        for(int id:new int[]{2022585,2022586,2022587,2022588,2022616,2022617}){
            var effect=ItemInformationProvider.getInstance().getItemEffect(id);if(effect!=null)chr.cancelEffect(effect,false,-1);
        }
    }
    public void finish(boolean success){
        if(!closed.compareAndSet(false,true))return;cleared=success;
        if(timer!=null)timer.cancel(false);
        for(Character chr:List.copyOf(participants)){
            try{
                chr.setPartyQuest(null);if(chr.getEventInstance()==instance)instance.unregisterPlayer(chr);cancelBuffs(chr);
                if(chr.isLoggedinWorld()){
                    int target=bonus?(subway?910320000:926010000):(subway?(success?910330001:910320001):(success?926020001:926010001));
                    if(!chr.isAlive())chr.respawn(subway?910320000:926010000);else chr.changeMap(target,0);
                    sendScore(chr);
                }
            }catch(Exception ex){LoggerFactory.getLogger(Pyramid.class).error("Could not exit survival player {}",chr.getId(),ex);}
        }
        participants.clear();instance.dispose();
    }
    /** Called by external event disposal; do not recursively dispose the event. */
    public void disposed() {
        if (!closed.compareAndSet(false, true)) return;
        if (timer != null) timer.cancel(false);
        for (Character chr : List.copyOf(participants)) {
            if (chr.getPartyQuest() == this) chr.setPartyQuest(null);
            if (chr.getEventInstance() == instance) chr.setEventInstance(null);
            cancelBuffs(chr);
            if (chr.isLoggedinWorld()) {
                if (!chr.isAlive()) chr.respawn(subway ? 910320000 : 926010000);
                else chr.changeMap(subway ? 910320000 : 926010000, 0);
            }
        }
        participants.clear();
    }
    public void changedMap(Character chr){if(!transitioning && chr.getMap()!=current)leave(chr,false);}
    public static String collectTickets(Character chr,boolean subway){
        synchronized(chr){
            int first=subway?4001321:4001322,last=subway?4001321:4001325,total=0;
            for(int id=first;id<=last;id++){
                String key="survival.ticket."+id;long pending=chr.getContentState().get(key);
                short count=(short)Math.min(100,pending);if(count<=0)continue;
                if(!InventoryManipulator.checkSpace(chr.getClient(),id,count,"") || !InventoryManipulator.addById(chr.getClient(),id,count))return "Make room in Etc. Uncollected passes remain saved with your character.";
                chr.getContentState().add(key,-count);total+=count;
            }
            return total>0?"Collected "+total+" bonus passes.":"No uncollected passes. Finish all survival stages to earn one.";
        }
    }
}
