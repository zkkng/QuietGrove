package soloMapling.ArtificialPlayer.CompanionSystem;

import client.Character;
import client.inventory.*;
import scripting.event.EventInstanceManager;
import server.expeditions.*;
import server.maps.*;
import soloMapling.ArtificialPlayer.GCMoveSystem.*;
import java.util.*;

/** Explicit access adapters mirror local scripts. The human performs canonical encounter entry. */
public final class BossAccess {
    private BossAccess() {}
    public static boolean mapAllowed(Character c, BossDefinition d) {
        if (c == null || c.getMap() == null) return false;
        if (d.restricted()) {
            var eim = c.getEventInstance();
            return eim != null && !eim.isEventDisposed() && eim.getEm().getName().equals(d.eventScript())
                    && d.maps().contains(c.getMapId()) && c.getMap().getEventInstance() == eim;
        }
        if (d.type() == BossDefinition.Type.BOAT && Set.of(200090000,200090010,200090001,200090011,101000301,200000112).contains(c.getMapId()))
            return c.getEventInstance() == null;
        return CompanionRuntime.ordinaryMap(c);
    }
    public static String requestFailure(Character human, BossDefinition d) {
        if (!human.isAlive()) return "Recover before asking for a boss hunt.";
        if (human.getEventInstance() != null && !mapAllowed(human,d)) return "Finish or leave your current instance before changing the boss objective.";
        if (human.getLevel() < entryLevel(d) && d.restricted()) return "The encounter requires level " + entryLevel(d) + ".";
        if (d.key().equals("papulatus") && ((human.getQuestStatus(6361) == 1 && human.haveItem(4031870))
                || human.getQuestStatus(6361) == 2 && human.getQuestStatus(6363) != 2))
            return "The Machine Room gate leads to your current quest stage. Finish that stage before asking for this encounter.";
        if (d.restricted() && d.key().equals("zakum") && human.getQuestStatus(100200) == 0)
            return "Earn the council's Zakum approval and complete Adobis's trials first.";
        if (d.restricted() && d.key().equals("horntail") && !human.haveItem(4001086)) return "Earn the Horntail Certificate of the Dragon Squad first.";
        return "";
    }
    public static String actorFailure(Character bot, BossDefinition d) {
        if (bot.getLevel() < entryLevel(d) && d.restricted()) return "encounter level requirement";
        if (d.restricted() && d.key().equals("zakum") && bot.getQuestStatus(100200) == 0) return "Zakum council approval missing";
        if (d.restricted() && d.key().equals("horntail") && !bot.haveItem(4001086)) return "Dragon Squad certificate missing";
        return "";
    }
    public static int entryLevel(BossDefinition d) { return d.key().equals("papulatus") ? 1 : d.minimumLevel(); }
    public static boolean legalGather(Character human, BossDefinition d, int map) {
        if (map == human.getMapId()) return true;
        if (human.getEventInstance() != null) return d.restricted() && d.maps().contains(map) && mapAllowed(human,d);
        if (d.restricted() && d.maps().contains(map)) return human.getEventInstance() != null && mapAllowed(human,d);
        if (map >= 900000000 || map / 10000 == 20009 || map / 10000 == 20008) return false;
        return CompanionNavigation.routeExists(human,map);
    }
    public static boolean combatMap(Character bot, Character leader) {
        BossDefinition d = BossRuntime.get().definition(bot);
        return d != null && mapAllowed(bot,d) && mapAllowed(leader,d)
                && bot.getMap() == leader.getMap() && bot.getEventInstance() == leader.getEventInstance();
    }
    /** Same adapter and exact instance only; entry never uses a bare map-ID warp. */
    public static boolean tick(Character bot, Character leader, BossDefinition d) {
        if (!bot.isAlive() || !leader.isAlive()) return false;
        if (d.type() == BossDefinition.Type.BOAT) return board(bot,leader,d);
        if (d.key().equals("horntail") && bot.getEventInstance() != null && bot.getEventInstance() == leader.getEventInstance()) {
            int stage = bot.getMapId() == 240060000 ? 1 : bot.getMapId() == 240060100 ? 2 : 0;
            int destination = stage == 1 ? 240060100 : 240060200;
            if (stage > 0 && leader.getMapId() >= destination && bot.getEventInstance().getIntProperty("defeatedHead") >= stage) {
                for (Portal portal : bot.getMap().getPortals()) if ("hontale_BR".equals(portal.getScriptName()) && portal.getPortalStatus()) {
                    return usePortal(bot,portal,bot.getEventInstance().getMapInstance(destination),"0");
                }
            }
        }
        return false; // Party/expedition entry is exclusively EventManager's canonical roster operation.
    }
    /** An approved scripted portal still requires physically reaching its entrance. */
    private static boolean usePortal(Character bot, Portal source, MapleMap target, String arrivalName) {
        if (target == null) return false;
        var arrival = target.getPortal(arrivalName);
        if (arrival == null) arrival = target.getPortal(0);
        if (arrival == null) return false;
        if (bot.getPosition().distanceSq(source.getPosition()) > 35*35) {
            GCMovement.move(bot,source.getPosition().x,source.getPosition().y); return true;
        }
        GCMovement.stop(bot); bot.changeMap(target,arrival); return true;
    }
    private static boolean board(Character bot, Character leader, BossDefinition d) {
        int source = bot.getMapId();
        int waiting = source == 101000300 ? 101000301 : source == 200000111 ? 200000112 : -1;
        if (waiting < 0 || leader.getMapId() != waiting || bot.getMap().getChannelServer() != leader.getMap().getChannelServer()) return false;
        var em = bot.getMap().getChannelServer().getEventSM().getEventManager("Boats");
        if (em == null || !"true".equals(em.getProperty("entry"))) return false;
        int ticket = source == 101000300 ? 4031045 : 4031047;
        var etc = bot.getInventory(InventoryType.ETC);
        // These local NPC scripts sell this ticket for 5,000 mesos. Approach the real seller.
        if (etc.countById(ticket) < 1) {
            var seller = bot.getMap().getNPCById(source == 101000300 ? 1032007 : 2012000);
            if (seller == null || bot.getMeso() < 5000) return false;
            if (bot.getPosition().distanceSq(seller.getPosition()) > 100*100) {
                GCMovement.move(bot,seller.getPosition().x,seller.getPosition().y); return true;
            }
            synchronized (bot) {
                etc.lockInventory();
                try {
                    if (etc.countById(ticket) < 1 && bot.getMeso() >= 5000 && etc.addItem(new Item(ticket,(short)0,(short)1)) >= 0)
                        bot.gainMeso(-5000,false);
                } finally { etc.unlockInventory(); }
            }
        }
        var boarding = bot.getMap().getNPCById(source == 101000300 ? 1032008 : 2012001);
        if (boarding == null) return false;
        if (bot.getPosition().distanceSq(boarding.getPosition()) > 100*100) {
            GCMovement.move(bot,boarding.getPosition().x,boarding.getPosition().y); return true;
        }
        synchronized (bot) {
            etc.lockInventory();
            try {
                // Tickets may already have been bought/traded. Missing ticket means an honest refusal.
                if (etc.countById(ticket) < 1) return false;
                MapleMap target = bot.getMap().getChannelServer().getMapFactory().getMap(waiting);
                if (target == null || target.getPortal(0) == null || !"true".equals(em.getProperty("entry"))) return false;
                for (Item item : etc.list()) if (item.getItemId() == ticket && item.getQuantity() > 0) {
                    etc.removeItem(item.getPosition(),(short)1,false); break;
                }
                GCMovement.stop(bot); bot.changeMap(target,target.getPortal(0)); return true;
            } finally { etc.unlockInventory(); }
        }
    }
    public static boolean enterInstance(Character c, EventInstanceManager eim) {
        BossDefinition d = BossRuntime.get().definition(c);
        if (!soloMapling.ArtificialPlayer.BotHelpers.isBot(c)) return true;
        if (!CompanionRuntime.active(c)) return true; // Other event actor adapters retain their own admission.
        return d != null && d.restricted() && eim.getEm().getName().equals(d.eventScript()) && actorFailure(c,d).isEmpty()
                && c.isAlive() && (c.getMapId() == d.gatherMap() || c.getEventInstance() == eim);
    }
    /** Registration uses the ordinary attempt-limit and ban checks of Expedition.addMember. */
    public static void registerCompanions(Expedition expedition) {
        Character leader = expedition.getLeader();
        if (leader.getParty() == null) return;
        for (var task : CompanionTaskService.shared().tasks()) if (task.party().partyId() == leader.getPartyId()
                && task.party().worldId() == leader.getWorld() && task.ownerId() == leader.getId()) {
            var actor = soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById(task.botId());
            if (actor == null) continue;
            Character bot = actor.getChr(); BossDefinition d = BossRuntime.get().definition(bot);
            if (d == null || bot.getMap() != leader.getMap() || !actorFailure(bot,d).isEmpty()) continue;
            ExpeditionType required = switch (d.key()) {
                case "zakum" -> ExpeditionType.ZAKUM; case "horntail" -> ExpeditionType.HORNTAIL;
                case "instance-balrog" -> ExpeditionType.BALROG_NORMAL; case "easy-balrog" -> ExpeditionType.BALROG_EASY; default -> null;
            };
            if (expedition.getType() == required && !expedition.contains(bot)) expedition.addMember(bot);
        }
    }
}
