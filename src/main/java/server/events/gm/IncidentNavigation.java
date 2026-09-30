package server.events.gm;
import client.Character;
import server.maps.*;
import soloMapling.ArtificialPlayer.GCMoveSystem.*;

/** Unscripted physical portals only; inaccessible routes wait or retreat, never fallback warp. */
public final class IncidentNavigation {
    private IncidentNavigation() {}
    public static boolean reachable(Character actor,int mapId) {return actor.getMapId()==mapId || CompanionNavigation.routeExists(actor,mapId);}
    public static boolean step(Character actor,MapleMap target) {
        if(actor.getMap()==target) return true;
        Portal next=null;
        int nextMap=CompanionNavigation.nextMap(actor.getMapId(),target.getId());
        for(Portal portal:actor.getMap().getPortals()) if(portal.getPortalStatus() && portal.getType()!=Portal.DOOR_PORTAL
                && portal.getTargetMapId()!=actor.getMapId() && portal.getTargetMapId()<900000000
                && (portal.getScriptName()==null || portal.getScriptName().isBlank())
                && portal.getTargetMapId()==nextMap) {next=portal;break;}
        if(next==null) {GCMovement.stop(actor);return false;}
        if(Math.abs(actor.getPosition().x-next.getPosition().x)>35 || Math.abs(actor.getPosition().y-next.getPosition().y)>100) {
            if(!GCMovement.isMoving(actor)) GCMovement.move(actor,next.getPosition().x,next.getPosition().y);return false;
        }
        return enter(actor,next) && actor.getMap()==target;
    }
    /** Headless clients have no human player binding. Validate the real edge and move this actor only. */
    static boolean enter(Character actor,Portal portal) {
        if(actor==null || !actor.isAlive() || actor.getMap()==null || actor.getEventInstance()!=null || portal==null) return false;
        MapleMap source=actor.getMap();int targetId=portal.getTargetMapId();
        if(!source.getPortals().contains(portal) || !portal.getPortalStatus() || portal.getType()==Portal.DOOR_PORTAL
                || portal.getScriptName()!=null && !portal.getScriptName().isBlank()
                || targetId==source.getId() || targetId>=900000000 || targetId/10000==20009 || targetId/10000==20008
                || !soloMapling.server.MapleVersionManager.isPortalinCurrentVersion(targetId)) return false;
        if(portal.getPosition()==null || Math.abs(actor.getPosition().x-portal.getPosition().x)>35
                || Math.abs(actor.getPosition().y-portal.getPosition().y)>100) return false;
        MapleMap destination=source.getChannelServer().getMapFactory().getMap(targetId);
        Portal arrival=destination==null?null:destination.getPortal(portal.getTarget());
        if(arrival==null || destination.getEventInstance()!=null) return false;
        GCMovement.stop(actor);actor.changeMap(destination,arrival);return actor.getMap()==destination;
    }
}
