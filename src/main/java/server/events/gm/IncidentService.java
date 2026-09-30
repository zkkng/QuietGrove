package server.events.gm;

import client.Character;
import client.Client;
import client.inventory.InventoryType;
import net.server.channel.Channel;
import server.TimerManager;
import server.life.*;
import server.maps.*;
import soloMapling.ArtificialPlayer.*;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.CompanionSystem.*;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import tools.PacketCreator;
import java.awt.Point;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit finite public-world encounters. Administrative spawns remain separate unless deliberately adopted. */
public final class IncidentService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(IncidentService.class);
    private static final IncidentService INSTANCE=new IncidentService();
    public static IncidentService getInstance() {return INSTANCE;}
    private record Encounter(String key,Set<Integer> templates,Set<Integer> finals,int root,int parts) {}
    private record Hosted(String leaseOwner,long spawnInterval,java.util.function.Consumer<Boolean> completed) {}
    private static final class Incident {
        final String id=UUID.randomUUID().toString();
        final long generation,started=System.currentTimeMillis(),expires;
        final Channel channel;final MapleMap map;final Encounter encounter;
        final Set<Long> roots=java.util.concurrent.ConcurrentHashMap.newKeySet(),completeRoots=java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Set<Integer> objects=java.util.concurrent.ConcurrentHashMap.newKeySet(),actors=java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Map<Integer,Long> travelProgressAt=new java.util.concurrent.ConcurrentHashMap<>();
        final Map<Integer,Point> travelPositions=new java.util.concurrent.ConcurrentHashMap<>();
        final Map<Integer,Long> departedAt=new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicLongArray arrivalBuckets=new java.util.concurrent.atomic.AtomicLongArray(5);
        final java.util.concurrent.atomic.AtomicLong casualties=new java.util.concurrent.atomic.AtomicLong(),retreats=new java.util.concurrent.atomic.AtomicLong(),bystanderDeaths=new java.util.concurrent.atomic.AtomicLong();
        final Map<Integer,CompanionTaskService.EventLease> leases=new java.util.concurrent.ConcurrentHashMap<>();
        final IncidentAwareness awareness;
        final EventCapacityProfiles.Profile profile;
        final int spawnBudget;
        final int trialActiveLimit;
        final Hosted hosted;
        final long spawnInterval;
        final Map<Monster,Boolean> accepted=new IdentityHashMap<>();
        int remainingInitial;
        long nextSpawnAt,nextDeparture;
        final Point spawnPosition;
        final EventGovernor governor=new EventGovernor(); final EventTelemetry telemetry=new EventTelemetry();
        volatile int spawned;volatile long controllerGeneration;volatile boolean closed,prepared;
        Incident(long generation,Channel channel,MapleMap map,Encounter encounter,int count,
                 Collection<IncidentAwareness.Candidate> candidates,EventCapacityProfiles.Profile profile,int trialActiveLimit,
                 Hosted hosted,Point spawnPosition,long spawnInterval) {
            this.generation=generation;this.channel=channel;this.map=map;this.encounter=encounter;this.profile=profile;
            this.trialActiveLimit=trialActiveLimit;
            this.hosted=hosted;this.remainingInitial=count;this.spawnPosition=new Point(spawnPosition);
            this.spawnInterval=spawnInterval;
            expires=started+2*60*60_000L;
            spawnBudget=Math.addExact(Math.multiplyExact(count,encounter.parts()),Integer.getInteger("gm.events.incidentDescendantBudget",128));
            awareness=new IncidentAwareness(generation,candidates);
        }
        String mapOwner() {return hosted==null?id:hosted.leaseOwner();}
    }
    private final AtomicLong generations=new AtomicLong();
    private final Map<String,Incident> incidents=new HashMap<>();
    private final Map<Long,Incident> roots=new HashMap<>();
    private final Map<Integer,Long> ambientDead=new HashMap<>();
    private static final ThreadLocal<Incident> SPAWNING=new ThreadLocal<>();
    private ScheduledFuture<?> pump;
    private IncidentService() {}

    private static Encounter resolve(String key) {
        if(key.equals("snail")) return new Encounter(key,Set.of(100100),Set.of(100100),100100,1);
        if(key.equals("crimson-balrog")) return new Encounter(key,Set.of(8150000),Set.of(8150000),8150000,1);
        var definition=BossRegistry.get(key);
        if(key.equals("zakum")) return new Encounter(key,definition.phases(),definition.finals(),8800000,9);
        if(key.equals("horntail")) return new Encounter(key,definition.phases(),definition.finals(),8810026,10);
        if(definition.type()!=BossDefinition.Type.FIELD && definition.type()!=BossDefinition.Type.AREA_SEARCH)
            throw new IllegalArgumentException("This encounter requires its normal instance/transport adapter.");
        return new Encounter(key,definition.phases(),definition.finals(),definition.roots().iterator().next(),1);
    }
    public static boolean approvedEncounter(String key) {try {resolve(key);return true;} catch(RuntimeException invalid) {return false;}}
    public String create(Character host,String key,int count,boolean announced) {
        return create(host,key,count,announced,0);
    }
    /** Explicit finite GM development exercise; never constitutes measured approval. */
    public String create(Character host,String key,int count,boolean announced,int trialActiveLimit) {
        if(host.gmLevel()<3 || host.getMap()==null) return "Incident control requires GM rank 3.";
        return prepare(host,key,count,announced,trialActiveLimit,null,host.getPosition());
    }
    String createHosted(Character host,String owner,String key,int count,int activeLimit,Point position,
                        java.util.function.Consumer<Boolean> completed) {
        var lease=CompanionTaskService.shared().eventLease(host.getId()).orElse(null);
        if(lease==null || !lease.committed() || lease.role()!=CompanionTaskService.EventRole.HOST
                || !lease.eventId().equals(owner) || !WaveInvasionService.getInstance().ownsHost(owner,host)
                || !EventMapLeases.owned(host.getMap(),owner)) return "Hosted incident authority is stale.";
        return prepare(host,key,count,true,activeLimit,new Hosted(owner,key.equals("snail")?2000:15_000,completed),position);
    }
    private String prepare(Character host,String key,int count,boolean announced,int trialActiveLimit,Hosted hosted,Point origin) {
        if(trialActiveLimit<0) return "Trial active limit must be positive.";
        Encounter encounter;
        try {encounter=resolve(key.toLowerCase(Locale.ROOT));} catch(Exception invalid) {return "Unknown/unapproved incident encounter.";}
        if(count<1 || count>Integer.getInteger("gm.events.developmentMonsterBudget",256)) return "Count exceeds the configured development monster budget (not measured capacity).";
        Client hostClient=host.getClient();
        if(hostClient==null || hostClient.getChannelServer()==null) return "Incident host is disconnected.";
        MapleMap map=host.getMap();Channel channel=hostClient.getChannelServer();
        if(map.getEventInstance()!=null || channel.getMapFactory().getMap(map.getId())!=map) return "Incidents require a public map instance.";
        if(encounter.parts()>1 && count!=1) return "One full multipart encounter per leased map is allowed.";
        if(encounter.parts()>1 && map.getAllMonsters().stream().anyMatch(m->encounter.templates().contains(m.getId()))) return "An existing multipart encounter occupies this map.";
        Point position=map.getPointBelow(origin);
        if(position==null || map.getPortal(0)==null || !map.getMapArea().contains(position)) return "No valid grounded incident placement here.";
        if(encounter.key().equals("zakum") && map.getId()!=280030000 || encounter.key().equals("horntail") && map.getId()!=240060200)
            return "Full boss placement requires its reviewed arena; a town placement profile has not been verified.";
        Monster template=LifeFactory.getMonster(encounter.root());
        if(template==null || template.getMaxHp()<1 || template.getStats().isFriendly()) return "Incompatible/missing monster template.";
        if(count>(template.getStats().isBoss()?3:30)) return "Managed incidents allow at most3 bosses or30 ordinary mobs.";
        List<IncidentAwareness.Candidate> candidates=new ArrayList<>();
        for(BotSM bot:CharacterStorage.getAllBots().values()) {
            Character actor=bot.getChr();
            if(EventBotRuntime.prior(bot)==null || !sameChannel(actor,channel)) continue;
            List<Integer> contacts=actor.getBuddylist()==null?List.of():Arrays.stream(actor.getBuddylist().getBuddyIds()).boxed().toList();
            candidates.add(new IncidentAwareness.Candidate(actor.getId(),actor.getMapId(),contacts,
                    Math.floorMod(actor.getId()*31,1000)/1000.0,2000+Math.floorMod(actor.getId()*17,18000)));
        }
        String profileKey="invasion:"+encounter.key()+":"+map.getId();
        var profile=EventCapacityProfiles.forEvent(profileKey);
        var definition=new EventDefinition(profileKey,1,map.getId(),List.of(map.getId()),1,2*60*60_000L,0,false,"");
        if(profile!=null && !profile.verified(definition)) profile=null;
        Incident incident=new Incident(generations.incrementAndGet(),channel,map,encounter,count,candidates,profile,trialActiveLimit,
                hosted,position,hosted==null?(template.getStats().isBoss()?15_000:2_000):hosted.spawnInterval());
        synchronized(this) {
            if(hosted==null?!EventMapLeases.acquire(incident.id,List.of(map)):!EventMapLeases.owned(map,hosted.leaseOwner()))
                return "The map is already leased by an event or incident.";
            incidents.put(incident.id,incident);
            EventInstrumentation.register(incident.id,List.of(map),incident.telemetry);
            if(pump==null) pump=TimerManager.getInstance().register(this::pump,250,250);
        }
        try {
            SPAWNING.set(incident);
            if(encounter.key().equals("zakum")) {
                map.spawnZakumOnGroundBelow(position);
            } else if(encounter.key().equals("horntail")) map.spawnHorntailOnGroundBelow(position);
            else map.spawnMonsterOnGroundBelow(LifeFactory.getMonster(encounter.root()),position);
            incident.remainingInitial--;
            incident.nextSpawnAt=System.currentTimeMillis()+incident.spawnInterval;
        } catch(RuntimeException failure) {end(incident,false,"Incident preparation failed");return "Incident preparation failed: "+failure.getMessage();}
        finally {SPAWNING.remove();}
        if(incident.roots.isEmpty()) {end(incident,false,"No encounter could be placed");return "No encounter could be placed.";}
        log.info("Incident {} initial root key={} spawned={} scheduled={} map={}",incident.id,encounter.key(),incident.spawned,count,map.getId());
        Set<Integer> audience=candidates.stream().map(IncidentAwareness.Candidate::id).collect(java.util.stream.Collectors.toSet());
        incident.controllerGeneration=BossMonsterController.registerIncident(incident.id,map,Set.copyOf(incident.roots),audience,incident.expires);
        if(announced) {
            incident.awareness.announce(audience,map.getId(),System.currentTimeMillis());
            channel.broadcastPacket(PacketCreator.serverNotice(6,"[Invasion] "+key+" at map "+map.getId()+", channel "+channel.getId()+". This is a public fight; normal PvE death, EXP and drops apply."));
        }
        logSignal(host,soloMapling.server.EventMessageSystem.EventType.INCIDENT_STARTED,incident,0,announced?audience:Set.of());
        incident.prepared=true;
        return "Created "+(announced?"announced":"silent")+" incident "+incident.id+"; finite candidate snapshot="+candidates.size()
                +", first encounters="+incident.roots.size()+", scheduled total="+count+", descendant budget="+(incident.spawnBudget-incident.spawned)
                +(trialActiveLimit>0?"; UNMEASURED GM response trial, active limit="+trialActiveLimit:
                    profile==null?". Bot response awaits a measured compatible incident profile.":"; measured profile="+profile.id());
    }
    /** Called before every canonical spawn, including WZ summons/revives. */
    public synchronized boolean allowSpawn(MapleMap map,Monster monster) {
        long root=monster.getEncounterId();
        if(monster.getIncidentOwner()!=null && !incidents.containsKey(monster.getIncidentOwner())) return false;
        Incident incident=roots.get(root);
        if(incident==null) incident=SPAWNING.get();
        if(incident==null) return true;
        if(incident.closed || incident.map!=map || incident.spawned>=incident.spawnBudget || !EventMapLeases.owned(map,incident.mapOwner())) return false;
        boolean boss=monster.getStats().isBoss();
        if(boss) {
            // Only reviewed multipart pieces share a slot. A boss summon inheriting a field
            // root remains another living boss; encounter inheritance cannot evade the cap.
            Set<Object> living=new HashSet<>();
            for(var accepted:incident.accepted.entrySet()) if(accepted.getValue() && accepted.getKey().isAlive())
                living.add(bossSlot(incident,accepted.getKey()));
            if(!living.contains(bossSlot(incident,monster)) && living.size()>=3) return false;
        } else if(incident.accepted.entrySet().stream().filter(e->!e.getValue() && e.getKey().isAlive()).count()>=30) return false;
        incident.accepted.put(monster,boss);
        monster.setIncidentOwner(incident.id);
        incident.roots.add(root);roots.put(root,incident);incident.spawned++;
        return true;
    }
    private static Object bossSlot(Incident incident,Monster monster) {
        return incident.encounter.parts()>1 && incident.encounter.templates().contains(monster.getId())
                ? Long.valueOf(monster.getEncounterId()):monster;
    }
    public void monsterSpawned(Monster monster) {
        boolean stale;
        synchronized(this) {
            Incident incident=roots.get(monster.getEncounterId());
            stale=monster.getIncidentOwner()!=null && (incident==null || incident.closed);
            if(incident!=null && !incident.closed) incident.objects.add(monster.getObjectId());
        }
        // Cancellation may race the accepted spawn. Remove the late object after canonical counters exist.
        if(stale && monster.getMap()!=null) monster.getMap().killMonster(monster,null,false,1,(short)0);
    }
    public synchronized void monsterRemoved(Monster monster,boolean legitimate) {
        Incident incident=roots.get(monster.getEncounterId());
        if(incident==null || incident.closed) return;
        incident.objects.remove(monster.getObjectId());
        incident.accepted.remove(monster);
        if(legitimate && incident.encounter.finals().contains(monster.getId())) {
            incident.completeRoots.add(monster.getEncounterId());
            log.info("Incident {} actual final death root={} mob={}",incident.id,monster.getEncounterId(),monster.getId());
        }
    }
    public static boolean exposed(Character actor) {return exposureGeneration(actor)>0;}
    private static boolean sameChannel(Character actor,Channel channel) {
        if(actor==null) return false;
        Client client=actor.getClient();
        return client!=null && actor.getWorld()==channel.getWorld() && client.getChannel()==channel.getId();
    }
    public static long exposureGeneration(Character actor) {
        if(actor==null || !BotHelpers.isBot(actor) || actor.getMap()==null) return 0;
        var lease=CompanionTaskService.shared().eventLease(actor.getId()).orElse(null);
        synchronized(INSTANCE) {
            return INSTANCE.incidents.values().stream().filter(i->!i.closed && i.map==actor.getMap()
                    && i.expires>System.currentTimeMillis())
                    // The exact show host is event staff. Participants and all other bystanders remain exposed.
                    .filter(i->i.hosted==null || lease==null || !lease.committed()
                            || lease.role()!=CompanionTaskService.EventRole.HOST
                            || !lease.eventId().equals(i.mapOwner())
                            || !WaveInvasionService.getInstance().ownsHost(i.mapOwner(),actor))
                    .mapToLong(i->i.generation).findFirst().orElse(0);
        }
    }
    public synchronized boolean owns(String id) {return incidents.containsKey(id);}
    private void pump() {
        List<Incident> active;synchronized(this){active=List.copyOf(incidents.values());}
        for(Incident incident:active) try {synchronized(incident) {tick(incident);}} catch(RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(IncidentService.class).error("Incident {} tick failed",incident.id,failure);
            end(incident,false,"Incident failed");
        }
    }
    private void tick(Incident incident) {
        long now=System.currentTimeMillis();
        if(incident.closed || !incident.prepared) return;
        if(incident.channel.getMapFactory().getMap(incident.map.getId())!=incident.map || now>=incident.expires) {end(incident,false,"Incident expired/map replaced");return;}
        if(incident.remainingInitial==0 && !incident.roots.isEmpty() && incident.completeRoots.containsAll(incident.roots)) {end(incident,true,"The actual encounter was defeated");return;}
        EventGovernor.Mode mode=incident.governor.update(incident.telemetry.sample(now));
        if(mode==EventGovernor.Mode.STOP) {end(incident,false,"Sustained incident overload");return;}
        if(incident.remainingInitial>0 && now>=incident.nextSpawnAt && incident.governor.arrivals()) {
            int before=incident.spawned;
            try {
                SPAWNING.set(incident);
                incident.map.spawnMonsterOnGroundBelow(LifeFactory.getMonster(incident.encounter.root()),incident.spawnPosition);
            } finally {SPAWNING.remove();}
            if(incident.spawned>before) {
                for(long root:incident.roots) BossMonsterController.addIncidentRoot(incident.id,incident.controllerGeneration,root);
                incident.remainingInitial--;
                log.info("Incident {} scheduled root arrived key={} spawned={} remaining={}",incident.id,incident.encounter.key(),incident.spawned,incident.remainingInitial);
            }
            incident.nextSpawnAt=now+incident.spawnInterval;
        }
        // Actual local presence discovers silent incidents. No remote population seed occurs here.
        List<Monster> threats=incident.map.getAllMonsters().stream().filter(m->incident.roots.contains(m.getEncounterId()) && m.isAlive()).toList();
        var threatIndex=new EventSpatialIndex<>(threats,Monster::getPosition,650);
        Map<MapleMap,EventSpatialIndex<Character>> localReports=new IdentityHashMap<>();
        for(Character witness:incident.map.getCharacters()) if(BotHelpers.isBot(witness) && witness.isAlive()
                && !threatIndex.nearby(witness.getPosition(),650).isEmpty()
                && incident.awareness.witness(witness.getId(),incident.map.getId(),now))
            logSignal(witness,soloMapling.server.EventMessageSystem.EventType.INCIDENT_WITNESSED,incident,witness.getId(),Set.of(witness.getId()));
        int activeLimit=incident.trialActiveLimit>0?incident.trialActiveLimit:incident.profile==null?0:incident.profile.capacity().activeLimit();
        int worldLimit=incident.trialActiveLimit>0?incident.trialActiveLimit+(incident.hosted==null?0:1):incident.profile==null?incident.awareness.population():incident.profile.worldLimit();
        int departures=0,budget=incident.trialActiveLimit>0?2:incident.profile==null?0:Math.min(incident.profile.arrivalBurst(),incident.profile.promotionBudget());
        for(var response:incident.awareness.pump(now,incident.generation)) {
            BotSM bot=CharacterStorage.getBotById(response.id());
            if(bot==null) {incident.awareness.retire(response.id(),now);continue;}
            Character actor=bot.getChr();
            if(actor==null || actor.getMap()==null || !sameChannel(actor,incident.channel)) {
                incident.awareness.retire(response.id(),now);continue;
            }
            if(response.evidence()!=null && response.evidence().sourceId()!=response.id())
                logSignal(actor,soloMapling.server.EventMessageSystem.EventType.INCIDENT_REPORTED,incident,response.id(),Set.of(response.id()));
            var nearbyIndex=localReports.computeIfAbsent(actor.getMap(),field->new EventSpatialIndex<>(
                    field.getCharacters().stream().filter(BotHelpers::isBot).toList(),Character::getPosition,600));
            List<Integer> nearby=nearbyIndex.nearby(actor.getPosition(),600).stream().map(Character::getId).toList();
            incident.awareness.report(actor.getId(),nearby,now);
            boolean available=EventBotRuntime.eligible(bot,incident.channel.getWorld(),incident.channel.getId());
            Monster threat=threats.stream().filter(m->!m.isFake()).findFirst().orElse(null);
            double hit=threat==null?0:CompanionCombat.hitChance(actor,threat,actor.getJob().getJobNiche()==2);
            double survival=survival(actor,threat);
            boolean supplies=hasSupplies(actor);
            var choice=incident.awareness.decide(actor.getId(),available && supplies,IncidentNavigation.reachable(actor,incident.map.getId()),hit,survival,now);
            if(choice==IncidentAwareness.State.FLEE && available && actor.getMap()==incident.map) {
                // Vulnerability is independent of this lease; this lease authorizes only a physical escape.
                var lease=EventBotRuntime.reserve(bot,incident.id,CompanionTaskService.EventRole.SPECTATOR,incident.channel.getWorld(),incident.channel.getId(),
                        worldLimit).orElse(null);
                if(lease!=null) {
                    var committed=CompanionTaskService.shared().commitEvent(lease).orElse(null);
                    if(committed!=null) {
                        incident.actors.add(actor.getId());incident.leases.put(actor.getId(),committed);
                        if(!EventBotRuntime.activateIncident(bot,committed)) releaseBot(incident,actor.getId(),0);
                    }
                }
            }
        }
        if(activeLimit==0 || !incident.governor.arrivals() || now<incident.nextDeparture) return;
        budget=Math.min(budget,1);
        for(var response:incident.awareness.responses()) {
            if(departures>=budget) break;
            if(response.state()!=IncidentAwareness.State.PREPARING || response.due()>now) continue;
            BotSM previous=CharacterStorage.getBotById(response.id());
            if(previous==null || !EventBotRuntime.eligible(previous,incident.channel.getWorld(),incident.channel.getId())) {incident.awareness.retire(response.id(),now);continue;}
            if(incident.leases.values().stream().filter(l->l.role()==CompanionTaskService.EventRole.PARTICIPANT).count()>=activeLimit) continue;
            var lease=EventBotRuntime.reserve(previous,incident.id,CompanionTaskService.EventRole.PARTICIPANT,incident.channel.getWorld(),incident.channel.getId(),worldLimit).orElse(null);
            if(lease==null) continue;
            var committed=CompanionTaskService.shared().commitEvent(lease).orElse(null);
            if(committed==null || !incident.awareness.depart(response.id(),now)) {CompanionTaskService.shared().releaseEvent(response.id(),lease.generation());continue;}
            incident.actors.add(response.id());
            incident.leases.put(response.id(),committed);
            incident.departedAt.put(response.id(),now);
            if(!EventBotRuntime.activateIncident(previous,committed)) releaseBot(incident,response.id(),0);
            else log.info("Incident {} responder departed actor={} active={}",incident.id,response.id(),incident.actors.size());
            departures++;
            incident.nextDeparture=now+2000;
        }
    }
    public void botTick(Character actor,CompanionTaskService.EventLease lease,CompanionCombat combat) {
        Incident incident;synchronized(this){incident=incidents.get(lease.eventId());}
        if(incident==null || incident.closed || !lease.equals(CompanionTaskService.shared().eventLease(actor.getId()).orElse(null))
                || !sameChannel(actor,incident.channel)) {EventBotRuntime.release(actor.getId(),lease.generation(),true);return;}
        long now=System.currentTimeMillis();var response=incident.awareness.response(actor.getId());
        if(!actor.isAlive()) {
            GCMovement.stop(actor);
            if(response!=null && response.state()!=IncidentAwareness.State.DEAD && response.state()!=IncidentAwareness.State.RECOVERING) {
                incident.awareness.casualty(actor.getId(),now);
                incident.casualties.incrementAndGet();
                log.info("Incident {} responder death actor={} map={} hp={} deaths={}",incident.id,actor.getId(),actor.getMapId(),actor.getHp(),response.deaths()+1);
            }
            response=incident.awareness.response(actor.getId());
            if(response==null) {releaseBot(incident,actor.getId(),0);return;}
            if(now>=response.due()) {
                if(!returnDeadResponder(actor)) return;
                incident.awareness.recovered(actor.getId(),now);incident.actors.remove(actor.getId());
                log.info("Incident {} responder recovered actor={} map={} deaths={}",incident.id,actor.getId(),actor.getMapId(),response.deaths());
                releaseBot(incident,actor.getId(),60_000L*response.deaths());
            }
            return;
        }
        if(actor.getHp()<actor.getCurrentMaxHp()*.2 || response==null || now>=incident.expires) {
            retreat(actor,incident,now,combat);return;
        }
        if(response.state()==IncidentAwareness.State.FLEE || response.state()==IncidentAwareness.State.RETREATED
                || lease.role()==CompanionTaskService.EventRole.SPECTATOR) {retreat(actor,incident,now,combat);return;}
        if(actor.getMap()!=incident.map) {
            combat.travelSupplies(actor,lease.generation());
            Point previous=incident.travelPositions.get(actor.getId());
            if(previous==null || previous.distanceSq(actor.getPosition())>16*16) {
                incident.travelPositions.put(actor.getId(),new Point(actor.getPosition()));incident.travelProgressAt.put(actor.getId(),now);
            }
            if(now-incident.travelProgressAt.getOrDefault(actor.getId(),now)>60_000) {retreat(actor,incident,now,combat);return;}
            IncidentNavigation.step(actor,incident.map);return;
        }
        if(incident.awareness.arrive(actor.getId(),now)) {
            Long departed=incident.departedAt.remove(actor.getId());
            long travelMs=departed==null?-1:Math.max(0,now-departed);
            if(travelMs>=0) incident.arrivalBuckets.incrementAndGet(travelBucket(travelMs));
            long awarenessMs=response.evidence()==null?-1:Math.max(0,now-response.evidence().learnedAt());
            log.info("Incident {} responder arrived actor={} map={} travelMs={} awarenessToArrivalMs={} hp={} mp={}",
                    incident.id,actor.getId(),actor.getMapId(),travelMs,awarenessMs,actor.getHp(),actor.getMp());
        }
        combat.tickIncident(actor);
    }
    static boolean returnDeadResponder(Character actor) {
        MapleMap origin=actor.getMap();
        MapleMap town=origin==null?null:origin.getReturnMap();
        if(town==null || town.getPortal(0)==null) return false;
        try {actor.changeMap(town,town.getPortal(0));}
        catch(RuntimeException failedWarp) {
            log.warn("Incident responder recovery warp failed actor={}",actor.getId(),failedWarp);
            return false;
        }
        if(actor.getMap()!=town) return false;
        actor.updateHp(Math.max(1,actor.getCurrentMaxHp()*3/10));
        return true;
    }
    private void retreat(Character actor,Incident incident,long now,CompanionCombat combat) {
        var lease=CompanionTaskService.shared().eventLease(actor.getId()).orElse(null);
        if(lease!=null) combat.travelSupplies(actor,lease.generation());
        var response=incident.awareness.response(actor.getId());
        if(response!=null && response.state()!=IncidentAwareness.State.RETREATED) {
            incident.retreats.incrementAndGet();
            log.info("Incident {} responder retreat actor={} map={} hp={} mp={} deaths={}",
                    incident.id,actor.getId(),actor.getMapId(),actor.getHp(),actor.getMp(),response.deaths());
        }
        incident.awareness.retire(actor.getId(),now);
        if(actor.getMap()!=incident.map) {GCMovement.stop(actor);releaseBot(incident,actor.getId(),0);return;}
        Portal exit=actor.getMap().getPortals().stream().filter(p->p.getPortalStatus() && p.getTargetMapId()!=actor.getMapId()
                && p.getTargetMapId()<900000000 && (p.getScriptName()==null || p.getScriptName().isBlank())).findFirst().orElse(null);
        if(exit!=null) {
            if(Math.abs(actor.getPosition().x-exit.getPosition().x)>35 || Math.abs(actor.getPosition().y-exit.getPosition().y)>100)
                {if(!GCMovement.isMoving(actor)) GCMovement.move(actor,exit.getPosition().x,exit.getPosition().y);}
            else if(IncidentNavigation.enter(actor,exit)) releaseBot(incident,actor.getId(),0);
        }
    }
    /** Stops a dead actor's ordinary role callbacks without taking its existing lease/trade/party. */
    public static boolean handleAmbientDeath(BotSM bot) {
        Character actor=bot.getChr();
        if(actor.isAlive() || bot instanceof IncidentBot || bot instanceof EventBot || bot instanceof CompanionBot) return false;
        synchronized(INSTANCE) {if(!INSTANCE.ambientDead.containsKey(actor.getId()) && !exposed(actor)) return false;}
        long now=System.currentTimeMillis(),reviveAt;
        synchronized(INSTANCE) {
            Long existing=INSTANCE.ambientDead.get(actor.getId());
            if(existing==null) {
                reviveAt=now+6000;INSTANCE.ambientDead.put(actor.getId(),reviveAt);
                for(Incident incident:INSTANCE.incidents.values()) if(!incident.closed && incident.map==actor.getMap()) {
                    incident.bystanderDeaths.incrementAndGet();
                    log.info("Incident {} bystander death actor={} map={} hp={}",incident.id,actor.getId(),actor.getMapId(),actor.getHp());
                    break;
                }
            } else reviveAt=existing;
        }
        GCMovement.stop(actor);
        if(now<reviveAt) return true;
        if(actor.getTrade()!=null) server.Trade.cancelTrade(actor,server.Trade.TradeResult.PARTNER_CANCEL);
        MapleMap town=actor.getMap()==null?null:actor.getMap().getReturnMap();
        if(town==null || town.getPortal(0)==null) return true;
        try {actor.changeMap(town,town.getPortal(0));}
        catch(RuntimeException failedWarp) {
            log.warn("Incident bystander recovery warp failed actor={}",actor.getId(),failedWarp);
            return true;
        }
        if(actor.getMap()!=town) return true;
        actor.updateHp(Math.max(1,actor.getCurrentMaxHp()*3/10));
        bot.waitFor(60_000);
        log.info("Incident bystander recovered actor={} map={} hp={}",actor.getId(),actor.getMapId(),actor.getHp());
        synchronized(INSTANCE) {INSTANCE.ambientDead.remove(actor.getId());}
        return true;
    }
    public String cancel(Character host,String id) {
        Incident incident;synchronized(this){incident=incidents.get(id);}
        if(host.gmLevel()<3 || incident==null || host.getWorld()!=incident.channel.getWorld()) return "Unknown incident or insufficient authority.";
        end(incident,false,"Cancelled by GM");return "Incident cancelled; only owned monsters/tasks were removed.";
    }
    void cancelHosted(String owner) {
        List<Incident> active;synchronized(this){active=incidents.values().stream()
                .filter(i->i.hosted!=null && i.hosted.leaseOwner().equals(owner)).toList();}
        active.forEach(i->end(i,false,"Hosted event stopped"));
    }
    private void end(Incident incident,boolean victory,String reason) {
        synchronized(incident) {endLocked(incident,victory,reason);}
    }
    private void endLocked(Incident incident,boolean victory,String reason) {
        synchronized(this) {
            if(incident.closed) return;incident.closed=true;incidents.remove(incident.id);incident.awareness.cancel();
            for(long root:incident.roots) roots.remove(root,incident);
        }
        BossMonsterController.releaseIncident(incident.id,incident.controllerGeneration);
        org.slf4j.LoggerFactory.getLogger(IncidentService.class).info("Incident {} ended: {}; victory={}; telemetry={}",
                incident.id,reason,victory,incident.telemetry.report());
        log.info("Incident {} responder summary arrivals<5s/<15s/<30s/<60s/>=60s={} responderDeaths={} bystanderDeaths={} retreats={}",
                incident.id,arrivalSummary(incident.arrivalBuckets),incident.casualties.get(),incident.bystanderDeaths.get(),incident.retreats.get());
        for(Monster monster:incident.map.getAllMonsters()) if(incident.roots.contains(monster.getEncounterId())) incident.map.killMonster(monster,null,false,1,(short)0);
        for(int actor:List.copyOf(incident.leases.keySet())) releaseBot(incident,actor,0);
        if(incident.hosted==null) EventMapLeases.release(incident.id);
        EventInstrumentation.retire(incident.id);
        logSignal(null,victory?soloMapling.server.EventMessageSystem.EventType.INCIDENT_RESOLVED:
                soloMapling.server.EventMessageSystem.EventType.INCIDENT_CANCELLED,incident,0,
                incident.awareness.responses().stream().filter(r->r.evidence()!=null).map(IncidentAwareness.Response::id).collect(java.util.stream.Collectors.toSet()));
        incident.channel.broadcastPacket(PacketCreator.serverNotice(6,"[Invasion] "+reason+(victory?".":"; no victory reward.")));
        if(incident.hosted!=null) soloMapling.server.BotTickService.runEventLifecycle(()->incident.hosted.completed().accept(victory));
    }
    public void cancelChannel(Channel channel) {
        List<Incident> active;synchronized(this){active=incidents.values().stream().filter(i->i.channel==channel).toList();}
        active.forEach(i->end(i,false,"Channel shutdown"));
    }
    public synchronized List<String> status(Channel channel) {
        return incidents.values().stream().filter(i->i.channel==channel).map(i->i.id+" "+i.encounter.key()+" map="+i.map.getId()
                +"; finite population="+i.awareness.population()+", committed="+i.actors.size()+", spawned="+i.spawned+"/"+i.spawnBudget
                +", scheduled remaining="+i.remainingInitial+", living boss slots="+i.accepted.entrySet().stream()
                    .filter(e->e.getValue() && e.getKey().isAlive()).map(e->bossSlot(i,e.getKey())).distinct().count()
                +"/3, living ordinary="+i.accepted.entrySet().stream().filter(e->!e.getValue() && e.getKey().isAlive()).count()+"/30"
                +", actual final encounters="+i.completeRoots.size()+"/"+i.roots.size()
                +", travel buckets <5/<15/<30/<60/>=60s="+arrivalSummary(i.arrivalBuckets)
                +", responder deaths="+i.casualties.get()+", bystander deaths="+i.bystanderDeaths.get()+", retreats="+i.retreats.get()).toList();
    }
    static int travelBucket(long milliseconds) {
        if(milliseconds<0) throw new IllegalArgumentException("Negative travel duration");
        return milliseconds<5000?0:milliseconds<15_000?1:milliseconds<30_000?2:milliseconds<60_000?3:4;
    }
    private static String arrivalSummary(java.util.concurrent.atomic.AtomicLongArray buckets) {
        return "["+buckets.get(0)+","+buckets.get(1)+","+buckets.get(2)+","+buckets.get(3)+","+buckets.get(4)+"]";
    }
    private static void releaseBot(Incident incident,int actorId,long recoveryMs) {
        var lease=incident.leases.remove(actorId);incident.actors.remove(actorId);
        incident.departedAt.remove(actorId);
        incident.travelPositions.remove(actorId);incident.travelProgressAt.remove(actorId);
        if(lease!=null) EventBotRuntime.release(actorId,lease.generation(),true,recoveryMs);
    }
    private static void logSignal(Character source,soloMapling.server.EventMessageSystem.EventType type,Incident incident,int target,Set<Integer> audience) {
        var response=target==0?null:incident.awareness.response(target);
        var evidence=response==null?null:response.evidence();
        var signal=new soloMapling.server.EventMessageSystem.IncidentSignal(incident.id,incident.generation,incident.map.getId(),
                source==null?0:source.getId(),target,evidence==null?System.currentTimeMillis():evidence.learnedAt(),
                evidence==null?0:evidence.confidence(),audience,type.name());
        soloMapling.server.EventMessageSystem.EventBus.getInstance().publish(new soloMapling.server.EventMessageSystem.GameEvent(source,incident.map,type,signal));
    }
    private static boolean hasSupplies(Character actor) {
        int hp=0,mp=0;
        for(var item:actor.getInventory(InventoryType.USE).list()) if(item.getItemId()/10000==200 && item.getQuantity()>0) {
            var effect=server.ItemInformationProvider.getInstance().getItemEffect(item.getItemId());
            if(effect==null) continue;
            if(effect.getHp()>0 || effect.getHpRate()>0) hp+=item.getQuantity();
            if(effect.getMp()>0 || effect.getMpRate()>0) mp+=item.getQuantity();
        }
        return hp>=5 && (actor.getJob().getJobNiche()!=2 || mp>=5);
    }
    private static double survival(Character actor,Monster threat) {
        if(threat==null) return 0;
        int damage=Math.max(threat.getPADamage(),threat.getStats().getMADamage());
        for(int attack=0;attack<9;attack++) {
            var info=MobAttackInfoFactory.getMobAttackInfo(threat,attack);
            if(info!=null && !info.isDeadlyAttack()) damage=Math.max(damage,info.getAttackPower());
        }
        double pool=actor.getHp();int guard=actor.getSkillLevel(constants.skills.Magician.MAGIC_GUARD);
        if(guard>0) {
            int fraction=client.SkillFactory.getSkill(constants.skills.Magician.MAGIC_GUARD).getEffect(guard).getX();
            pool+=Math.min(actor.getMp(),actor.getHp()*fraction/(double)Math.max(1,100-fraction));
        }
        return pool/Math.max(1,damage*1.2);
    }
}
