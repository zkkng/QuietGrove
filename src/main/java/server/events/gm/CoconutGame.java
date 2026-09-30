package server.events.gm;

import client.Character;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.TimerManager;
import server.maps.MapleMap;
import tools.PacketCreator;
import java.awt.Point;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/** One monitor commits hits and last-hit scoring; network I/O occurs afterwards. */
public final class CoconutGame {
    public record Hit(int id,int animation,int mapleScore,int storyScore,boolean scored) {}
    private static final class Nut {
        final Point position;
        int hits;
        long readyAt;
        boolean hittable=true;
        Nut(Point position) { this.position=new Point(position); }
    }
    private final MapleMap map;
    private final BooleanSupplier valid;
    private final IntPredicate participant;
    private final IntConsumer finish;
    private final Runnable extendClock;
    private final Map<Integer,Nut> nuts=new HashMap<>();
    private final Map<Integer,Long> actorReady=new HashMap<>();
    private final Random random;
    private int mapleScore,storyScore,falling,bombing,stopped,countHit;
    private volatile boolean running;
    private boolean overtime;
    private long timerGeneration;
    private ScheduledFuture<?> timer;
    private final Object outbound=new Object();

    public CoconutGame(MapleMap map,BooleanSupplier valid,IntPredicate participant,IntConsumer finish,long seed) {
        this(map,valid,participant,finish,seed,()->{});
    }
    public CoconutGame(MapleMap map,BooleanSupplier valid,IntPredicate participant,IntConsumer finish,long seed,Runnable extendClock) {
        this.map=map; this.valid=valid; this.participant=participant; this.finish=finish;
        this.extendClock=extendClock;
        random=new Random(seed);
        Data data=DataProviderFactory.getDataProvider(WZFiles.MAP).getData("Map/Map1/109080000.img");
        falling=DataTool.getInt("coconut/countFalling",data,401);
        bombing=DataTool.getInt("coconut/countBombing",data,80);
        stopped=DataTool.getInt("coconut/countStopped",data,20);
        countHit=DataTool.getInt("coconut/countHit",data,5);
        for(Data layer:data.getChildren()) {
            Data objects=layer.getChildByPath("obj"); if(objects==null) continue;
            for(Data object:objects.getChildren()) {
                if(!"coconut".equals(DataTool.getString("l1",object,""))) continue;
                int id=Integer.parseInt(DataTool.getString("name",object));
                Point position=new Point(DataTool.getInt("x",object),DataTool.getInt("y",object));
                if(nuts.put(id,new Nut(position))!=null) throw new IllegalArgumentException("Duplicate coconut ID "+id);
            }
        }
        if(nuts.isEmpty() || countHit<1 || falling+bombing+stopped>nuts.size())
            throw new IllegalArgumentException("Coconut map/configuration is incomplete");
    }
    public void start() {
        if(!valid.getAsBoolean()) return;
        synchronized(this) { if(running) return; running=true; }
        map.broadcastMessage(PacketCreator.hitCoconut(true,0,0));
        map.broadcastMessage(PacketCreator.coconutScore(0,0));
        map.broadcastMessage(PacketCreator.getClock(300));
        scheduleDeadline(300_000);
    }
    public boolean hit(Character actor,int id,long now) {
        if(actor==null || actor.getMap()!=map || !actor.isAlive() || actor.isGM() || actor.isChangingMaps()
                || !valid.getAsBoolean() || !participant.test(actor.getId()) || !EventMelee.legal(actor)) return false;
        Hit result=apply(actor.getId(),actor.getTeam(),actor.getPosition(),id,now);
        if(result==null || !valid.getAsBoolean()) return false;
        synchronized(outbound) {
            if(!valid.getAsBoolean()) return false;
            map.broadcastMessage(PacketCreator.hitCoconut(false,result.id(),result.animation()));
            if(result.scored()) map.broadcastMessage(PacketCreator.coconutScore(getMapleScore(),getStoryScore()));
        }
        return true;
    }
    synchronized Hit apply(int actorId,int team,Point position,int id,long now) {
        Nut nut=nuts.get(id);
        if(!running || (team!=0 && team!=1) || nut==null || !nut.hittable || now<nut.readyAt
                || now<actorReady.getOrDefault(actorId,0L)
                || Math.abs(position.x-nut.position.x)>180 || Math.abs(position.y-nut.position.y)>160) return null;
        actorReady.put(actorId,now+750); nut.readyAt=now+750;
        if(++nut.hits<countHit) return new Hit(id,1,mapleScore,storyScore,false);
        nut.hittable=false;
        int remaining=falling+bombing+stopped;
        if(remaining<=0) return new Hit(id,1,mapleScore,storyScore,false);
        int outcome=random.nextInt(remaining);
        if(outcome<stopped) {stopped--; return new Hit(id,1,mapleScore,storyScore,false);}
        if(outcome<stopped+bombing) {bombing--; return new Hit(id,2,mapleScore,storyScore,false);}
        falling--; if(team==0) mapleScore++; else storyScore++;
        return new Hit(id,3,mapleScore,storyScore,true);
    }
    private void scheduleDeadline(long delay) {
        long generation;
        synchronized(this) {if(!running) return; generation=++timerGeneration;}
        ScheduledFuture<?> next=TimerManager.getInstance().schedule(()->timeUp(generation),delay);
        synchronized(this) {
            if(!running || timerGeneration!=generation) next.cancel(false);
            else timer=next;
        }
    }
    private void timeUp(long generation) {
        if(!valid.getAsBoolean()) return;
        boolean extend;
        int winner;
        synchronized(this) {
            if(!running || timerGeneration!=generation) return;
            timerGeneration++;
            extend=mapleScore==storyScore && !overtime;
            if(extend) overtime=true;
            winner=winner();
            if(!extend) running=false;
        }
        if(extend) {
            extendClock.run();
            if(!valid.getAsBoolean()) return;
            map.broadcastMessage(PacketCreator.getClock(120));
            scheduleDeadline(120_000);
        } else finish.accept(winner);
    }
    public void finishNow() {
        int winner;
        synchronized(this) { if(!running) return; winner=winner(); running=false;timerGeneration++;if(timer!=null) timer.cancel(false); }
        finish.accept(winner);
    }
    public synchronized int winner() { return mapleScore>storyScore?0:mapleScore<storyScore?1:-1; }
    public synchronized int getMapleScore() { return mapleScore; }
    public synchronized int getStoryScore() { return storyScore; }
    public synchronized int coconutCount() { return nuts.size(); }
    public synchronized Point position(int id) { Nut nut=nuts.get(id); return nut==null?null:new Point(nut.position); }
    public synchronized GmEventService.BotTarget target(Point from) {
        return nuts.entrySet().stream().filter(e->e.getValue().hittable)
                .filter(e->Math.abs(e.getValue().position.y-from.y)<250)
                .min(java.util.Comparator.comparingDouble(e->e.getValue().position.distanceSq(from)))
                .map(e->new GmEventService.BotTarget(e.getKey(),new Point(e.getValue().position))).orElse(null);
    }
    public synchronized void cancel() { running=false;timerGeneration++; if(timer!=null) timer.cancel(false); actorReady.clear(); }
}
