package server.events.gm;

import java.util.*;
import java.util.function.LongSupplier;

/** Account-scoped coalescing with finite expiry, acceptance cooldown and post-event channel gap. */
public final class EventRequestCoordinator {
    public record ChannelId(int world,int channel) {}
    public record Request(UUID id,ChannelId channel,String key,Map<Integer,Integer> accountActors,long expiresAt) {}
    /** Null delivery retries later; an empty accepted set closes a failed empty dispatch without charging accounts. */
    public record Delivery(Set<Integer> acceptedAccounts) {
        public Delivery { acceptedAccounts=Set.copyOf(acceptedAccounts); }
    }
    public interface Gateway {
        boolean approved(String key);
        boolean ready(int actorId,ChannelId channel);
        boolean busy(ChannelId channel);
        Delivery dispatch(Request request);
    }
    private final LongSupplier clock;
    private final Gateway gateway;
    private final Map<Integer,UUID> inFlight=new HashMap<>();
    private final Map<Integer,Long> acceptedUntil=new HashMap<>();
    private final Map<ChannelId,Long> channelReadyAt=new HashMap<>();
    private final LinkedHashMap<UUID,Request> queue=new LinkedHashMap<>();
    private final Set<UUID> dispatching=new HashSet<>();
    private final java.util.concurrent.atomic.AtomicBoolean pumping=new java.util.concurrent.atomic.AtomicBoolean();
    public EventRequestCoordinator(LongSupplier clock,Gateway gateway) {this.clock=clock;this.gateway=gateway;}
    public synchronized String request(int accountId,int actorId,ChannelId channel,String key,int queueBudget) {
        expire();
        if(accountId<=0 || actorId<=0 || queueBudget<1 || !gateway.approved(key)) return "That event has not passed gameplay, bot and measured capacity approval.";
        if(inFlight.containsKey(accountId)) return "Your account already has an event request in flight.";
        if(acceptedUntil.getOrDefault(accountId,0L)>clock.getAsLong()) return "Your account's 30-minute event request cooldown is active.";
        if(!gateway.ready(actorId,channel)) return "You cannot request an event from your current state/map.";
        Request existing=queue.values().stream().filter(r->r.channel().equals(channel) && r.key().equals(key)).findFirst().orElse(null);
        if(existing!=null) {
            if(dispatching.contains(existing.id())) return "That event is starting; use @joinevent to participate.";
            if(existing.accountActors().size()>=queueBudget) return "That event's requesting participant queue is full.";
            Map<Integer,Integer> actors=new HashMap<>(existing.accountActors());actors.put(accountId,actorId);
            queue.put(existing.id(),new Request(existing.id(),channel,key,Map.copyOf(actors),existing.expiresAt()));
            inFlight.put(accountId,existing.id());return "Joined the queued request for "+key+"; duplicate events are coalesced.";
        }
        if(queue.values().stream().filter(r->r.channel().equals(channel)).count()>=queueBudget) return "The channel's event request queue is full.";
        UUID id=UUID.randomUUID();queue.put(id,new Request(id,channel,key,Map.of(accountId,actorId),clock.getAsLong()+10*60_000));
        inFlight.put(accountId,id);return "Queued "+key+" on channel "+channel.channel()+". Stay online to participate.";
    }
    public void pump() {
        if(!pumping.compareAndSet(false,true)) return;
        try {pumpOnce();} finally {pumping.set(false);}
    }
    private void pumpOnce() {
        List<Request> candidates;
        synchronized(this) {expire();candidates=List.copyOf(queue.values());}
        for(Request original:candidates) {
            if(gateway.busy(original.channel())) continue;
            Request request;
            synchronized(this) {
                request=queue.get(original.id());
                if(request==null || channelReadyAt.getOrDefault(request.channel(),0L)>clock.getAsLong()) continue;
                dispatching.add(request.id());
            }
            try {
                Map<Integer,Integer> ready=new HashMap<>();
                for(var entry:request.accountActors().entrySet()) if(gateway.ready(entry.getValue(),request.channel())) ready.put(entry.getKey(),entry.getValue());
                if(ready.isEmpty() || !gateway.approved(request.key())) {synchronized(this){remove(request);}continue;}
                Request dispatch=new Request(request.id(),request.channel(),request.key(),Map.copyOf(ready),request.expiresAt());
                Delivery delivered=gateway.dispatch(dispatch);
                if(delivered!=null) synchronized(this) {
                    for(int account:delivered.acceptedAccounts()) if(ready.containsKey(account))
                        acceptedUntil.put(account,clock.getAsLong()+30*60_000);
                    remove(request);
                }
            } finally {
                synchronized(this) {dispatching.remove(request.id());}
            }
        }
    }
    public synchronized void completed(ChannelId channel) {channelReadyAt.put(channel,clock.getAsLong()+5*60_000);}
    public synchronized void cancelChannel(ChannelId channel) {new ArrayList<>(queue.values()).stream().filter(r->r.channel().equals(channel)).forEach(this::remove);}
    private void remove(Request request) {
        Request actual=queue.remove(request.id());dispatching.remove(request.id());
        if(actual!=null) actual.accountActors().keySet().forEach(a->inFlight.remove(a,request.id()));
    }
    private void expire() {
        long now=clock.getAsLong();
        new ArrayList<>(queue.values()).stream().filter(r->r.expiresAt()<=now).forEach(this::remove);
        acceptedUntil.values().removeIf(t->t<=now);channelReadyAt.values().removeIf(t->t<=now);
    }
    public synchronized int queued() {expire();return queue.size();}
}
