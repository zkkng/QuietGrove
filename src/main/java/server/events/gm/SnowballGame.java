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
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/** Both teams share one lifecycle, deadline, cadence monitor and result commitment. */
public final class SnowballGame {
    public record Hit(int target,int damage,int winner,boolean moved,boolean stopped) {}
    private final MapleMap map;
    private final BooleanSupplier valid;
    private final IntPredicate participant;
    private final IntConsumer finish;
    private final Snowball[] teams;
    private final int[] laneY;
    private final int snowmanX,ballX,dx,goal,maxHp;
    private final Map<Integer,Long> readyAt=new HashMap<>();
    private final Map<Integer,Long> resetAt=new HashMap<>();
    private final Map<Integer,Point> observedAt=new HashMap<>();
    private final SnowballGeometry geometry=new SnowballGeometry();
    private final Random random;
    private boolean running;
    private boolean resultPending;
    private ScheduledFuture<?> deadline,recovery,resultDelay;

    public SnowballGame(MapleMap map,BooleanSupplier valid,IntPredicate participant,IntConsumer finish,long seed) {
        this.map=map; this.valid=valid; this.participant=participant; this.finish=finish; random=new Random(seed);
        teams=new Snowball[]{new Snowball(0,map),new Snowball(1,map)};
        Data data=DataProviderFactory.getDataProvider(WZFiles.MAP).getData("Map/Map1/109060000.img");
        laneY=new int[]{DataTool.getInt("snowBall/0/y",data),DataTool.getInt("snowBall/1/y",data)};
        snowmanX=DataTool.getInt("snowBall/x",data); ballX=DataTool.getInt("snowBall/x0",data);
        dx=DataTool.getInt("snowBall/dx",data); goal=DataTool.getInt("snowBall/xMax",data);
        maxHp=DataTool.getInt("snowBall/snowManHP",data);
        for(Snowball team:teams) team.snowmanHp(maxHp);
        if(dx<1 || goal<1 || maxHp<1) throw new IllegalArgumentException("Snowball WZ configuration");
    }
    public void start() {
        if(!valid.getAsBoolean()) return;
        synchronized(this) {
            if(running) return;
            running=true; for(Snowball ball:teams) ball.hittable(true);
        }
        broadcastState(1); map.broadcastMessage(PacketCreator.getClock(600));
        deadline=TimerManager.getInstance().schedule(this::finishNow,600_000);
        recovery=TimerManager.getInstance().register(this::recover,250,250);
        synchronized(this) { if(!running) cancelTimers(); }
    }
    public boolean hit(Character actor,int target,long now) {
        if(actor==null || actor.getMap()!=map || !actor.isAlive() || actor.isGM() || actor.isChangingMaps()
                || !valid.getAsBoolean() || !participant.test(actor.getId()) || !EventMelee.legal(actor)) return false;
        Hit result=apply(actor.getId(),actor.getTeam(),actor.getPosition(),target,now);
        if(result==null || !valid.getAsBoolean()) return false;
        map.broadcastMessage(PacketCreator.hitSnowBall(target,result.damage()));
        if(result.moved()) broadcastState(0);
        if(result.moved() || result.stopped()) broadcastState(1);
        if(result.stopped()) message(1-actor.getTeam(),4);
        if(result.moved()) {
            int position=teams[actor.getTeam()].getPosition();
            int section=position==45?1:position==290?2:position==560?3:0;
            if(section>0) message(1-actor.getTeam(),section);
        }
        if(result.winner()>=0) presentResult(result.winner());
        return true;
    }
    synchronized Hit apply(int actorId,int team,Point position,int target,long now) {
        if(!running || team<0 || team>1 || target<0 || target>3 || target%2!=team
                || now<readyAt.getOrDefault(actorId,0L) || Math.abs(position.y-laneY[team])>110) return null;
        Snowball ball=teams[team],other=teams[1-team];
        int x=target<2?ballX+dx*ball.getPosition():snowmanX;
        if(Math.abs(position.x-x)>200) return null;
        if(target<2 && other.getSnowmanHP()==0 || target>=2 && ball.getSnowmanHP()==0) return null;
        readyAt.put(actorId,now+500);
        if(target<2) {
            boolean moved=--ball.hits==0;
            if(moved) { ball.position(Math.min(goal,ball.getPosition()+1)); ball.hits=3; }
            int winner=ball.getPosition()>=goal?team:-1;
            if(winner>=0) {running=false; for(Snowball b:teams) b.hittable(false);}
            return new Hit(target,10,winner,moved,false);
        }
        int damage=random.nextDouble()<.03?45:15;
        ball.snowmanHp(Math.max(0,ball.getSnowmanHP()-damage));
        boolean stopped=ball.getSnowmanHP()==0;
        if(stopped) ball.recoveryAt=now+10_000;
        return new Hit(target,damage,-1,false,stopped);
    }
    private void recover() {
        if(!valid.getAsBoolean()) return;
        for(int team=0;team<2;team++) {
            boolean recovered;
            synchronized(this) { recovered=recoverTeam(team,System.currentTimeMillis()); }
            if(recovered) {broadcastState(1); message(1-team,5);}
        }
        // Native clients normally report contact themselves; this also handles a ball
        // rolling into a stationary entrant. Bot collision runs on the physical driver.
        Set<Integer> present=new java.util.HashSet<>();
        for(Character actor:map.getCharacters()) if(!soloMapling.ArtificialPlayer.BotHelpers.isBot(actor)) {
            present.add(actor.getId());
            Point current=actor.getPosition();
            Point previous;
            synchronized(this) { previous=observedAt.put(actor.getId(),new Point(current)); }
            collide(actor,previous,System.currentTimeMillis());
        }
        synchronized(this) {observedAt.keySet().retainAll(present);}
    }
    synchronized boolean recoverTeam(int team,long now) {
        Snowball ball=teams[team];
        if(!running || ball.getSnowmanHP()!=0 || now<ball.recoveryAt) return false;
        ball.snowmanHp(maxHp); ball.recoveryAt=0; return true;
    }
    public void finishNow() {
        if(!valid.getAsBoolean()) return;
        int winner;
        synchronized(this) {
            if(!running) return;
            winner=winner(); running=false; for(Snowball ball:teams) ball.hittable(false);
        }
        presentResult(winner);
    }
    public synchronized int winner() {
        return teams[0].getPosition()>teams[1].getPosition()?0:teams[0].getPosition()<teams[1].getPosition()?1:-1;
    }
    public Snowball team(int team) { return teams[team]; }
    public synchronized Point target(int team,int target) {
        if(team<0 || team>1 || target<0 || target>3 || target%2!=team || !running) return null;
        return new Point(target<2?ballX+dx*teams[team].getPosition()-geometry.approachOffset():snowmanX,laneY[team]);
    }
    synchronized boolean touches(int team,Point previous,Point current) {
        if(!running || team<0 || team>1) return false;
        return geometry.touches(new Point(ballX+dx*teams[team].getPosition(),laneY[team]),previous,current);
    }
    /** An actual touch resets position only; no score, HP, inventory or entrant ownership changes. */
    public boolean collide(Character actor,Point previous,long now) {
        if(actor==null) return false;
        synchronized(actor) {
            if(actor.getMap()!=map || !actor.isAlive() || actor.isGM() || actor.isChangingMaps()
                    || !valid.getAsBoolean() || !participant.test(actor.getId()) || actor.getTeam()<0 || actor.getTeam()>1) return false;
            var portal=map.getPortal("st0"+actor.getTeam());
            if(portal==null) return false;
            synchronized(this) {
                if(!running || now<resetAt.getOrDefault(actor.getId(),0L)
                        || !(touches(0,previous,actor.getPosition()) || touches(1,previous,actor.getPosition()))) return false;
                resetAt.put(actor.getId(),now+1000);readyAt.put(actor.getId(),now+500);
            }
            if(!valid.getAsBoolean()) return false;
            if(soloMapling.ArtificialPlayer.BotHelpers.isBot(actor))
                soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.disable(actor);
            actor.changeMap(map,portal);
            synchronized(this) {observedAt.put(actor.getId(),new Point(portal.getPosition()));}
            return actor.getMap()==map;
        }
    }
    public void broadcastState(int state) { map.broadcastMessage(PacketCreator.rollSnowBall(false,state,teams[0],teams[1])); }
    private void broadcastResult(int winner) {
        for(Character actor:map.getCharacters()) if(participant.test(actor.getId()))
            actor.sendPacket(PacketCreator.rollSnowBall(false,winner<0?2:actor.getTeam()==winner?3:4,teams[0],teams[1]));
    }
    private void presentResult(int winner) {
        synchronized(this) {
            if(!valid.getAsBoolean()) return;
            resultPending=true;
        }
        broadcastResult(winner);
        // The legacy result animation needs time on the arena before the winner/exit warp.
        resultDelay=TimerManager.getInstance().schedule(() -> {
            synchronized(this) {
                if(!resultPending || !valid.getAsBoolean()) return;
                resultPending=false;
            }
            finish.accept(winner);
        },10_000);
    }
    private void message(int team,int message) {
        for(Character actor:map.getCharacters()) if(actor.getTeam()==team && participant.test(actor.getId()))
            actor.sendPacket(PacketCreator.snowballMessage(team,message));
    }
    public synchronized void cancel() {running=false;resultPending=false;for(Snowball ball:teams) ball.hittable(false); cancelTimers(); readyAt.clear();resetAt.clear();observedAt.clear();}
    private void cancelTimers() {if(deadline!=null) deadline.cancel(false); if(recovery!=null) recovery.cancel(false); if(resultDelay!=null) resultDelay.cancel(false);}
}
