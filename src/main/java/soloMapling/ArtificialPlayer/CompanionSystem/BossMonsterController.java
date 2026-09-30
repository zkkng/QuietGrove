package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import provider.*;
import provider.wz.WZFiles;
import server.TimerManager;
import server.life.*;
import server.maps.MapleMap;
import tools.PacketCreator;
import java.awt.Point;
import java.util.*;

/** Server authority only while no living visible human can control the observed encounter. */
public final class BossMonsterController {
    private static final class State { long due; int nextAttack, nextSkill; }
    private record SkillAction(MobSkillId id, int action, int delay) {}
    private record Kit(List<Integer> attacks, List<SkillAction> skills, boolean flying) {}
    private record Incident(String id, long generation, Set<Long> roots, Set<Integer> actors, long expires) {}
    private static final Map<Monster,State> states = new WeakHashMap<>();
    private static final Map<Integer,Kit> kits = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<MapleMap,List<Incident>> incidents = new WeakHashMap<>();
    private static long nextIncident;
    private static java.util.concurrent.ScheduledFuture<?> driver;
    private BossMonsterController() {}
    /** One shared driver, including incidents created with party recruitment disabled. */
    public static synchronized void ensureDriver() {
        if (driver != null && !driver.isCancelled() && !driver.isDone()) return;
        driver = TimerManager.getInstance().register(() -> BossRuntime.get().pump(),250,250);
    }
    static synchronized void stopDriver() {
        if (driver != null) driver.cancel(false);
        driver = null;
    }
    /** GM integration seam: only finite explicit encounter roots and actors with matching committed shared event leases. */
    public static synchronized long registerIncident(String eventId, MapleMap map, Set<Long> encounterRoots,
            Set<Integer> actors, long expiresAt) {
        if (eventId == null || eventId.isBlank() || map == null || encounterRoots == null || encounterRoots.isEmpty() || actors == null
                || expiresAt <= System.currentTimeMillis()) throw new IllegalArgumentException("incident capability");
        ensureDriver();
        long generation = ++nextIncident;
        incidents.computeIfAbsent(map, x -> new ArrayList<>()).add(new Incident(eventId,generation,Set.copyOf(encounterRoots),Set.copyOf(actors),expiresAt));
        return generation;
    }
    public static synchronized void releaseIncident(String id, long generation) {
        incidents.values().forEach(list -> list.removeIf(i -> i.id().equals(id) && i.generation() == generation));
        incidents.values().removeIf(List::isEmpty);
    }
    /** Admit a later canonical wave root without replacing controller or actor generations. */
    public static synchronized boolean addIncidentRoot(String id,long generation,long rootId) {
        if(id==null || rootId<=0) return false;
        for(var entry:incidents.entrySet()) {
            List<Incident> list=entry.getValue();
            for(int index=0;index<list.size();index++) {
                Incident incident=list.get(index);
                if(!incident.id().equals(id) || incident.generation()!=generation
                        || incident.expires()<=System.currentTimeMillis()) continue;
                if(incident.roots().contains(rootId)) return true;
                // Caller cannot silently claim a world boss or a root in another map.
                if(entry.getKey().getAllMonsters().stream().noneMatch(m->m.getEncounterId()==rootId
                        && id.equals(m.getIncidentOwner()))) return false;
                Set<Long> roots=new HashSet<>(incident.roots());roots.add(rootId);
                list.set(index,new Incident(id,generation,Set.copyOf(roots),incident.actors(),incident.expires()));
                return true;
            }
        }
        return false;
    }
    public static synchronized boolean incidentActor(Character c) {
        if (c == null || c.getMap() == null) return false;
        var lease = CompanionTaskService.shared().eventLease(c.getId()).orElse(null);
        return lease != null && lease.committed() && lease.role() == CompanionTaskService.EventRole.PARTICIPANT && incidents.getOrDefault(c.getMap(),List.of()).stream()
                .anyMatch(i -> i.expires() > System.currentTimeMillis() && i.actors().contains(c.getId()) && i.id().equals(lease.eventId()));
    }
    public static boolean combatAllowed(Character c, long generation) {
        var lease = c == null ? null : CompanionTaskService.shared().eventLease(c.getId()).orElse(null);
        return lease != null && lease.generation() == generation && c.isAlive() && incidentActor(c);
    }
    public static synchronized boolean targetAllowed(Character c, Monster m) {
        var lease = CompanionTaskService.shared().eventLease(c.getId()).orElse(null);
        return lease != null && incidentActor(c) && m.getMap() == c.getMap() && m.isAlive() && !m.isFake() && !m.isEncounterMarker()
                && incidents.getOrDefault(c.getMap(),List.of()).stream().anyMatch(i -> i.id().equals(lease.eventId())
                        && i.actors().contains(c.getId()) && i.roots().contains(m.getEncounterId()));
    }
    public static synchronized boolean allied(Character source, Character target) {
        var lease = CompanionTaskService.shared().eventLease(source.getId()).orElse(null);
        return lease != null && source.getMap() == target.getMap() && incidentActor(source)
                && incidents.getOrDefault(source.getMap(),List.of()).stream().anyMatch(i -> i.id().equals(lease.eventId())
                        && i.actors().contains(source.getId()) && i.actors().contains(target.getId()));
    }
    private static synchronized boolean incidentMonster(Monster m) {
        return incidents.getOrDefault(m.getMap(),List.of()).stream().anyMatch(i -> i.expires() > System.currentTimeMillis() && i.roots().contains(m.getEncounterId()));
    }
    public static boolean authoritativeMonster(Monster m) { return incidentMonster(m) || BossRuntime.get().controlEligible(m); }
    public static synchronized boolean visibleIncident(Character human, BossDefinition d) {
        return human.getMap().getAllMonsters().stream().anyMatch(m -> m.isAlive() && !m.isFake()
                && d.phases().contains(m.getId()) && incidentMonster(m));
    }
    public static void pump() {
        long started = System.nanoTime();
        try { pumpMaps(); }
        finally { BossTelemetry.sample(BossTelemetry.Stage.CONTROLLER,System.nanoTime()-started); }
    }
    private static void pumpMaps() {
        Set<MapleMap> maps = new HashSet<>(BossRuntime.get().activeMaps());
        synchronized (BossMonsterController.class) {
            long now = System.currentTimeMillis();
            incidents.values().forEach(list -> list.removeIf(i -> now >= i.expires()));
            incidents.values().removeIf(List::isEmpty); maps.addAll(incidents.keySet());
        }
        for (MapleMap map : maps) {
            if (map == null) continue;
            boolean humanController = map.getCharacters().stream().anyMatch(c -> !soloMapling.ArtificialPlayer.BotHelpers.isBot(c)
                    && c.isAlive() && !c.isHidden() && c.isLoggedinWorld());
            for (Monster m : map.getAllMonsters()) if (m.isAlive() && !m.isFake() && !m.isEncounterMarker()
                    && (BossRuntime.get().controlEligible(m) || incidentMonster(m))) {
                synchronized (m) {
                    if (humanController) {
                        if (m.getController() != null && needsServerController(m)) m.aggroRemoveController();
                        m.aggroUpdateController(); continue;
                    }
                    if (m.getController() != null) m.aggroRemoveController();
                    Character target = map.getCharacters().stream().filter(c -> c.isAlive() && (CompanionRuntime.active(c) || incidentActor(c)))
                            .min(Comparator.comparingDouble(c -> c.getPosition().distanceSq(m.getPosition()))).orElse(null);
                    if (target != null) tick(m,target);
                }
            }
        }
    }
    static boolean needsServerController(Monster m) {
        Character controller = m.getController();
        return controller == null || !controller.isAlive() || controller.isHidden() || controller.getMap() != m.getMap()
                || !controller.isLoggedinWorld() || soloMapling.ArtificialPlayer.BotHelpers.isBot(controller);
    }
    private static void tick(Monster m, Character target) {
        State state;
        synchronized (states) { state = states.computeIfAbsent(m,x -> new State()); }
        long now = System.currentTimeMillis();
        if (now < state.due || !m.isAlive()) return;
        Kit kit = kits.computeIfAbsent(m.getId(),BossMonsterController::load);
        boolean left = target.getPosition().x < m.getPosition().x;
        for (int n = 0; n < kit.skills().size(); n++) {
            SkillAction action = kit.skills().get(state.nextSkill++ % kit.skills().size());
            MobSkill skill = MobSkillFactory.getMobSkillOrThrow(action.id().type(),action.id().level());
            if (skill.getHP() < (int)(m.getHp()*100.0/Math.max(1,m.getMaxHp())) || !m.canUseSkill(skill,true)) continue;
            emitAction(m,21 + action.action()-1,left,action.id().type().getId(),action.id().level());
            var map = m.getMap();
            var fence = CompanionMonsterAttacks.actorFence(m);
            TimerManager.getInstance().schedule(() -> {
                if (!m.isAlive() || m.getMap() != map || map.getMonsterByOid(m.getObjectId()) != m) return;
                List<Character> banished = new ArrayList<>();
                skill.applyEffectFiltered(target,m,true,banished,fence);
                for (Character c : banished) if (c != null && c.isAlive() && c.getMap() == map) c.changeMapBanish(m.getBanish());
            },action.delay());
            state.due = now + Math.max(900,Math.max(action.delay(),m.getStats().getAnimationTime("skill" + action.action()))); return;
        }
        for (int n = 0; n < kit.attacks().size(); n++) {
            int index = kit.attacks().get(state.nextAttack++ % kit.attacks().size());
            MobAttackInfo attack = MobAttackInfoFactory.getMobAttackInfo(m,index);
            if (attack == null || !attack.canTarget(m.getPosition(),target.getPosition(),left) || m.canUseAttack(index,false) < 1) continue;
            emitAction(m,12+index,left,0,0);
            CompanionMonsterAttacks.accepted(m,index,left);
            state.due = now + Math.max(900,Math.max(attack.getAttackDelay(),m.getStats().getAnimationTime("attack"+(index+1)))); return;
        }
        moveOnTerrain(m,target,kit.flying());
        emitAction(m,0,left,0,0); state.due = now+500;
    }
    /** Flying WZ actors remain in the map rectangle; walkers remain on their current foothold. */
    static void moveOnTerrain(Monster m, Character target, boolean flying) {
        if (!m.getStats().isMobile()) return;
        if (flying) {
            Point from=m.getPosition(), goal=target.getPosition(); double distance=from.distance(goal);
            if (distance <= 1) return;
            double fraction=Math.min(1,20/distance);
            Point next=new Point(from.x+(int)((goal.x-from.x)*fraction),from.y+(int)((goal.y-from.y)*fraction));
            if (m.getMap().getMapArea().contains(next)) {m.setPosition(next);m.getMap().moveMonster(m,next);}
            return;
        }
        boolean left=target.getPosition().x < m.getPosition().x;
        if (m.getStats().isMobile() && m.getFh() > 0) {
            var foothold = m.getMap().getFootholds().findBelow(new Point(m.getPosition().x,m.getPosition().y-10));
            if (foothold != null && foothold.getId() == m.getFh() && foothold.getX2() != foothold.getX1()) {
                int x = m.getPosition().x + (left ? -1 : 1) * 20;
                if (x >= Math.min(foothold.getX1(),foothold.getX2()) && x <= Math.max(foothold.getX1(),foothold.getX2())) {
                    double fraction = (x-foothold.getX1())/(double)(foothold.getX2()-foothold.getX1());
                    m.setPosition(new Point(x,(int)(foothold.getY1()+fraction*(foothold.getY2()-foothold.getY1()))-1));
                    m.getMap().moveMonster(m,m.getPosition());
                }
            }
        }
    }
    private static void emitAction(Monster m,int action,boolean left,int skill,int level) {
        var packet = PacketCreator.serverMonsterAction(m,action,left,skill,level);
        BossTelemetry.packet(packet.getBytes().length); m.getMap().broadcastMessage(packet);
    }
    private static Kit load(int id) {
        DataProvider source = DataProviderFactory.getDataProvider(WZFiles.MOB);
        Data data = source.getData(String.format("%07d.img",id));
        String link = DataTool.getString("info/link",data,"");
        if (!link.isEmpty()) data = source.getData(String.format("%07d.img",Integer.parseInt(link)));
        List<Integer> attacks = new ArrayList<>(); List<SkillAction> skills = new ArrayList<>();
        for (int n = 0; n < 9; n++) if (data.getChildByPath("attack"+(n+1)+"/info") != null) attacks.add(n);
        Data skillData = data.getChildByPath("info/skill");
        if (skillData != null) for (Data s : skillData) {
            int skill = DataTool.getInt("skill",s,0), level = DataTool.getInt("level",s,0);
            MobSkillType.from(skill).ifPresent(type -> skills.add(new SkillAction(new MobSkillId(type,level),
                    Math.max(1,Math.min(9,DataTool.getInt("action",s,1))),Math.max(0,DataTool.getInt("effectAfter",s,0)))));
        }
        return new Kit(List.copyOf(attacks),List.copyOf(skills),data.getChildByPath("fly") != null);
    }
}
