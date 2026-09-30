package soloMapling.server.EventMessageSystem;

import java.util.Set;

/** Typed lifecycle/evidence metadata. Audience limits preserve silent incident locality. */
public record IncidentSignal(String incidentId,long generation,int originMap,int sourceId,int targetId,
                             long evidenceTime,double confidence,Set<Integer> audience,String phase) {
    public IncidentSignal {
        audience=Set.copyOf(audience);
        if(incidentId==null || generation<=0 || phase==null) throw new IllegalArgumentException("incident identity");
    }
}
