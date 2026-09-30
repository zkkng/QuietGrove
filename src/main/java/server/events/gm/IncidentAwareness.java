package server.events.gm;

import java.util.*;

/** Finite population/evidence model. No world-wide silent seed, generated volunteers or endless waves. */
public final class IncidentAwareness {
    public enum State { UNAWARE, WITNESS, FLEE, PREPARING, TRAVELING, FIGHTING, RETREATED, DEAD, RECOVERING, RELEASED }
    public record Candidate(int id,int homeMap,List<Integer> contacts,double courage,long preparationMs) {
        public Candidate {contacts=List.copyOf(contacts);}
    }
    public record Evidence(int sourceId,long learnedAt,long expiresAt,int mapId,double confidence,int hops) {}
    public record Response(int id,State state,Evidence evidence,long due,int deaths) {}
    public record Report(int sourceId,int targetId,long due,Evidence evidence) {}
    private final Map<Integer,Candidate> population;
    private final Map<Integer,Response> responses=new LinkedHashMap<>();
    private final PriorityQueue<Report> reports=new PriorityQueue<>(Comparator.comparingLong(Report::due));
    private final Set<String> sent=new HashSet<>();
    private final long generation;
    private boolean cancelled;
    public IncidentAwareness(long generation,Collection<Candidate> candidates) {
        this.generation=generation; Map<Integer,Candidate> snapshot=new LinkedHashMap<>();
        for(Candidate c:candidates) if(c.id()>0) snapshot.put(c.id(),c);
        population=Map.copyOf(snapshot); snapshot.forEach((id,c)->responses.put(id,new Response(id,State.UNAWARE,null,0,0)));
    }
    public synchronized boolean witness(int id,int map,long now) {
        if(cancelled || !population.containsKey(id)) return false;
        Response old=responses.get(id);
        if(old.state()!=State.UNAWARE) return false;
        Evidence evidence=new Evidence(id,now,now+5*60_000,map,1,0);
        responses.put(id,new Response(id,State.WITNESS,evidence,now+1000+Math.floorMod(id*31L+generation,6000),old.deaths()));
        return true;
    }
    public synchronized void announce(Collection<Integer> audience,int map,long now) {
        for(int id:audience) witness(id,map,now);
    }
    /** Existing contacts and actual nearby witnesses only; bounded fan-out, TTL, hops and deduplication. */
    public synchronized void report(int source,Collection<Integer> nearby,long now) {
        Response from=responses.get(source);
        if(cancelled || from==null || from.evidence()==null || now>=from.evidence().expiresAt() || from.evidence().hops()>=4) return;
        LinkedHashSet<Integer> targets=new LinkedHashSet<>(population.get(source).contacts());targets.addAll(nearby);
        int fanout=0;
        for(int target:targets) {
            if(fanout>=3) break;
            if(target==source || !population.containsKey(target) || !sent.add(source+":"+target)) continue;
            Response receiver=responses.get(target);
            if(receiver.state()!=State.UNAWARE) continue;
            long due=now+2000+Math.floorMod(source*17L+target*31L+generation,12000);
            Evidence evidence=new Evidence(source,due,from.evidence().expiresAt(),from.evidence().mapId(),from.evidence().confidence()*.85,from.evidence().hops()+1);
            reports.add(new Report(source,target,due,evidence));fanout++;
        }
    }
    public synchronized List<Response> pump(long now,long expectedGeneration) {
        if(cancelled || expectedGeneration!=generation) return List.of();
        responses.replaceAll((id,r)->r.state()==State.RECOVERING && r.due()<=now && r.deaths()<2
                && r.evidence()!=null && r.evidence().expiresAt()>now
                ?new Response(id,State.WITNESS,r.evidence(),now+population.get(id).preparationMs(),r.deaths()):r);
        while(!reports.isEmpty() && reports.peek().due()<=now) {
            Report report=reports.remove();Response old=responses.get(report.targetId());
            if(old.state()==State.UNAWARE && now<report.evidence().expiresAt())
                responses.put(old.id(),new Response(old.id(),State.WITNESS,report.evidence(),now+population.get(old.id()).preparationMs(),old.deaths()));
        }
        return responses.values().stream().filter(r->r.state()==State.WITNESS && r.due()<=now
                && r.evidence()!=null && r.evidence().expiresAt()>now).toList();
    }
    public synchronized State decide(int id,boolean available,boolean reachable,double hitChance,double survival,long now) {
        Response response=responses.get(id);Candidate candidate=population.get(id);
        if(cancelled || response==null || response.state()!=State.WITNESS || response.due()>now) return State.UNAWARE;
        boolean capable=available && reachable && hitChance>=.35 && survival>=1.2+.4*response.deaths();
        // A reckless initial mistake is possible; casualties update evidence and later willingness.
        boolean reckless=response.deaths()==0 && available && reachable && candidate.courage()>.97 && survival>=.6;
        State state=capable || reckless?State.PREPARING:State.FLEE;
        responses.put(id,new Response(id,state,response.evidence(),now+candidate.preparationMs(),response.deaths()));return state;
    }
    public synchronized boolean depart(int id,long now) {
        Response r=responses.get(id);
        if(cancelled || r==null || r.state()!=State.PREPARING || now<r.due()) return false;
        responses.put(id,new Response(id,State.TRAVELING,r.evidence(),now,r.deaths()));return true;
    }
    public synchronized boolean arrive(int id,long now) {
        Response r=responses.get(id);
        if(cancelled || r==null || r.state()!=State.TRAVELING) return false;
        responses.put(id,new Response(id,State.FIGHTING,r.evidence(),now,r.deaths()));return true;
    }
    public synchronized void casualty(int id,long now) {
        Response r=responses.get(id);if(cancelled || r==null || r.state()==State.DEAD || r.state()==State.RECOVERING) return;
        int deaths=r.deaths()+1;responses.put(id,new Response(id,State.DEAD,r.evidence(),now+6000,deaths));
    }
    public synchronized void recovered(int id,long now) {
        Response r=responses.get(id);if(cancelled || r==null || r.state()!=State.DEAD || now<r.due()) return;
        responses.put(id,new Response(id,State.RECOVERING,r.evidence(),now+60_000L*r.deaths(),r.deaths()));
    }
    public synchronized void retire(int id,long now) {
        Response r=responses.get(id);if(r!=null) responses.put(id,new Response(id,State.RETREATED,r.evidence(),now,r.deaths()));
    }
    public synchronized Response response(int id) {return responses.get(id);}
    public synchronized List<Response> responses() {return List.copyOf(responses.values());}
    public synchronized void cancel() {cancelled=true;reports.clear();responses.replaceAll((id,r)->new Response(id,State.RELEASED,r.evidence(),r.due(),r.deaths()));}
    public int population() {return population.size();}
    public long generation() {return generation;}
}
