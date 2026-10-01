package server.events.gm;

import client.Character;
import client.Client;
import net.server.channel.Channel;
import net.server.Server;
import server.TimerManager;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.CompanionSystem.CompanionTaskService;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import tools.PacketCreator;

import java.awt.Point;
import java.util.*;
import java.util.concurrent.ScheduledFuture;

/** Finite NPC GM show with genuine incident combat, staggered spawns and exact host/map ownership. */
public final class WaveInvasionService {
    // The 80-responder development trial overflowed one legacy client's outbound
    // queue during the third Henesys wave (3,301 pending; oldest >10 seconds).
    // Keep a visibly active defense while bounding per-viewer packet pressure.
    private static final int HENESYS_TRIAL_RESPONDERS = 24;
    private static final WaveInvasionService INSTANCE = new WaveInvasionService();
    public static WaveInvasionService getInstance() { return INSTANCE; }
    private static final class Show {
        final String id; final Channel channel; final MapleMap map; final Character host;
        final CompanionTaskService.EventLease lease; final WaveSequence sequence; final Point position;
        final long expires = System.currentTimeMillis() + 2 * 60 * 60_000L;
        volatile ScheduledFuture<?> timer; volatile boolean closed;
        Show(String id, Channel channel, MapleMap map, Character host, CompanionTaskService.EventLease lease, WaveSequence sequence, Point position) {
            this.id=id;this.channel=channel;this.map=map;this.host=host;this.lease=lease;this.sequence=sequence;this.position=new Point(position);
        }
    }
    private final Map<Channel, Show> shows = new HashMap<>();
    private WaveInvasionService() {}
    public String start(Character operator, String mode) {
        if (operator.gmLevel() < 3) return "GM rank3 required.";
        return start(operator.getClient().getChannelServer(), operator.getMapId(), mode);
    }
    /** Trusted server operator entry; player commands still enforce GM rank. Uses the same finite show. */
    public String startHenesys(Channel channel) { return start(channel, 100000000, "bosses"); }
    private String start(Channel channel, int mapId, String mode) {
        List<WaveSequence.Wave> plan;
        if (mode.equals("bosses")) plan=WaveSequence.bosses();
        else if (mode.equals("mobs")) plan=WaveSequence.mobs();
        else return "Usage: !event waves [bosses|mobs]";
        MapleMap map=channel.getMapFactory().getMap(mapId);
        if (map==null || map.getEventInstance()!=null || map.getPortal(0)==null) return "A public map with a spawn portal is required.";
        if (map.getAllMonsters().stream().anyMatch(m->m.isAlive() && m.getStats().isBoss()))
            return "Finish the existing bosses before starting the hosted show.";
        var portal=map.getPortal(mapId==100000000?1:0);
        if (portal==null) return "Host placement portal is missing.";
        Point origin=portal.getPosition(), position=map.getPointBelow(origin);
        if (position==null || !map.getMapArea().contains(position)) return "No grounded event placement here.";
        String id=UUID.randomUUID().toString();
        synchronized(this) {
            if (shows.containsKey(channel)) return "This channel already has a hosted show.";
            if (!EventMapLeases.acquire(id,List.of(map))) return "This map is already running an event.";
        }
        Show show=null;
        CompanionTaskService.EventLease token=null;
        try {
            var candidates=CharacterStorage.getAllBots().values().stream()
                    .filter(bot->EventBotRuntime.eligible(bot,channel.getWorld(),channel.getId()))
                    .sorted(Comparator.<soloMapling.ArtificialPlayer.BotSM>comparingInt(bot->bot.getChr().getLevel()).reversed()).toList();
            for (var previous:candidates) {
                token=EventBotRuntime.reserve(previous,id,CompanionTaskService.EventRole.HOST,channel.getWorld(),channel.getId(),HENESYS_TRIAL_RESPONDERS+1).orElse(null);
                if (token==null) continue;
                var lease=CompanionTaskService.shared().commitEvent(token).orElse(null);
                if (lease==null) {CompanionTaskService.shared().releaseEvent(token.botId(),token.generation());token=null;continue;}
                Character host=previous.getChr();
                show=new Show(id,channel,map,host,lease,new WaveSequence(plan,System.currentTimeMillis()),position);
                synchronized(this) {
                    if (shows.containsKey(channel)) throw new IllegalStateException("Channel show changed during preparation");
                    shows.put(channel,show);
                }
                if (!GmHostPresentation.activate(host,lease) || !EventBotRuntime.activateWaveHost(previous,lease))
                    throw new IllegalStateException("GM host activation failed");
                synchronized(host) {
                    Point balcony=map.getPointBelow(new Point(origin.x,origin.y-400));
                    if (balcony!=null && map.getMapArea().contains(balcony)) host.changeMap(map,new Point(balcony.x,balcony.y-1));
                    else host.changeMap(map,portal);
                    if (host.getMap()!=map) throw new IllegalStateException("Host map entry failed");
                }
                announce(show,"I am hosting "+(mode.equals("bosses")?"normal, blue and zombie Mushmom plus Jr. Balrog waves":"a snail invasion")
                        +" in map "+mapId+", channel "+channel.getId()+". Starts in20 seconds; approach when ready."
                        +(mode.equals("bosses")?" Each wave starts with5-10 bosses, arriving one second apart; every3 minutes survivors are topped up toward7-10. Clear them all for the next wave immediately. Maximum10 live bosses.":" Maximum30 ordinary mobs alive."));
                Show active=show;
                show.timer=TimerManager.getInstance().register(()->tick(active),1000,1000);
                if (show.closed) show.timer.cancel(false);
                return "Hosted event "+id+" opened by "+GmHostPresentation.displayName(host)+". UNMEASURED development trial; finite existing population, active response limit"+HENESYS_TRIAL_RESPONDERS+". Stop: !event stop-waves";
            }
            return "No available existing bot can host this event.";
        } catch (RuntimeException failure) {
            if (show!=null) stop(show,"Host preparation failed");
            return "Hosted event preparation failed: "+failure.getMessage();
        } finally {
            if (show==null) {
                EventMapLeases.release(id);
                if (token!=null) CompanionTaskService.shared().releaseEvent(token.botId(),token.generation());
            }
        }
    }
    synchronized boolean ownsHost(String id,Character actor) {
        return shows.values().stream().anyMatch(s->!s.closed && s.id.equals(id) && s.host==actor);
    }
    public void hostTick(Character actor,CompanionTaskService.EventLease lease) {
        Show show;synchronized(this) {show=shows.values().stream()
                .filter(candidate->candidate.host==actor && candidate.lease.equals(lease)).findFirst().orElse(null);}
        if (show==null || show.host!=actor || !show.lease.equals(lease)) {
            GmHostPresentation.restore(actor,lease.generation());EventBotRuntime.release(actor.getId(),lease.generation(),true);return;
        }
        Client client=actor.getClient();
        if (client==null || client.getChannelServer()!=show.channel || !actor.isAlive() || actor.getMap()!=show.map
                || !lease.equals(CompanionTaskService.shared().eventLease(actor.getId()).orElse(null)))
            stop(show,"The host can no longer continue");
        else GCMovement.stop(actor);
    }
    private void tick(Show show) {
        if (show.closed) return;
        if (System.currentTimeMillis()>=show.expires || show.channel.getMapFactory().getMap(show.map.getId())!=show.map)
            {stop(show,"Event expired or map changed");return;}
        hostTick(show.host,show.lease);
        if (show.closed) return;
        var token=show.sequence.next(System.currentTimeMillis());
        if (token==null) return;
        announce(show,"Wave"+(token.index()+1)+"/"+show.sequence.total()+": "+token.wave().encounter()+". The next wave waits for actual defeat.");
        String result=IncidentService.getInstance().createHosted(show.host,show.id,token.wave().encounter(),token.wave().count(),HENESYS_TRIAL_RESPONDERS,show.position,
                victory->completed(show,token,victory));
        if (!result.startsWith("Created ")) stop(show,result);
    }
    private void completed(Show show,WaveSequence.Token token,boolean victory) {
        if (show.closed || !show.sequence.complete(token,victory,System.currentTimeMillis())) return;
        if (show.sequence.closed()) stop(show,victory?"All waves were genuinely defeated":"The defense ended without victory");
        else {
            announce(show,"Wave cleared. The next wave is starting.");
            TimerManager.getInstance().schedule(()->tick(show),0);
        }
    }
    public String stop(Character operator) {
        if (operator.gmLevel()<3) return "GM rank3 required.";
        return stopChannel(operator.getClient().getChannelServer());
    }
    public String stopChannel(Channel channel) {
        Show show;synchronized(this){show=shows.get(channel);}
        if(show==null)return "No hosted wave event is running.";
        stop(show,"Event stopped");return "Hosted event stopped; owned mobs and host tasks were cleaned up.";
    }
    private void stop(Show show,String reason) {
        synchronized(this) {if(show.closed)return;show.closed=true;shows.remove(show.channel,show);show.sequence.cancel();}
        org.slf4j.LoggerFactory.getLogger(WaveInvasionService.class).info(
                "Hosted event {} ended: {}; host={} hp={} map={}; completed={}/{}",
                show.id,reason,GmHostPresentation.displayName(show.host),show.host.getHp(),show.host.getMapId(),
                show.sequence.completed(),show.sequence.total());
        if(show.timer!=null)show.timer.cancel(false);
        IncidentService.getInstance().cancelHosted(show.id);
        announce(show,reason+".");
        GmHostPresentation.restore(show.host,show.lease.generation());
        EventBotRuntime.release(show.host.getId(),show.lease.generation(),true);
        EventMapLeases.release(show.id);
    }
    private static void announce(Show show,String message) {
        Server.getInstance().getWorld(show.channel.getWorld()).broadcastPacket(
                PacketCreator.serverNotice(6,GmHostPresentation.displayName(show.host)+": "+message));
    }
    public synchronized String status(Channel channel) {
        Show show=shows.get(channel);
        return show==null?"No NPC GM wave event.":show.id+" host="+GmHostPresentation.displayName(show.host)
                +"; completed="+show.sequence.completed()+"/"+show.sequence.total()+"; hosted boss cap=10; top-up every3 minutes toward7-10; ordinary cap=30; UNMEASURED.";
    }
}
