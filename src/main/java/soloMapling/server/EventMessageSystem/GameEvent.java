package soloMapling.server.EventMessageSystem;

import client.Character;
import client.inventory.Item;
import server.maps.MapleMap;

import static soloMapling.DebugUtilities.debugprint;

public class GameEvent {
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_ID = new java.util.concurrent.atomic.AtomicInteger();

    private final int id;
    private final long timestamp;
    private final Character mapleCharacter;
    private final int world;
    private final int channel;
    private final MapleMap map;
    private final String playerName;
    private final int playerId;
    private final EventType type;
    private final String message;
    private final Item item;
    private final Boolean pass;
    private final IncidentSignal incident;

    public GameEvent(Character mapleCharacter,
                     EventType type, String message, Item item, Boolean pass) {
        this(mapleCharacter,mapleCharacter.getMap(),type,message,item,pass,null);
    }

    public GameEvent(Character mapleCharacter,MapleMap map,EventType type,IncidentSignal incident) {
        this(mapleCharacter,map,type,incident.phase(),null,null,incident);
    }

    private GameEvent(Character mapleCharacter,MapleMap map,EventType type,String message,Item item,Boolean pass,IncidentSignal incident) {
        this.id = NEXT_ID.incrementAndGet();
        this.timestamp = System.currentTimeMillis();
        this.mapleCharacter = mapleCharacter;
        this.world = map.getWorld();
        this.channel = map.getChannelServer().getId();
        this.map = map;
        this.playerName = mapleCharacter==null?"Incident":mapleCharacter.getName();
        this.playerId = mapleCharacter==null?0:mapleCharacter.getId();

        this.type = type;
        this.message = message;
        this.item = item;
        this.pass = pass;
        this.incident = incident;
    }

    public int getId() { return id; }
    public long getTimestamp() { return timestamp; }
    public Character getMapleCharacter() { return mapleCharacter; }
    public int getWorld() { return world; }
    public int getChannel() { return channel; }
    public MapleMap getMap() { return map; }
    public String getPlayerName() { return playerName; }
    public int getPlayerId() { return playerId; }
    public EventType getType() { return type; }
    public String getMessage() { return message; }
    public Item getItem() { return item; }
    public Boolean getPass() { return pass; }
    public IncidentSignal getIncident() { return incident; }
    public void printDescription() {
        debugprint("GameEvent: ", id, mapleCharacter, type, message, item, pass);
    }
}
