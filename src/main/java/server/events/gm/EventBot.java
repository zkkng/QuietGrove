package server.events.gm;

import client.Character;
import server.maps.*;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionTaskService;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.server.BotTickService;
import java.awt.Point;
import java.util.*;

/** Decisions consume a public view; scoring, portals, pickups and melee remain authoritative service actions. */
public final class EventBot extends BotSM {
    private final CompanionTaskService.EventLease lease;
    private final Random random;
    private Object map;
    private Portal door;
    private final Map<Integer,Set<String>> wrongDoors=new HashMap<>();
    private int question=-1;
    private long actAfter,lastProgressAt;
    private Point progress;
    private boolean entered;
    private final double confidence,courseSkill;
    private double fatigue;
    private Point lastCoursePosition;
    private long restUntil;
    private GmEventService.BotTarget coconutTarget;
    EventBot(Character actor,CompanionTaskService.EventLease lease) {
        super(actor); this.lease=lease; random=new Random(lease.generation()*31+actor.getId()); botType="EventBot"; state=BotState.RUNNING;
        confidence=.2+random.nextDouble()*.7;courseSkill=.25+random.nextDouble()*.7;
    }
    @Override public synchronized void startScheduledTask(long delay) {
        super.startScheduledTask(delay); BotTickService.reschedule(getChr().getId(),250);
    }
    @Override public synchronized void stopScheduledTask() { GCMovement.disable(getChr());super.stopScheduledTask(); }
    @Override public void updateState() {
        if(!getRunning()) return;
        var current=CompanionTaskService.shared().eventLease(getChr().getId()).orElse(null);
        if(current==null || current.generation()!=lease.generation() || !current.committed()) return;
        if(lease.role()==CompanionTaskService.EventRole.HOST) { GmEventService.getInstance().hostTick(getChr(),lease);return; }
        var view=GmEventService.getInstance().botView(getChr(),lease);
        if(view==null) { EventBotRuntime.release(getChr().getId(),lease.generation(),true); return; }
        if(!getChr().isAlive()) { GmEventService.getInstance().leave(getChr());return; }
        if(view.phase()!=EventSession.Phase.RUNNING) return;
        if(lease.role()==CompanionTaskService.EventRole.SPECTATOR) {
            if(!view.spectatorMotion()) GCMovement.stop(getChr()); return;
        }
        long now=System.currentTimeMillis();
        if(map!=getChr().getMap()) { map=getChr().getMap(); door=null;coconutTarget=null;lastCoursePosition=null;entered=getChr().getMapId()!=view.lobbyMap();progress=null;lastProgressAt=now; }
        if(progress==null || progress.distanceSq(getChr().getPosition())>16*16) { progress=new Point(getChr().getPosition());lastProgressAt=now; }
        else if((view.key().equals("fitness") || view.key().equals("ola")) && GCMovement.isMoving(getChr())
                && now-lastProgressAt>30_000) { GmEventService.getInstance().leave(getChr());return; }
        if(view.key().equals("ox")) {
            if(question!=view.question()) {
                question=view.question(); actAfter=now+1000+(long)((1-confidence)*7000)+random.nextInt(2500);
            }
            if(now>=actAfter && question>=0) {
                boolean agrees=OxBotKnowledge.choose(question,getChr().getId(),getChr().getLevel(),confidence);
                move(new Point(agrees?-550:50,10),100);
            }
        } else if(view.key().equals("fitness") || view.key().equals("ola")) course(view,now);
        else if(view.key().equals("coconut")) {
            if(coconutTarget==null) coconutTarget=GmEventService.getInstance().coconutTarget(getChr());
            var target=coconutTarget;
            if(target!=null && move(target.position(),140) && now>=actAfter) {
                if(GmEventService.getInstance().coconutHit(getChr(),target.id())) { BotAttack.basicSwing(getChr());actAfter=now+750; }
                else {coconutTarget=null;actAfter=now+250+random.nextInt(500);}
            }
        } else if(view.key().equals("snowball")) {
            int target=(getChr().getId()%4==0?2:0)+getChr().getTeam();
            Point position=GmEventService.getInstance().snowballTarget(getChr(),target);
            if(position!=null && move(position,15) && now>=actAfter
                    && GmEventService.getInstance().snowballHit(getChr(),target)) { BotAttack.basicSwing(getChr());actAfter=now+500; }
        } else if(view.key().equals("treasure")) treasure(now);
    }
    private boolean move(Point position,int reach) {
        if(Math.abs(getChr().getPosition().x-position.x)<=reach && Math.abs(getChr().getPosition().y-position.y)<=100) { GCMovement.stop(getChr());return true; }
        if(!GCMovement.isMoving(getChr())) GCMovement.move(getChr(),position.x,position.y);
        return false;
    }
    private void course(GmEventService.BotView view,long now) {
        if(now<restUntil) {GCMovement.stop(getChr());return;}
        Point position=getChr().getPosition();
        if(lastCoursePosition!=null) fatigue+=Math.min(100,position.distance(lastCoursePosition))*(1-courseSkill*.5);
        lastCoursePosition=new Point(position);
        if(fatigue>1200+courseSkill*1800) {
            fatigue=0;restUntil=now+750+(long)((1-courseSkill)*2250);GCMovement.stop(getChr());return;
        }
        if(door==null) {
            if(!entered) door=getChr().getMap().getPortal("join00");
            else if(view.key().equals("fitness")) door=getChr().getMap().getPortal("in00");
            else {
                var known=wrongDoors.computeIfAbsent(getChr().getMapId(),ignored->new HashSet<>());
                List<Portal> choices=getChr().getMap().getPortals().stream().filter(p->p.getName().matches("ch\\d+") && !known.contains(p.getName())).toList();
                if(choices.isEmpty()) { known.clear();return; }
                door=choices.get(random.nextInt(choices.size()));
            }
        }
        if(door!=null && move(door.getPosition(),30) && now>=actAfter) {
            int before=getChr().getMapId(); String name=door.getName();
            GmEventService.getInstance().coursePortal(getChr(),door); entered=true;door=null;
            if(view.key().equals("ola") && name.startsWith("ch") && before==getChr().getMapId()) wrongDoors.get(before).add(name);
            actAfter=now+250+(long)((1-courseSkill)*1250)+random.nextInt(1000);
        }
    }
    private void treasure(long now) {
        if(GmEventService.getInstance().treasureBotComplete(getChr())) return;
        MapItem loot=getChr().getMap().getMapObjectsInRange(getChr().getPosition(),400*400,List.of(MapObjectType.ITEM)).stream()
                .map(o->(MapItem)o).filter(i->i.getItemId()==TreasureGame.SCROLL && !i.isPickedUp()).findFirst().orElse(null);
        if(loot!=null) { if(move(loot.getPosition(),100)) getChr().pickupItem(loot); return; }
        Reactor chest=getChr().getMap().getAllReactors().stream().filter(r->r.getId()==9002002 && r.isAlive())
                .min(Comparator.comparingDouble(r->r.getPosition().distanceSq(getChr().getPosition()))).orElse(null);
        if(chest!=null) {
            if(move(chest.getPosition(),130) && now>=actAfter && GmEventService.getInstance().reactorHit(getChr(),chest,0)) {
                BotAttack.basicSwing(getChr());chest.hitReactor(true,getChr().getPosition().x,(short)2,0,getChr().getClient());actAfter=now+600;
            }
            return;
        }
        if(getChr().getMapId()==109010000) { GmEventService.getInstance().treasureField(getChr(),getChr().getId()%2==0?109010100:109010200);return; }
        Portal passage=getChr().getMap().getPortals().stream().filter(p->p.getPortalStatus() && p.getTargetMapId()>=109010100 && p.getTargetMapId()<=109010206
                && (p.getScriptName()==null || p.getScriptName().isBlank()) && p.getTargetMapId()!=getChr().getMapId())
                .min(Comparator.comparingDouble(p->p.getPosition().distanceSq(getChr().getPosition()))).orElse(null);
        if(passage!=null && move(passage.getPosition(),30)) passage.enterPortal(getChr().getClient());
    }
}
