package server.events.gm;

import com.esotericsoftware.yamlbeans.YamlReader;
import java.nio.file.*;
import java.util.*;

/** Operator-supplied measured evidence. Missing/incomplete evidence never produces a guessed crowd limit. */
public final class EventCapacityProfiles {
    public record Measurements(int repeatPasses,int soakMinutes,int renderClients,int active,int visible,
            double actionP95Ms,double actionP99Ms,double driftMs,double cpuPercent,double heapPercent,
            double clientOnePercentLowFps,double clientRecurringStallMs,boolean growingQueues,int disconnects,
            boolean gameplayPassed,String clientMachine,String reportPath) {
        public boolean passes() {
            return repeatPasses>=3 && soakMinutes>=60 && renderClients>=1 && active>0 && visible>=active
                && actionP95Ms>=0 && actionP95Ms<=100 && actionP99Ms>=actionP95Ms && actionP99Ms<=200
                && driftMs>=0 && driftMs<=250 && cpuPercent>0 && cpuPercent<=80 && heapPercent>0 && heapPercent<=75
                && clientOnePercentLowFps>=30 && clientRecurringStallMs>=0 && clientRecurringStallMs<=250
                && !growingQueues && disconnects==0 && gameplayPassed && clientMachine!=null && !clientMachine.isBlank()
                && reportPath!=null && !reportPath.isBlank();
        }
    }
    public record Profile(String id,String eventKey,int definitionVersion,List<Integer> maps,int serverActive,
            int clientActive,int visible,int operatorActive,int worldLimit,int pending,int humanReserve,
            int arrivalBurst,int promotionBudget,double headroom,boolean humanVerified,boolean botsVerified,
            boolean requestsApproved,Measurements measurements) {
        public boolean verified(EventDefinition definition) {
            return id!=null && !id.isBlank() && definition!=null && eventKey.equals(definition.key())
                && definitionVersion==definition.version() && maps!=null && new HashSet<>(maps).equals(new HashSet<>(definition.mapIds()))
                && serverActive>0 && clientActive>0 && operatorActive>0 && worldLimit>0 && pending>0 && humanReserve>=0
                && arrivalBurst>0 && promotionBudget>0 && headroom>=.15 && headroom<1 && humanVerified && botsVerified
                && measurements!=null && measurements.passes() && measurements.active()>=Math.min(serverActive,clientActive)
                && measurements.visible()>=visible && visible>=Math.min(serverActive,clientActive)
                && capacity().activeLimit()>=humanReserve
                // Requested events always have a visible bot host and must reserve a human entrant.
                && (!requestsApproved || humanReserve>=1 && capacity().visibleLimit()>=2);
        }
        public EventSession.Capacity capacity() {
            int limit=(int)Math.floor(Math.min(Math.min(serverActive,clientActive),Math.min(operatorActive,worldLimit))*(1-headroom));
            int screen=(int)Math.floor(visible*(1-headroom));
            return new EventSession.Capacity(Math.max(1,limit),Math.max(Math.max(1,limit),screen),pending,humanReserve,id,true);
        }
    }
    private static volatile Map<String,Profile> profiles=Map.of();
    static {reload();}
    private EventCapacityProfiles() {}
    public static Profile forEvent(String key) { return profiles.get(key); }
    public static String reload() {
        Path file=Path.of("server-config/event-capacity-profiles.yaml");
        if(!Files.isRegularFile(file)) { profiles=Map.of(); return "No measured event-capacity profiles installed."; }
        try {
            List<Profile> loaded=new ArrayList<>();
            try(var source=Files.newBufferedReader(file)) {
                YamlReader reader=new YamlReader(source);
                Object value=reader.read();
                if(!(value instanceof List<?> entries)) throw new IllegalArgumentException("Expected profile list");
                for(Object entry:entries) loaded.add(readRecord(Profile.class,entry));
                reader.close();
            }
            Map<String,Profile> next=new HashMap<>();
            for(Profile profile:loaded) {
                EventDefinition definition=EventDefinition.find(profile.eventKey());
                if(definition==null && profile.eventKey().startsWith("invasion:") && profile.maps().size()==1) {
                    String[] parts=profile.eventKey().split(":");
                    if(parts.length!=3 || Integer.parseInt(parts[2])!=profile.maps().getFirst()
                            || !IncidentService.approvedEncounter(parts[1])) throw new IllegalArgumentException("Invalid incident encounter/map key");
                    definition=new EventDefinition(profile.eventKey(),1,profile.maps().getFirst(),profile.maps(),1,2*60*60_000L,0,false,"");
                }
                if(!profile.verified(definition)) return "Rejected invalid/unverified capacity profile "+profile.id()+"; prior profiles retained.";
                if(next.put(profile.eventKey(),profile)!=null) return "Rejected duplicate event capacity profile; prior profiles retained.";
            }
            profiles=Map.copyOf(next); return "Loaded "+next.size()+" measured event profiles.";
        } catch(Exception invalid) { return "Capacity profile file rejected: "+invalid.getMessage(); }
    }
    private static <T> T readRecord(Class<T> type,Object source) throws ReflectiveOperationException {
        if(!(source instanceof Map<?,?> values)) throw new IllegalArgumentException("Expected "+type.getSimpleName()+" mapping");
        var fields=type.getRecordComponents();
        Class<?>[] types=new Class<?>[fields.length]; Object[] args=new Object[fields.length];
        for(int i=0;i<fields.length;i++) {
            String name=fields[i].getName(); Class<?> field=fields[i].getType(); types[i]=field;
            Object value=values.get(name);
            if(value==null) throw new IllegalArgumentException("Missing "+name);
            if(field==int.class) args[i]=Integer.parseInt(value.toString());
            else if(field==double.class) args[i]=Double.parseDouble(value.toString());
            else if(field==boolean.class) {
                if(!value.toString().equals("true") && !value.toString().equals("false")) throw new IllegalArgumentException("Invalid "+name);
                args[i]=Boolean.parseBoolean(value.toString());
            } else if(field==String.class) args[i]=value.toString();
            else if(field==List.class && value instanceof List<?> list) args[i]=list.stream().map(v->Integer.parseInt(v.toString())).toList();
            else if(field.isRecord()) args[i]=readRecord(field,value);
            else throw new IllegalArgumentException("Invalid "+name);
        }
        return type.getDeclaredConstructor(types).newInstance(args);
    }
}
