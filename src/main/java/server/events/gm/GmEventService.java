package server.events.gm;

import client.Character;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.MapId;
import net.server.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.maps.FieldLimit;
import server.maps.MapleMap;
import server.maps.Portal;
import server.maps.Reactor;
import server.maps.MapItem;
import server.maps.SavedLocationType;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionTaskService;
import tools.PacketCreator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Runtime event owner. Network/warps/game callbacks occur outside the short registry monitor. */
public final class GmEventService {
    private static final Logger log = LoggerFactory.getLogger(GmEventService.class);
    private static final GmEventService INSTANCE = new GmEventService();
    public static GmEventService getInstance() { return INSTANCE; }
    private record ChannelKey(int world, int channel) {}
    private record ActorKey(int world, int actor) {}
    private static final class Live {
        final EventSession session;
        final MapleMap map;
        final Channel channel;
        final boolean oldStarted, oldOx, oldPortalOpen;
        final Event compatibility;
        final Map<Integer,MapleMap> maps = new HashMap<>();
        final Map<Integer,Boolean> oldMapStarted = new HashMap<>();
        final Map<Integer,CompanionTaskService.EventLease> botLeases=new java.util.concurrent.ConcurrentHashMap<>();
        volatile OxQuiz game;
        volatile CourseEvent course;
        volatile CoconutGame coconut;
        volatile SnowballGame snowball;
        volatile TreasureGame treasure;
        volatile ScheduledFuture<?> deadline;
        volatile ScheduledFuture<?> watchdog;
        volatile ScheduledFuture<?> rewardExpiry;
        final EventTelemetry telemetry=new EventTelemetry();
        final EventGovernor governor=new EventGovernor();
        volatile CompanionTaskService.EventLease hostLease;
        volatile int waitingParticipants,waitingSpectators;
        final long openedAt=System.currentTimeMillis();
        Live(EventSession session, MapleMap map, Channel channel) {
            this.session=session; this.map=map; this.channel=channel;
            oldStarted=map.eventStarted(); oldOx=map.isOxQuiz();
            oldPortalOpen=map.getPortal("join00") != null && map.getPortal("join00").getPortalStatus();
            for (int mapId:session.definition().mapIds()) {
                MapleMap field=channel.getMapFactory().getMap(mapId);
                EventMapObstacles.prepare(field);
                maps.put(mapId,field); oldMapStarted.put(mapId,field.eventStarted());
            }
            compatibility = new Event(session.definition().lobbyMapId(), 0) {
                @Override public int getLimit() { return session.freeSeats(); }
                @Override public void minusLimit() { throw new IllegalStateException("Use managed event admission"); }
                @Override public void addLimit() { /* legacy counters cannot modify managed capacity */ }
            };
        }
    }
    private final AtomicLong generations = new AtomicLong();
    private final Map<ChannelKey, Live> current = new HashMap<>();
    private final Map<ActorKey, Live> attendance = new HashMap<>();
    private final EventRequestCoordinator requests=new EventRequestCoordinator(System::currentTimeMillis,new EventRequestCoordinator.Gateway() {
        public boolean approved(String key) {
            var definition=EventDefinition.find(key);var profile=EventCapacityProfiles.forEvent(key);
            return definition!=null && definition.available() && profile!=null && profile.requestsApproved() && profile.verified(definition);
        }
        public boolean ready(int actorId,EventRequestCoordinator.ChannelId key) {
            var world=net.server.Server.getInstance().getWorld(key.world());
            Character actor=world==null?null:world.getPlayerStorage().getCharacterById(actorId);
            return actor!=null && !BotHelpers.isBot(actor) && !actor.isGM() && actor.isAlive() && !actor.isChangingMaps()
                    && actor.getClient().getChannel()==key.channel() && actor.getTrade()==null && actor.getEventInstance()==null
                    && actor.getMap()!=null && !FieldLimit.CANNOTMIGRATE.check(actor.getMap().getFieldLimit());
        }
        public boolean busy(EventRequestCoordinator.ChannelId key) {synchronized(GmEventService.this){return current.containsKey(new ChannelKey(key.world(),key.channel()));}}
        public EventRequestCoordinator.Delivery dispatch(EventRequestCoordinator.Request request) {
            var channel=net.server.Server.getInstance().getChannel(request.channel().world(),request.channel().channel());
            if(channel==null) return null;
            createBotHost(channel,request.key(),1);Live live;
            synchronized(GmEventService.this) {live=current.get(key(channel));}
            if(live==null || live.hostLease==null || !live.session.definition().key().equals(request.key())) return null;
            Set<Integer> joined=new java.util.HashSet<>();
            for(var entry:request.accountActors().entrySet()) {
                Character actor=channel.getPlayerStorage().getCharacterById(entry.getValue());
                if(actor!=null && join(actor).startsWith("You joined")) joined.add(entry.getKey());
            }
            if(joined.isEmpty()) {cancel(live,"No requesting human could join");return new EventRequestCoordinator.Delivery(Set.of());}
            live.waitingParticipants=Math.max(0,live.session.snapshot().capacity().activeLimit()-live.session.snapshot().humans());
            return new EventRequestCoordinator.Delivery(joined);
        }
    });
    private volatile ScheduledFuture<?> requestPump;
    private GmEventService() {}
    public String request(Character actor,String key) {
        var definition=EventDefinition.find(key);var profile=EventCapacityProfiles.forEvent(key);
        if(definition==null || profile==null || !profile.requestsApproved() || !profile.verified(definition))
            return "No approved measured profile for "+key+". Gameplay and bot verification are required before player requests.";
        synchronized(this) {
            if(requestPump==null) requestPump=TimerManager.getInstance().register(()->{
                try {requests.pump();} catch(RuntimeException failure) {log.error("Event request pump failed",failure);}
            },1000,1000);
        }
        return requests.request(actor.getClient().getAccID(),actor.getId(),new EventRequestCoordinator.ChannelId(actor.getWorld(),actor.getClient().getChannel()),key,profile.pending());
    }
    public String open(Character host) {
        Live live=authorized(host,"open");
        if(live==null) return "No event owned by you on this channel.";
        return live.governor.admits() && live.session.reopen()?"Registration is open.":"Registration cannot reopen during this phase/load.";
    }
    public List<String> telemetry(Channel channel) {
        Live live;synchronized(this){live=current.get(key(channel));}
        return live==null?List.of("No event telemetry session."):live.telemetry.report();
    }

    public String create(Character host, String key, int operatorLimit) {
        if (host.gmLevel() < 3) return "Only a GM may create an event.";
        return createInternal(host,key,operatorLimit);
    }
    private String createInternal(Character host,String key,int operatorLimit) {
        EventDefinition definition = EventDefinition.find(key);
        if (definition == null) return "Unknown event key. Use @event list.";
        if (!definition.available()) return definition.key() + ": " + definition.unavailableReason();
        if (operatorLimit < 1) return "The development admission limit must be positive.";
        Channel channel=host.getClient().getChannelServer();
        MapleMap map=channel.getMapFactory().getMap(definition.lobbyMapId());
        if (map == null || map.getPortal(0) == null
                || ((isCourse(definition) || definition.key().equals("ox")) && map.getPortal("join00") == null))
            return "Required event map/portals are missing.";
        for(int mapId:definition.mapIds()) {
            MapleMap field=channel.getMapFactory().getMap(mapId);
            if(field==null || field.getId()!=mapId || field.getPortal(0)==null)
                return "Required event map " + mapId + " is missing.";
            if(field.eventStarted() || field.getOx()!=null || field.getCoconut()!=null
                    || field.getSnowball(0)!=null || field.getSnowball(1)!=null)
                return "The map is already running an event.";
            if(isCourse(definition) && field.getPortal("start00")==null && mapId!=definition.lobbyMapId())
                return "Course start portal is missing in " + mapId + ".";
            if(definition.key().equals("fitness") && field.getPortal("in00")==null)
                return "Fitness stage portal is missing in " + mapId + ".";
            if(definition.key().equals("ola") && field.getPortal("ch00")==null)
                return "Ola choice portals are missing in " + mapId + ".";
            if(mapId==MapId.EVENT_SNOWBALL && (field.getPortal("st00")==null || field.getPortal("st01")==null))
                return "Snowball team starting portals are missing.";
        }
        if ("ox".equals(definition.key()) && OxQuiz.loadQuestions().size() < definition.rounds())
            return "Insufficient valid OX question keys.";
        var profile=EventCapacityProfiles.forEvent(key);
        EventSession.Capacity capacity=profile!=null && profile.verified(definition)?profile.capacity():EventSession.Capacity.development(operatorLimit);
        EventSession session=new EventSession(generations.incrementAndGet(), definition,
                capacity, System::currentTimeMillis,
                host.getWorld(), channel.getId(), host.getId());
        Live live=new Live(session,map,channel);
        synchronized (this) {
            ChannelKey channelKey=key(channel);
            if (current.containsKey(channelKey) || channel.getEvent()!=null)
                return "This channel already has an event. Close entry, finish or cancel it first.";
            if(!EventMapLeases.acquire(session.id().toString(),live.maps.values())) return "An event/incident already leases one of these map instances.";
            session.open(); current.put(channelKey,live); channel.setEvent(live.compatibility);
            EventInstrumentation.register(session.id().toString(),live.maps.values(),live.telemetry);
        }
        live.watchdog=TimerManager.getInstance().register(() -> supervise(live), 250, 250);
        channel.broadcastPacket(PacketCreator.serverNotice(6, "[Event " + session.id() + "] "
                + definition.key() + " registration on channel " + channel.getId() + ": @joinevent. "
                + (capacity.botsVerified()?"Measured profile "+capacity.measuredProfileId()+"; active limit "+capacity.activeLimit()+".":
                "Development operator limit " + operatorLimit + "; capacity is not measured.")));
        return "Opened " + definition.key() + " " + session.id() + ". Start with !event start after entrants join.";
    }

    public String join(Character actor) {
        Channel channel=actor.getClient().getChannelServer();
        Live live;
        EventSession.Admission token;
        ActorKey actorKey=new ActorKey(actor.getWorld(),actor.getId());
        if (!actor.isAlive() || actor.isGM() || actor.isChangingMaps() || actor.getTrade()!=null
                || actor.getEventInstance()!=null || actor.getMap()==null
                || FieldLimit.CANNOTMIGRATE.check(actor.getMap().getFieldLimit())) return "You cannot join from your current state/map.";
        boolean bot=BotHelpers.isBot(actor);
        // Until measured fair-play adapters exist, bot attendance is deliberately unavailable.
        if (bot || CompanionTaskService.shared().owned(actor.getId())) return "Bot attendance requires a verified event adapter and capacity profile.";
        synchronized (this) {
            live=current.get(key(channel));
            if (live==null) return "There is no managed event on this channel.";
            if(!live.governor.admits()) return "Entry is temporarily paused while the event recovers from load.";
            if (attendance.containsKey(actorKey) || actor.getMap()==live.map) return "You are already in an event.";
            token=live.session.reserve(actor.getId(),false,actor.getMapId()).orElse(null);
            if(token==null) return "Entry is closed or the development admission limit is full.";
            attendance.put(actorKey,live);
        }
        boolean committed=false;
        try {
            if (!isRegistration(live)) return "The event was cancelled before arrival.";
            synchronized(actor) {
                if(!isRegistration(live)) return "The event was cancelled before arrival.";
                actor.saveLocation("EVENT"); actor.saveLocationOnWarp();
                actor.setTeam(token.team()); actor.changeMap(live.map,live.map.getPortal(0));
            }
            synchronized (this) {
                committed=current.get(key(channel))==live && attendance.get(actorKey)==live
                        && actor.getMap()==live.map && actor.isAlive()
                        && actor.getWorld()==live.session.worldId()
                        && actor.getClient().getChannel()==live.session.channelId()
                        && live.session.commit(token);
            }
            return committed ? "You joined " + live.session.definition().key() + "." : "Arrival was cancelled; your place was released.";
        } finally {
            if (!committed) {
                synchronized (this) {
                    live.session.leave(actor.getId()); attendance.remove(actorKey,live);
                }
                // Never let an old arrival move an actor now owned by a newer session.
                boolean unowned;
                synchronized(this) { unowned=!attendance.containsKey(actorKey); }
                if(unowned && actor.getMap()==live.map) returnActor(actor,token.returnMapId());
            }
        }
    }

    public record BotView(String key,EventSession.Phase phase,int lobbyMap,int question,boolean spectatorMotion) {}
    public record BotTarget(int id,java.awt.Point position) {}
    public BotView botView(Character actor,CompanionTaskService.EventLease lease) {
        Live live;
        synchronized(this) { live=attendance.get(new ActorKey(actor.getWorld(),actor.getId())); }
        var currentLease=CompanionTaskService.shared().eventLease(actor.getId()).orElse(null);
        if(live==null || currentLease==null || !currentLease.equals(lease) || !lease.committed()
                || !lease.eventId().equals(live.session.id().toString()) || live.session.terminal()
                || actor.getWorld()!=live.session.worldId() || actor.getClient().getChannel()!=live.session.channelId()
                || live.maps.get(actor.getMapId())!=actor.getMap()) return null;
        return new BotView(live.session.definition().key(),live.session.snapshot().phase(),live.map.getId(),
                live.game==null?-1:live.game.publicRound(),live.governor.spectatorMotion());
    }
    /** Shared damage/movement capability for actual participants; public-world incident capability is separate. */
    public boolean realParticipant(Character actor) {
        return realPresence(actor,false);
    }
    public boolean realPresence(Character actor) { return realPresence(actor,true); }
    private boolean realPresence(Character actor,boolean includeSpectators) {
        if(actor==null || !actor.isAlive()) return false;
        Live live;
        synchronized(this) { live=attendance.get(new ActorKey(actor.getWorld(),actor.getId())); }
        if(live==null || !isCurrent(live) || actor.getClient().getChannel()!=live.session.channelId()
                || live.maps.get(actor.getMapId())!=actor.getMap()) return false;
        var lease=CompanionTaskService.shared().eventLease(actor.getId()).orElse(null);
        return lease!=null && lease.committed() && lease.eventId().equals(live.session.id().toString())
                && (lease.role()==CompanionTaskService.EventRole.PARTICIPANT && live.session.active(actor.getId())
                || includeSpectators && lease.role()==CompanionTaskService.EventRole.SPECTATOR);
    }
    public BotTarget coconutTarget(Character actor) {
        Live live;
        synchronized(this) { live=attendance.get(new ActorKey(actor.getWorld(),actor.getId())); }
        return live!=null && live.coconut!=null && participant(live,actor.getId())?live.coconut.target(actor.getPosition()):null;
    }
    public java.awt.Point snowballTarget(Character actor,int target) {
        Live live;
        synchronized(this) {live=attendance.get(new ActorKey(actor.getWorld(),actor.getId()));}
        return live!=null && live.snowball!=null && participant(live,actor.getId())?live.snowball.target(actor.getTeam(),target):null;
    }
    public boolean snowballCollision(Character actor,java.awt.Point previous) {
        if(actor==null || actor.getMapId()!=MapId.EVENT_SNOWBALL) return false;
        Live live;
        synchronized(this) {live=attendance.get(new ActorKey(actor.getWorld(),actor.getId()));}
        return live!=null && live.snowball!=null && live.snowball.collide(actor,previous,System.currentTimeMillis());
    }
    public boolean treasureBotComplete(Character actor) {
        Live live;
        synchronized(this) { live=attendance.get(new ActorKey(actor.getWorld(),actor.getId())); }
        if(live==null || live.treasure==null || !treasureParticipant(live,actor.getId()) || !live.treasure.eligible(actor.getId())
                || !BotHelpers.isBot(actor) || !live.session.finishActor(actor.getId())) return false;
        EventBotRuntime.award(actor.getId());leave(actor);return true;
    }
    public String attend(Character host,int count,boolean spectators) {
        Live live=authorized(host,"attend");
        if(live==null) return "No event owned by you on this channel.";
        if(count<1 || !live.session.snapshot().capacity().botsVerified() && !live.session.botTrial()) return "Install a verified measured capacity profile, or use the explicit GM development trial.";
        synchronized(live) {
            if(!isRegistration(live)) return "Entry is closed.";
            if(spectators) live.waitingSpectators=Math.min(count,live.session.snapshot().capacity().visibleLimit());
            else live.waitingParticipants=Math.min(count,live.session.snapshot().capacity().activeLimit());
        }
        return "Queued finite bot attendance; arrivals use the profile's measured burst budget.";
    }
    /** A GM-controlled development run collects evidence; it never approves bot hosts or player requests. */
    public String trialBots(Character host,int count,boolean spectators) {
        Live live=authorized(host,"trial-bots");
        if(live==null || BotHelpers.isBot(host) || live.hostLease!=null || !live.session.enableBotTrial()) return "Create a human-GM development session first.";
        String result=attend(host,count,spectators);
        log.info("UNMEASURED event bot trial {} host {} requested {} spectators={}",live.session.id(),host.getId(),count,spectators);
        return "UNMEASURED GM development trial. "+result;
    }
    private String attend(Live live,int count,boolean spectators) {
        if(count<1 || !live.session.snapshot().capacity().botsVerified() && !live.session.botTrial()) return "Install a verified measured capacity profile before bot attendance.";
        var profile=EventCapacityProfiles.forEvent(live.session.definition().key());
        if(profile!=null && !profile.verified(live.session.definition())) profile=null;
        if(profile==null && !live.session.botTrial()) return "Measured profile is missing or incompatible.";
        int worldBudget=profile==null?live.session.snapshot().capacity().visibleLimit():profile.worldLimit();
        int accepted=0;
        for(BotSM previous:CharacterStorage.getAllBots().values()) {
            if(accepted>=count || !isRegistration(live) || !live.governor.admits() || !live.governor.arrivals()) break;
            if(!spectators && (live.coconut!=null || live.session.definition().key().equals("coconut") || live.session.definition().key().equals("snowball") || live.session.definition().key().equals("treasure"))
                    && !EventMelee.legal(previous.getChr())) continue;
            var lease=EventBotRuntime.reserve(previous,live.session.id().toString(),spectators?CompanionTaskService.EventRole.SPECTATOR:CompanionTaskService.EventRole.PARTICIPANT,
                    live.session.worldId(),live.session.channelId(),worldBudget).orElse(null);
            if(lease==null) continue;
            Character actor=previous.getChr(); EventSession.Admission token;
            live.botLeases.put(actor.getId(),lease);
            synchronized(this) {
                token=live.session.reserve(actor.getId(),true,actor.getMapId(),spectators).orElse(null);
                if(token!=null) attendance.put(new ActorKey(actor.getWorld(),actor.getId()),live);
            }
            boolean committed=false;
            try {
                if(token==null) continue;
                synchronized(actor) {
                    if(!isRegistration(live)) continue;
                    actor.saveLocation("EVENT"); actor.setTeam(token.team()); actor.changeMap(live.map,live.map.getPortal(0));
                    synchronized(this) {
                        committed=isRegistration(live) && actor.getMap()==live.map && actor.isAlive()
                                && live.session.commit(token) && CompanionTaskService.shared().commitEvent(lease).isPresent();
                    }
                }
                if(committed && EventBotRuntime.activate(previous,CompanionTaskService.shared().eventLease(actor.getId()).orElseThrow())) accepted++;
                else committed=false;
            } finally {
                if(!committed) {
                    if(token!=null) leave(actor);
                    live.botLeases.remove(actor.getId(),lease);
                    CompanionTaskService.shared().releaseEvent(actor.getId(),lease.generation());
                }
            }
        }
        return "Admitted "+accepted+" bot "+(spectators?"spectators":"participants")+"; profile "+(profile==null?"UNMEASURED-development":profile.id())+".";
    }
    private void pumpArrivals(Live live) {
        if(!isRegistration(live) || !live.governor.admits() || !live.governor.arrivals()) return;
        var profile=EventCapacityProfiles.forEvent(live.session.definition().key());
        if(profile!=null && !profile.verified(live.session.definition())) profile=null;
        if(profile==null && !live.session.botTrial()) return;
        int burst=profile==null?2:Math.min(profile.arrivalBurst(),profile.promotionBudget());
        int participants,spectators;
        synchronized(live) {
            participants=Math.min(live.waitingParticipants,burst);
            spectators=Math.min(live.waitingSpectators,Math.max(0,burst-participants));
            live.waitingParticipants-=participants;live.waitingSpectators-=spectators;
        }
        if(participants>0) attend(live,participants,false);
        if(spectators>0) attend(live,spectators,true);
    }
    public String botHost(Character organizer,String key,int operatorLimit) {
        if(organizer.gmLevel()<3) return "Only a GM may directly schedule a bot host.";
        return createBotHost(organizer.getClient().getChannelServer(),key,operatorLimit,false);
    }
    /** Operator-only evidence run. It cannot approve player requests or create a measured profile. */
    public String trialBotHost(Character organizer,String key,int operatorLimit,int botParticipants) {
        if(organizer.gmLevel()<3 || BotHelpers.isBot(organizer)) return "Only a human GM may run an unmeasured bot-host trial.";
        if(operatorLimit<2) return "A bot-host trial needs room for the host and at least one human.";
        if(botParticipants<0 || botParticipants>operatorLimit-2) return "Leave one human seat free after the host and queued bots.";
        Channel channel=organizer.getClient().getChannelServer();
        String result=createBotHost(channel,key,operatorLimit,true);
        if(!result.startsWith("UNMEASURED GM development trial. Opened ")) return result;
        Live live;synchronized(this) {live=current.get(key(channel));}
        if(live==null || live.hostLease==null || !live.session.botTrial()) return "Bot-host trial ended during setup.";
        synchronized(live) {live.waitingParticipants=botParticipants;}
        return result+" Queued "+botParticipants+" finite bot participants; one human seat stays open.";
    }
    private String createBotHost(Channel channel,String key,int operatorLimit) {
        return createBotHost(channel,key,operatorLimit,false);
    }
    private String createBotHost(Channel channel,String key,int operatorLimit,boolean trial) {
        EventDefinition definition=EventDefinition.find(key);
        var profile=EventCapacityProfiles.forEvent(key);
        boolean measured=definition!=null && profile!=null && profile.verified(definition);
        if(!trial && !measured) return "Bot hosting requires verified human/bot gameplay and measured capacity.";
        if(trial && !measured && (definition==null || !definition.available())) return "Unknown or unavailable event key.";
        BotSM previous=CharacterStorage.getAllBots().values().stream().filter(b->EventBotRuntime.eligible(b,channel.getWorld(),channel.getId())).findFirst().orElse(null);
        if(previous==null) return "No available supported bot can host on this channel.";
        Character host=previous.getChr(); String result=createInternal(host,key,operatorLimit); Live live;
        if(!result.startsWith("Opened ")) return result;
        synchronized(this) {live=current.get(key(channel));}
        if(live==null || live.session.hostId()!=host.getId()) return result;
        if(trial && !measured && !live.session.enableBotTrial()) {cancel(live,"Bot-host trial could not open");return "Bot-host trial could not open.";}
        int worldLimit=measured?profile.worldLimit():operatorLimit;
        var lease=EventBotRuntime.reserve(previous,live.session.id().toString(),CompanionTaskService.EventRole.HOST,channel.getWorld(),channel.getId(),worldLimit).orElse(null);
        if(lease==null) {cancel(live,"Bot host became unavailable");return "Bot host became unavailable.";}
        live.hostLease=CompanionTaskService.shared().commitEvent(lease).orElse(null);
        live.session.hostVisible(true);
        if(live.hostLease==null || !EventBotRuntime.activate(previous,live.hostLease)) {cancel(live,"Bot host activation failed");return "Bot host activation failed.";}
        try {synchronized(host) {host.changeMap(live.map,live.map.getPortal(0));}}
        catch(RuntimeException failedWarp) {cancel(live,"Bot host could not reach the event map");return "Bot host could not reach the event map.";}
        if(host.getMap()!=live.map) {cancel(live,"Bot host did not arrive in the event map");return "Bot host did not arrive in the event map.";}
        channel.broadcastPacket(PacketCreator.serverNotice(6,"[Event "+live.session.id()+"] Host "
                +GmHostPresentation.displayName(host)+" is at map "+live.map.getId()+". "
                +(measured?"Measured profile "+profile.id():"UNMEASURED GM development trial")+"; join with @joinevent."));
        if(trial && !measured) log.info("UNMEASURED bot-host trial {} host {} key {} operator limit {}",live.session.id(),host.getId(),key,operatorLimit);
        return (trial && !measured?"UNMEASURED GM development trial. ":"")+result;
    }
    public void hostTick(Character host,CompanionTaskService.EventLease lease) {
        Live live;
        synchronized(this) {live=current.get(key(host.getClient().getChannelServer()));}
        if(live==null || live.session.hostId()!=host.getId() || !lease.equals(live.hostLease)) {EventBotRuntime.release(host.getId(),lease.generation(),true);return;}
        if(!host.isAlive() || !lease.equals(CompanionTaskService.shared().eventLease(host.getId()).orElse(null))) {cancel(live,"Bot host failed");return;}
        long age=System.currentTimeMillis()-live.openedAt;
        if(live.session.snapshot().phase()==EventSession.Phase.REGISTRATION && age>=30_000) {
            if(live.session.snapshot().humans()>0) countdown(live);
            else if(age>=(live.session.botTrial()?300_000:90_000)) cancel(live,"No participating human joined the requested event");
        }
    }

    public String start(Character host) {
        Live live=authorized(host,"start");
        if(live==null) return "No event owned by you on this channel.";
        return countdown(live);
    }
    private String countdown(Live live) {
        synchronized(this) { if(current.get(key(live.channel))!=live || !live.session.countdown()) return "At least one active entrant is required; event may already be running."; }
        live.map.broadcastMessage(PacketCreator.serverNotice(6,"[Event] "+live.session.definition().key()+" starts in ten seconds. Entry is closed."));
        live.map.broadcastMessage(PacketCreator.getClock(10));
        live.deadline=TimerManager.getInstance().schedule(()->{
            if(live.session.snapshot().phase()!=EventSession.Phase.COUNTDOWN) return;
            String result=startNow(live);
            if(!live.session.running(live.session.generation())) cancel(live,result);
        },10_000);
        return "Ten-second countdown started; entry is closed.";
    }
    private String startNow(Live live) {
        if(live.session.definition().key().equals("treasure")) return startTreasure(live);
        if(isCourse(live.session.definition())) return startCourse(live);
        if(live.session.definition().key().equals("coconut") || live.session.definition().key().equals("snowball"))
            return startTeams(live);
        List<OxQuiz.Question> questions=OxQuiz.loadQuestions();
        if(questions.size()<live.session.definition().rounds()) return "Insufficient valid OX questions.";
        synchronized(this) {
            if(current.get(key(live.channel))!=live || !live.session.start()) return "At least one active entrant is required; event may already be running.";
            live.game=new OxQuiz(live.map,questions,live.session.definition().rounds(),live.session.generation(),
                    () -> isCurrent(live), id -> participant(live,id),
                    id -> eliminate(live,id), () -> finishOx(live));
            live.map.setOx(live.game); live.map.setOxQuiz(true); live.map.startEvent();
            live.map.getPortal("join00").setPortalStatus(false);
        }
        live.game.sendQuestion();
        return "OX started: " + live.session.definition().rounds() + " questions, common 30-second deadlines.";
    }

    private String startCourse(Live live) {
        synchronized(this) {
            if(current.get(key(live.channel))!=live || !live.session.start())
                return "At least one active entrant is required; event may already be running.";
            live.course=new CourseEvent(live.session.definition(),live.session.id().getMostSignificantBits());
            for(var entrant:live.session.entrants()) live.course.admit(entrant.admission().actorId());
            for(MapleMap field:live.maps.values()) field.startEvent();
            live.map.getPortal("join00").setPortalStatus(true);
        }
        for(var entrant:live.session.entrants()) {
            Character actor=live.channel.getPlayerStorage().getCharacterById(entrant.admission().actorId());
            if(actor!=null) {
                sendClock(actor);
                actor.dropMessage(5,"The course is open. Complete every stage before the common deadline; death eliminates you.");
            }
        }
        live.deadline=TimerManager.getInstance().schedule(() -> finishCourse(live),
                Math.max(1,live.session.snapshot().deadlineMs()-System.currentTimeMillis()));
        return live.session.definition().key()+" started; all entrants share a "
                +live.session.definition().roundMillis()/1000+"-second deadline.";
    }

    private String startTreasure(Live live) {
        if(live.session.snapshot().phase()!=EventSession.Phase.COUNTDOWN) return "Event is already running.";
        TreasureGame game;
        try { game=new TreasureGame(live.maps.values(),live.session.id(),()->isCurrent(live),id->treasureParticipant(live,id)); }
        catch(IllegalStateException missing) { return missing.getMessage(); }
        synchronized(this) {
            if(current.get(key(live.channel))!=live || !live.session.start()) return "At least one active entrant is required.";
            live.treasure=game;
            for(MapleMap field:live.maps.values()) field.startEvent();
        }
        game.start();
        for(var e:live.session.entrants()) {
            Character actor=live.channel.getPlayerStorage().getCharacterById(e.admission().actorId());
            if(actor!=null) {
                actor.dropMessage(5,"Treasure Hunt: ten minutes. Use basic melee to break chests, collect a Treasure Scroll and redeem it with the event assistant. One prize per entrant.");
                sendClock(actor);
            }
        }
        live.deadline=TimerManager.getInstance().schedule(()->finishTreasure(live),
                Math.max(1,live.session.snapshot().deadlineMs()-System.currentTimeMillis()));
        return "Treasure Hunt started with "+game.chestCount()+" finite chests and a common ten-minute clock.";
    }
    private boolean treasureParticipant(Live live,int id) {
        return treasurePresent(live,id) && System.currentTimeMillis()<live.session.snapshot().deadlineMs();
    }
    private boolean treasurePresent(Live live,int id) {
        Character actor=live.channel.getPlayerStorage().getCharacterById(id);
        return live.session.active(id) && actor!=null && actor.isAlive() && !actor.isGM() && !actor.isChangingMaps()
                && actor.getWorld()==live.session.worldId() && actor.getClient().getChannel()==live.session.channelId()
                && live.maps.get(actor.getMapId())==actor.getMap();
    }
    /** Unmanaged treasure reactors reject attacks; other reactor content retains its canonical behavior. */
    public boolean reactorHit(Character actor,Reactor reactor,int skill) {
        if(reactor.getId()!=9002002 || reactor.getMap().getId()<109010000 || reactor.getMap().getId()>109010206) return true;
        Live live;
        synchronized(this) { live=attendance.get(new ActorKey(actor.getWorld(),actor.getId())); }
        return live!=null && live.treasure!=null && live.treasure.permitHit(actor,reactor,skill,System.currentTimeMillis());
    }
    public void treasureChestBroken(Character actor,Reactor reactor) {
        Live live;
        synchronized(this) { live=attendance.get(new ActorKey(actor.getWorld(),actor.getId())); }
        if(live!=null && live.treasure!=null) live.treasure.broken(actor,reactor);
    }
    public boolean treasurePickup(Character actor,MapItem item,int petIndex) {
        List<Live> sessions;
        synchronized(this) { sessions=current.values().stream().filter(l->l.treasure!=null).toList(); }
        for(Live live:sessions) if(live.treasure.owns(item)) return live.treasure.pickup(actor,item,petIndex,System.currentTimeMillis());
        return false;
    }
    public String treasureField(Character actor,int mapId) {
        synchronized(actor) {
            Live live;
            synchronized(this) { live=attendance.get(new ActorKey(actor.getWorld(),actor.getId())); }
            if(live==null || live.treasure==null || !treasureParticipant(live,actor.getId())) return "Join a running Treasure Hunt first.";
            if(mapId!=109010100 && mapId!=109010200) return "Unknown treasure field.";
            actor.changeMap(live.maps.get(mapId),live.maps.get(mapId).getPortal(0)); sendClock(actor); return "Search for treasure chests.";
        }
    }
    public boolean redeemTreasure(Character actor) {
        synchronized(actor) {
            Live live;
            synchronized(this) {live=attendance.get(new ActorKey(actor.getWorld(),actor.getId()));}
            if(live==null || live.treasure==null || !treasureParticipant(live,actor.getId()) || !live.treasure.eligible(actor.getId())) return false;
            if(!actor.canHold(live.session.definition().rewardItemId())) return false;
            MapleMap destination=live.channel.getMapFactory().getMap(104000000);
            if(destination==null || destination.getPortal(0)==null) return false;
            actor.changeMap(destination,destination.getPortal(0));
            if(actor.getMap()!=destination || !isCurrent(live) || !actor.isAlive() || actor.isChangingMaps()) return false;
            if(!live.session.finishActor(actor.getId())) return false;
            return claimRewardLocked(actor);
        }
    }
    private void finishTreasure(Live live) {
        if(!isCurrent(live)) return;
        Set<Integer> winners=live.treasure.closeAndEligible().stream().filter(id->treasurePresent(live,id)).collect(Collectors.toSet());
        settleAndReturn(live,winners);
    }

    private String startTeams(Live live) {
        boolean snow=live.session.definition().key().equals("snowball");
        var entrants=live.session.entrants();
        if(entrants.stream().noneMatch(e -> e.attendance()==EventSession.Attendance.ACTIVE && e.admission().team()==0)
                || entrants.stream().noneMatch(e -> e.attendance()==EventSession.Attendance.ACTIVE && e.admission().team()==1))
            return "At least one entrant on each team is required.";
        synchronized(this) {
            if(current.get(key(live.channel))!=live || live.session.snapshot().phase()!=EventSession.Phase.COUNTDOWN)
                return "Event may already be running.";
            if(snow) live.snowball=new SnowballGame(live.maps.get(MapId.EVENT_SNOWBALL),()->isCurrent(live),
                    id->participant(live,id),team->finishTeam(live,team),live.session.generation());
            else live.coconut=new CoconutGame(live.map,()->isCurrent(live),id->participant(live,id),
                    team->finishTeam(live,team),live.session.generation(),()->live.session.extendDeadline(live.session.generation(),120_000));
            if(!live.session.start()) return "At least one active entrant is required.";
            MapleMap arena=competitionMap(live);arena.startEvent();
            if(snow) {arena.setSnowball(0,live.snowball.team(0));arena.setSnowball(1,live.snowball.team(1));}
        }
        if(snow) {
            MapleMap arena=live.maps.get(MapId.EVENT_SNOWBALL);
            for(var entrant:live.session.entrants()) {
                Character actor=live.channel.getPlayerStorage().getCharacterById(entrant.admission().actorId());
                if(actor!=null) synchronized(actor) {
                    if(isCurrent(live)) actor.changeMap(arena,arena.getPortal("st0"+Math.max(0,entrant.admission().team())));
                }
            }
            live.snowball.start();
        } else live.coconut.start();
        return live.session.definition().key()+" started. Basic melee only; both teams share the same rules.";
    }
    public boolean coconutHit(Character actor,int id) {
        return coconutHit(actor,id,System.nanoTime());
    }
    public boolean coconutHit(Character actor,int id,long receivedNs) {
        Live live;
        synchronized(this) {live=attendance.get(new ActorKey(actor.getWorld(),actor.getId()));}
        long began=receivedNs;
        try {return live!=null && live.coconut!=null && live.coconut.hit(actor,id,System.currentTimeMillis());}
        finally {if(live!=null) live.telemetry.action(began);}
    }
    public boolean snowballHit(Character actor,int target) {
        return snowballHit(actor,target,System.nanoTime());
    }
    public boolean snowballHit(Character actor,int target,long receivedNs) {
        Live live;
        synchronized(this) {live=attendance.get(new ActorKey(actor.getWorld(),actor.getId()));}
        long began=receivedNs;
        try {return live!=null && live.snowball!=null && live.snowball.hit(actor,target,System.currentTimeMillis());}
        finally {if(live!=null) live.telemetry.action(began);}
    }
    private void finishTeam(Live live,int team) {
        if(!isCurrent(live)) return;
        Set<Integer> winners=live.session.entrants().stream().filter(e -> e.admission().team()==team)
                .map(e -> e.admission().actorId()).filter(id->participant(live,id))
                .filter(id -> {
                    Character actor=live.channel.getPlayerStorage().getCharacterById(id);
                    return actor!=null && actor.isAlive();
                }).collect(Collectors.toSet());
        settleAndReturn(live,winners);
    }

    /** Called after packet portal identity/distance checks. Returns true when managed or rejected. */
    public boolean coursePortal(Character actor,Portal portal) {
        if(!MapId.isPhysicalFitness(actor.getMapId()) && !MapId.isOlaOla(actor.getMapId())) return false;
        synchronized(actor) {
            Live live;
            synchronized(this) { live=attendance.get(new ActorKey(actor.getWorld(),actor.getId())); }
            if(live==null || live.course==null || !isCurrent(live) || !live.session.active(actor.getId())
                    || !actor.isAlive() || actor.isChangingMaps() || actor.getClient().getChannel()!=live.session.channelId()
                    || live.maps.get(actor.getMapId())!=actor.getMap()
                    || actor.getMap().getPortal(portal.getName())!=portal
                    || !portal.getPortalStatus() || portal.getPosition().distanceSq(actor.getPosition())>160*160
                    || System.currentTimeMillis()>=live.session.snapshot().deadlineMs()) {
                actor.sendPacket(PacketCreator.enableActions()); return true;
            }
            CourseEvent.Transition transition=live.course.plan(actor.getId(),actor.getMapId(),portal.getName(),
                    portal.getTargetMapId(),portal.getTarget());
            if(transition==null) { actor.sendPacket(PacketCreator.enableActions()); return true; }
            MapleMap destination=live.channel.getMapFactory().getMap(transition.destinationMap());
            Portal target=destination==null?null:destination.getPortal(transition.destinationPortal());
            if(target==null) {
                live.course.abandon(transition); cancel(live,"Required course destination portal disappeared"); return true;
            }
            if(!isCurrent(live)) { live.course.abandon(transition); return true; }
            actor.changeMap(destination,target);
            if(!live.course.commit(transition,actor.getMapId())) {
                live.course.abandon(transition); leave(actor); return true;
            }
            if(transition.finish()) {
                if(!live.session.finishActor(actor.getId())) { leave(actor);return true; }
                actor.sendPacket(PacketCreator.removeClock());
                if(BotHelpers.isBot(actor)) {EventBotRuntime.award(actor.getId());leave(actor);}
                actor.dropMessage(5,"Course complete. Talk to the winner NPC to claim your prize.");
                if(live.session.entrants().stream().noneMatch(e -> live.session.active(e.admission().actorId()))) finishCourse(live);
            } else sendClock(actor);
            return true;
        }
    }
    public void sendClock(Character actor) {
        Live live;
        synchronized(this) { live=attendance.get(new ActorKey(actor.getWorld(),actor.getId())); }
        if(live!=null && (live.course!=null || live.treasure!=null) && live.session.active(actor.getId()) && live.maps.get(actor.getMapId())==actor.getMap())
            actor.sendPacket(PacketCreator.getClock((int)Math.max(0,(live.session.snapshot().deadlineMs()-System.currentTimeMillis()+999)/1000)));
    }
    private void finishCourse(Live live) {
        if(!isCurrent(live) || !live.session.settle(live.course.finished())) return;
        cleanupMap(live);
        for(var entrant:live.session.entrants()) {
            if(entrant.attendance()==EventSession.Attendance.WINNER) continue;
            Character actor=live.channel.getPlayerStorage().getCharacterById(entrant.admission().actorId());
            if(actor!=null) leave(actor);
        }
        retire(live); expireRewards(live);
    }
    public String finish(Character host) {
        Live live=authorized(host,"finish");
        if(live==null) return "No event owned by you on this channel.";
        if(live.treasure!=null) { finishTreasure(live); return "Treasure Hunt finished using actual scroll pickups."; }
        if(live.course!=null) { finishCourse(live); return "Course finished using recorded completions."; }
        if(live.coconut!=null) {live.coconut.finishNow(); return "Coconut finished using actual scores.";}
        if(live.snowball!=null) {live.snowball.finishNow(); return "Snowball finished using actual distances.";}
        return "OX must finish its selected questions; cancel aborts without prizes.";
    }
    public String transferHost(Character host,String targetName) {
        Live live=authorized(host,"transfer-host");
        if(live==null) return "No event owned by you on this channel.";
        Character target=live.channel.getPlayerStorage().getCharacterByName(targetName);
        if(target==null || target.gmLevel()<3 || !live.session.transferHost(live.session.hostId(),target.getId()))
            return "The new host must be an online GM rank 3+ on this channel, outside the roster.";
        var oldLease=live.hostLease;
        live.hostLease=null;
        live.session.hostVisible(false);
        if(oldLease!=null) EventBotRuntime.release(oldLease.botId(),oldLease.generation(),true);
        log.info("GM event {} host transferred {} -> {}",live.session.id(),host.getId(),target.getId());
        return "Event host transferred to "+target.getName()+".";
    }

    public String closeEntry(Character host) {
        Live live=authorized(host,"close-entry");
        if(live==null) return "No event owned by you on this channel.";
        live.session.closeEntry();
        return "Entry is closed. The session and existing entrants remain; use !event cancel to clean up.";
    }
    public String cancel(Character host) {
        Live live=authorized(host,"cancel");
        if(live==null) return "No event owned by you on this channel.";
        cancel(live,"Cancelled by host"); return "Event cancelled and cleaned up.";
    }
    public String leave(Character actor) {
        synchronized(actor) { return leaveLocked(actor); }
    }
    private String leaveLocked(Character actor) {
        Live live;
        EventSession.Entrant entrant;
        synchronized(this) {
            ActorKey actorKey=new ActorKey(actor.getWorld(),actor.getId());
            live=attendance.get(actorKey);
            if(live==null) return "You are not registered in a managed event.";
            entrant=live.session.leave(actor.getId()).orElse(null); attendance.remove(actorKey,live);
        }
        if(entrant!=null) {
            if(live.course!=null) live.course.remove(actor.getId());
            if(live.treasure!=null) live.treasure.removeScrolls(actor);
            synchronized(actor) { returnActor(actor,entrant.admission().returnMapId()); }
            if(entrant.admission().bot()) releaseBot(live,actor.getId());
        }
        if(live.session.terminal() && live.session.entrants().isEmpty() && live.rewardExpiry!=null)
            live.rewardExpiry.cancel(false);
        return "You left the event.";
    }
    /** NPC reward gate: identity, alive winner, one claim, inventory-full retry, no bot market stock. */
    public boolean claimReward(Character actor) {
        synchronized(actor) { return claimRewardLocked(actor); }
    }
    private boolean claimRewardLocked(Character actor) {
        Live live;
        synchronized(this) { live=attendance.get(new ActorKey(actor.getWorld(),actor.getId())); }
        if(live==null || !rewardMap(live,actor) || !actor.isAlive() || actor.isGM()
                || actor.getClient().getChannel()!=live.session.channelId()
                || !live.session.beginClaim(actor.getId())) return false;
        boolean delivered=false;
        try {
            delivered=InventoryManipulator.addById(actor.getClient(),live.session.definition().rewardItemId(),(short)1);
            return delivered;
        } finally {
            live.session.completeClaim(actor.getId(),delivered);
            if(delivered) leave(actor);
        }
    }
    public String status(Channel channel) {
        Live live;
        synchronized(this) { live=current.get(key(channel)); }
        if(live==null) return "No current managed event. Capacity profiles remain unmeasured; bot hosting/requests are gated.";
        var s=live.session.snapshot();
        return s.key()+" "+s.id()+" "+s.phase()+"; humans="+s.humans()+", bots="+s.bots()
                +", pending="+s.pending()+", spectators="+s.spectators()+", winners="+s.winners()+", entry="+s.entryOpen()
                +", bot-host="+(live.hostLease==null?0:1)+", development-bot-trial="+live.session.botTrial()
                +"; development limit="+s.capacity().activeLimit()+", profile="+s.capacity().measuredProfileId()
                +(live.game!=null ? "; OX deadline drift last/max="+live.game.lastDriftMs()+"/"+live.game.maxDriftMs()+"ms" : "");
    }
    public void cancelChannel(Channel channel) {
        requests.cancelChannel(new EventRequestCoordinator.ChannelId(channel.getWorld(),channel.getId()));
        Live live;
        synchronized(this) { live=current.get(key(channel)); }
        if(live!=null) cancel(live,"Channel shutdown");
        List<Live> settled;
        synchronized(this) {
            settled=attendance.values().stream().filter(s -> s.channel==channel && s.session.terminal()).distinct().toList();
        }
        for(Live closed:settled) {
            if(closed.rewardExpiry!=null) closed.rewardExpiry.cancel(false);
            for(var entrant:closed.session.entrants()) {
                Character actor=channel.getPlayerStorage().getCharacterById(entrant.admission().actorId());
                if(actor!=null) leave(actor);
                else synchronized(this) {
                    closed.session.leave(entrant.admission().actorId());
                    attendance.remove(new ActorKey(closed.session.worldId(),entrant.admission().actorId()),closed);
                }
            }
        }
    }
    private synchronized Live authorized(Character host,String action) {
        Live live=current.get(key(host.getClient().getChannelServer()));
        if(host.gmLevel()<3 || live==null) return null;
        if(live.session.hostId()==host.getId()) return live;
        if(live.hostLease==null) return null;
        log.info("GM event {} bot-host operator override actor={} channel={} action={}",
                live.session.id(),host.getId(),live.channel.getId(),action);
        return live;
    }
    private synchronized boolean isCurrent(Live live) {
        return current.get(key(live.channel))==live && live.session.running(live.session.generation())
                && live.maps.entrySet().stream().allMatch(e -> live.channel.getMapFactory().getMap(e.getKey())==e.getValue());
    }
    private synchronized boolean isRegistration(Live live) {
        return current.get(key(live.channel))==live && live.session.snapshot().phase()==EventSession.Phase.REGISTRATION;
    }
    private boolean participant(Live live,int actorId) {
        if(!live.session.active(actorId)) return false;
        Character actor=live.channel.getPlayerStorage().getCharacterById(actorId);
        return actor!=null && actor.getWorld()==live.session.worldId()
                && actor.getClient().getChannel()==live.session.channelId()
                && actor.getMap()==competitionMap(live) && !actor.isChangingMaps()
                && actor.isAlive() && !actor.isGM()
                && live.session.entrant(actorId).map(e -> e.admission().team()==actor.getTeam()).orElse(false);
    }
    private void eliminate(Live live,int actorId) {
        if(!isCurrent(live) || !live.session.eliminate(actorId)) return;
        Character actor=live.channel.getPlayerStorage().getCharacterById(actorId);
        if(actor!=null) leave(actor);
    }
    private void finishOx(Live live) {
        if(!isCurrent(live)) return;
        Set<Integer> winners=live.session.entrants().stream().filter(e -> live.session.active(e.admission().actorId()))
                .map(e -> e.admission().actorId()).filter(id -> {
                    Character actor=live.channel.getPlayerStorage().getCharacterById(id);
                    return actor!=null && actor.isAlive() && !actor.isGM() && actor.getMap()==live.map;
                }).collect(Collectors.toSet());
        settleAndReturn(live,winners);
    }
    private void settleAndReturn(Live live,Set<Integer> winners) {
        if(!live.session.settle(winners)) return;
        cleanupMap(live);
        for(var entrant:live.session.entrants()) {
            Character actor=live.channel.getPlayerStorage().getCharacterById(entrant.admission().actorId());
            if(actor==null) continue;
            if(entrant.attendance()==EventSession.Attendance.WINNER && entrant.admission().bot()) {EventBotRuntime.award(actor.getId());leave(actor);}
            else if(entrant.attendance()==EventSession.Attendance.WINNER) synchronized(actor) {
                actor.changeMap(live.treasure==null?MapId.EVENT_WINNER:104000000);
            }
            else leave(actor);
        }
        retire(live);
        // Unclaimed entitlements are bounded in memory and eventually return online actors safely.
        expireRewards(live);
    }
    private void expireRewards(Live live) {
        live.rewardExpiry=TimerManager.getInstance().schedule(() -> {
            for(var entrant:live.session.entrants()) {
                Character actor=live.channel.getPlayerStorage().getCharacterById(entrant.admission().actorId());
                if(actor!=null) leave(actor);
                else synchronized(this) {
                    live.session.leave(entrant.admission().actorId());
                    attendance.remove(new ActorKey(live.session.worldId(),entrant.admission().actorId()),live);
                }
            }
        },30*60_000L);
    }
    private void cancel(Live live,String reason) {
        var cancellation=live.session.cancelOnce();
        if(cancellation.isEmpty()) return;
        List<EventSession.Entrant> removed=cancellation.get();
        cleanupMap(live);
        synchronized(this) {
            for(var e:removed) attendance.remove(new ActorKey(live.session.worldId(),e.admission().actorId()),live);
        }
        for(var e:removed) {
            Character actor=live.channel.getPlayerStorage().getCharacterById(e.admission().actorId());
            if(actor!=null) synchronized(actor) {
                if(live.treasure!=null) live.treasure.removeScrolls(actor);
                returnActor(actor,e.admission().returnMapId());
            }
            if(e.admission().bot()) releaseBot(live,e.admission().actorId());
        }
        retire(live);
        if(!live.session.entrants().isEmpty()) expireRewards(live);
        live.channel.broadcastPacket(PacketCreator.serverNotice(6,"[Event] "+reason+". Existing completed results remain claimable."));
    }
    private void cleanupMap(Live live) {
        if(live.game!=null) live.game.cancel();
        if(live.watchdog!=null) live.watchdog.cancel(false);
        if(live.deadline!=null) live.deadline.cancel(false);
        if(live.coconut!=null) live.coconut.cancel();
        if(live.snowball!=null) {
            live.snowball.cancel();
        }
        if(live.treasure!=null) live.treasure.dispose();
        synchronized(this) {
        if(live.snowball!=null) {
            MapleMap arena=competitionMap(live);
            if(arena.getSnowball(0)==live.snowball.team(0)) arena.setSnowball(0,null);
            if(arena.getSnowball(1)==live.snowball.team(1)) arena.setSnowball(1,null);
        }
        if(live.course!=null || live.coconut!=null || live.snowball!=null || live.treasure!=null) {
            for(var entry:live.maps.entrySet()) if(live.channel.getMapFactory().getMap(entry.getKey())==entry.getValue())
                entry.getValue().setEventStarted(live.oldMapStarted.get(entry.getKey()));
            if(live.map.getPortal("join00")!=null) live.map.getPortal("join00").setPortalStatus(live.oldPortalOpen);
        }
        if(live.game!=null && live.map.getOx()==live.game) {
            live.map.setOx(null); live.map.setOxQuiz(live.oldOx); live.map.setEventStarted(live.oldStarted);
            if(live.map.getPortal("join00")!=null) live.map.getPortal("join00").setPortalStatus(live.oldPortalOpen);
        }
        }
    }
    private void retire(Live live) {
        synchronized(this) {
            current.remove(key(live.channel),live);
            if(live.channel.getEvent()==live.compatibility) live.channel.setEvent(null);
        }
        requests.completed(new EventRequestCoordinator.ChannelId(live.session.worldId(),live.session.channelId()));
        EventMapLeases.release(live.session.id().toString());
        EventInstrumentation.retire(live.session.id().toString());
        if(live.hostLease!=null) EventBotRuntime.release(live.hostLease.botId(),live.hostLease.generation(),true);
    }
    private void supervise(Live live) {
        if(live.session.terminal()) return;
        EventGovernor.Mode mode=live.governor.update(live.telemetry.sample(System.currentTimeMillis()));
        if(mode==EventGovernor.Mode.STOP) { cancel(live,"Sustained event overload"); return; }
        pumpArrivals(live);
        Character host=live.channel.getPlayerStorage().getCharacterById(live.session.hostId());
        if(host==null || host.getClient().getChannel()!=live.session.channelId()
                || live.maps.entrySet().stream().anyMatch(e -> live.channel.getMapFactory().getMap(e.getKey())!=e.getValue())) {
            cancel(live,"Host disconnected or event map replaced"); return;
        }
        for(var entrant:live.session.entrants()) {
            if(entrant.attendance()==EventSession.Attendance.PENDING) continue;
            Character actor=live.channel.getPlayerStorage().getCharacterById(entrant.admission().actorId());
            if(actor==null) {
                synchronized(this) {
                    live.session.leave(entrant.admission().actorId());
                    attendance.remove(new ActorKey(live.session.worldId(),entrant.admission().actorId()),live);
                }
                if(entrant.admission().bot()) releaseBot(live,entrant.admission().actorId());
            } else if(actor.isChangingMaps()) continue;
            else if(!actor.isAlive() || actor.getClient().getChannel()!=live.session.channelId()
                    || (entrant.attendance()==EventSession.Attendance.WINNER ? !rewardMap(live,actor)
                    : live.treasure!=null ? live.maps.get(actor.getMapId())!=actor.getMap()
                    : live.course==null ? actor.getMap()!=(live.session.snapshot().phase()==EventSession.Phase.REGISTRATION || live.session.snapshot().phase()==EventSession.Phase.COUNTDOWN?live.map:competitionMap(live))
                    : live.maps.get(actor.getMapId())!=actor.getMap() || !live.course.atStage(actor.getId(),actor.getMapId()))) leave(actor);
        }
        List<ActorKey> expired;
        synchronized(this) {
            expired=attendance.entrySet().stream().filter(e->e.getValue()==live && live.session.entrant(e.getKey().actor()).isEmpty())
                    .map(Map.Entry::getKey).toList();
        }
        for(ActorKey key:expired) {
            Character actor=live.channel.getPlayerStorage().getCharacterById(key.actor());
            if(actor!=null) synchronized(actor) {
                boolean removed;
                synchronized(this) {removed=attendance.remove(key,live);}
                if(removed && live.maps.get(actor.getMapId())==actor.getMap()) returnActor(actor,actor.peekSavedLocation("EVENT"));
            }
            else synchronized(this) {attendance.remove(key,live);}
            releaseBot(live,key.actor());
        }
    }
    private static ChannelKey key(Channel channel) { return new ChannelKey(channel.getWorld(),channel.getId()); }
    private static void releaseBot(Live live,int actorId) {
        var lease=live.botLeases.remove(actorId);
        if(lease!=null) EventBotRuntime.release(actorId,lease.generation(),true);
    }
    private static boolean isCourse(EventDefinition definition) {
        return definition.key().equals("fitness") || definition.key().equals("ola");
    }
    private static MapleMap competitionMap(Live live) {
        return live.session.definition().key().equals("snowball")?live.maps.get(MapId.EVENT_SNOWBALL):live.map;
    }
    private static boolean rewardMap(Live live,Character actor) {
        int id=live.treasure==null?MapId.EVENT_WINNER:104000000;
        return actor.getMapId()==id && live.channel.getMapFactory().getMap(id)==actor.getMap();
    }
    /** Reconnect retains a current entitlement; restart/stale saved locations return through the saved portal. */
    public void reconcileLogin(Character actor) {
        synchronized(actor) {
            Live live;
            synchronized(this) {live=attendance.get(new ActorKey(actor.getWorld(),actor.getId()));}
            if(live!=null) {
                var entrant=live.session.entrant(actor.getId()).orElse(null);
                if(entrant!=null && entrant.attendance()==EventSession.Attendance.WINNER && rewardMap(live,actor)) return;
                leave(actor); return;
            }
            int saved=actor.peekSavedLocation("EVENT");
            if(saved>=0) returnActor(actor,saved);
            else if(actor.getMapId()>=109010000 && actor.getMapId()<=109090000 && !actor.isGM()) returnActor(actor,MapId.HENESYS);
        }
    }
    private static void returnActor(Character actor,int returnMapId) {
        if(actor.getFitness()!=null) { actor.getFitness().resetTimes(); actor.setFitness(null); }
        if(actor.getOla()!=null) { actor.getOla().resetTimes(); actor.setOla(null); }
        actor.setTeam(0);
        actor.sendPacket(PacketCreator.removeClock());
        try {
            MapleMap destination=actor.getWarpMap(returnMapId);
            if(destination==null || destination.getPortal(0)==null) destination=actor.getWarpMap(MapId.HENESYS);
            int portalId=actor.peekSavedLocation("EVENT")==returnMapId?actor.peekSavedLocationPortal("EVENT"):0;
            Portal portal=destination.getPortal(portalId);
            if(portal==null) portal=destination.getPortal(0);
            actor.changeMap(destination,portal);
            if(actor.getMap()==destination) actor.clearSavedLocation(SavedLocationType.EVENT);
        } catch(RuntimeException failure) { log.warn("Event return failed for actor {} to {}",actor.getId(),returnMapId,failure); }
    }
}
