package server.pccafe;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.life.Monster;
import soloMapling.ArtificialPlayer.BotHelpers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class PcCafe {
    public static final int HUB = 193000000, MOUSE = 4000047;
    public record Road(int map, int recommendedLevel) {}
    public record Reward(String currency, int item, int quantity, int price, int weeklyLimit) {}
    private static final PcCafeConfig CONFIG = PcCafeConfig.load(Path.of("server-config/pc-cafe.properties"));
    private static final List<Reward> REWARDS = loadRewards();
    private static final List<Road> ROADS = List.of(new Road(190000000,20), new Road(190000001,45),
            new Road(190000002,45),new Road(191000000,40),new Road(191000001,25),new Road(192000000,20),
            new Road(192000001,30),new Road(195000000,20),new Road(195010000,25),new Road(195020000,45),
            new Road(195030000,60),new Road(196000000,55),new Road(196010000,80),new Road(197000000,35),new Road(197010000,35));
    private PcCafe() {}
    public static List<Road> roads() { return ROADS; }
    public static List<Reward> rewards() { return REWARDS; }
    public static boolean enabled() { return CONFIG.enabled(); }
    /** Café field identity is independent of reward settings and event membership. */
    public static boolean isCafeMap(int mapId) {
        return mapId == HUB || ROADS.stream().anyMatch(road -> road.map() == mapId);
    }
    public static boolean hunting(Character chr) {
        return CONFIG.enabled() && chr.getEventInstance() == null && !BotHelpers.isBot(chr)
                && ROADS.stream().anyMatch(road -> road.map() == chr.getMapId());
    }
    public static double expMultiplier(Character chr) { return hunting(chr) ? CONFIG.expMultiplier() : 1; }
    public static double dropMultiplier(Character chr) { return hunting(chr) ? CONFIG.dropMultiplier() : 1; }
    public static void onKill(Character chr, Monster monster) {
        if (!hunting(chr) || !chr.isAlive() || monster.getMap() != chr.getMap() || monster.isBoss()
                || monster.getStats().getLevel() < chr.getLevel() - CONFIG.maxLevelDifference()) return;
        boolean mouse;
        synchronized (chr) {
            PcCafeState state = chr.getPcCafeState();
            state.refresh(Instant.now(), CONFIG);
            state.kill(CONFIG);
            mouse = state.available(CONFIG) > 0 && ThreadLocalRandom.current().nextDouble() < CONFIG.mouseChance();
        }
        if (mouse) chr.getMap().spawnItemDrop(monster, chr, new Item(MOUSE, (short)0, (short)1),
                monster.getPosition(), false, false);
    }
    public static String status(Character chr) {
        synchronized (chr) {
            PcCafeState state = chr.getPcCafeState();
            state.refresh(Instant.now(), CONFIG);
            return "Cafe coins: #b" + state.balance() + "#k\r\nEarned this week: " + state.earned() + "/" + CONFIG.weeklyCap()
                    + "\r\nHunting progress: " + state.gauge() + "/" + CONFIG.killsPerCoin()
                    + " kills per coin\r\nWeekly reset: Monday 00:00 " + CONFIG.resetZone()
                    + "\r\nIn solo Premium Road: " + CONFIG.expMultiplier() + "x monster EXP, " + CONFIG.dropMultiplier()
                    + "x regular drops. Coins and mice require monsters no more than " + CONFIG.maxLevelDifference()
                    + " levels below you. Bonuses do not affect the party quest.\r\nOne Mouse = " + CONFIG.coinsPerMouse()
                    + " coin(s). Coins survive the weekly reset. Limits are per character.";
        }
    }
    public static String welcome(Character chr) {
        if (!CONFIG.enabled() || chr.getMapId() != HUB) return "The cafe rewards are unavailable here.";
        synchronized (chr) {
            PcCafeState state = chr.getPcCafeState(); state.refresh(Instant.now(), CONFIG);
            int coins = state.welcome(CONFIG);
            return (coins > 0 ? "Welcome! Here are " + coins + " starter coins.\r\n\r\n" : "Welcome back!\r\n\r\n") + status(chr);
        }
    }
    public static String enter(Client client, int selection) {
        Character chr = client.getPlayer();
        if (!CONFIG.enabled() || chr.getMapId() != HUB || chr.getEventInstance() != null || !chr.isAlive()
                || selection < 0 || selection >= ROADS.size()) return "Please choose a hunting ground at the cafe Computer.";
        var map = client.getChannelServer().getMapFactory().getMap(ROADS.get(selection).map());
        if (map == null || map.getPortal(0) == null) return "This hunting ground is unavailable. Please try another.";
        chr.changeMap(map, map.getPortal(0));
        return "";
    }
    public static String exchangeMice(Client client, int quantity) {
        if (quantity < 1 || quantity > 1000) return "Choose between 1 and 1,000 mice.";
        Character chr = client.getPlayer();
        if (!CONFIG.enabled() || chr.getMapId() != HUB || !client.tryacquireClient()) return "Please try again at Billy.";
        try {
            synchronized (chr) {
                Inventory inv = chr.getInventory(InventoryType.ETC); inv.lockInventory();
                try {
                    PcCafeState state = chr.getPcCafeState(); state.refresh(Instant.now(), CONFIG);
                    long coins = (long)quantity * CONFIG.coinsPerMouse();
                    if (inv.countById(MOUSE) < quantity) return "You do not have that many mice.";
                    if (coins > state.available(CONFIG)) return "That exceeds your remaining weekly or balance allowance. Your mice were kept.";
                    InventoryManipulator.removeById(client, InventoryType.ETC, MOUSE, quantity, false, false);
                    state.credit((int)coins);
                    return "Exchanged " + quantity + " mice for " + coins + " coins.\r\n" + status(chr);
                } finally { inv.unlockInventory(); }
            }
        } finally { client.releaseClient(); }
    }
    public static String buy(Client client, int selection) {
        Character chr = client.getPlayer();
        if (!CONFIG.enabled() || chr.getMapId() != HUB || selection < 0 || selection >= REWARDS.size()
                || !client.tryacquireClient()) return "Please choose an item at the cafe Vending Machine.";
        try {
            synchronized (chr) {
                Reward reward = REWARDS.get(selection);
                Inventory inv = chr.getInventory(ItemConstants.getInventoryType(reward.item()));
                Inventory equipped = chr.getInventory(InventoryType.EQUIPPED);
                equipped.lockInventory(); inv.lockInventory();
                try {
                    PcCafeState state = chr.getPcCafeState(); state.refresh(Instant.now(), CONFIG);
                    ItemInformationProvider ii = ItemInformationProvider.getInstance();
                    if (ii.getItemData(reward.item()) == null || inv.isFull()
                            || reward.quantity() > ii.getSlotMax(client, reward.item())
                            || (ii.isPickupRestricted(reward.item()) && chr.haveItemWithId(reward.item(), true)))
                        return "Please leave one free slot in the correct inventory and check for one-of-a-kind items.";
                    Item item = ItemConstants.isEquipment(reward.item()) ? ii.getEquipById(reward.item())
                            : new Item(reward.item(), (short)0, (short)reward.quantity());
                    if (item == null) return "This item is currently unavailable.";
                    if (reward.currency().equals("COINS")) {
                        if (!state.purchase(reward.item(), reward.price(), reward.weeklyLimit(),
                                () -> InventoryManipulator.addFromDrop(client, item, true)))
                            return "Purchase unsuccessful: check your coins, weekly limit and inventory. No coins were spent.";
                    } else {
                        if (!chr.trySpendMeso(reward.price())) return "You do not have enough mesos.";
                        boolean added = false;
                        try { added = InventoryManipulator.addFromDrop(client, item, true); }
                        finally { if (!added) chr.gainMeso(reward.price(), false); }
                        if (!added) return "The item could not be delivered. Your mesos were refunded.";
                    }
                    return "Purchase complete!\r\n" + status(chr);
                } finally { inv.unlockInventory(); equipped.unlockInventory(); }
            }
        } finally { client.releaseClient(); }
    }
    private static List<Reward> loadRewards() {
        List<Reward> rewards = new ArrayList<>(); Set<String> keys = new HashSet<>();
        try {
            for (String row : Files.readAllLines(Path.of("scripts/npc/data/pc-cafe-rewards.tsv"))) {
                if (row.isBlank() || row.startsWith("#")) continue;
                String[] f = row.split("\\|", -1);
                if (f.length != 5) throw new IllegalArgumentException("Invalid cafe reward: " + row);
                Reward r = new Reward(f[0],Integer.parseInt(f[1]),Integer.parseInt(f[2]),Integer.parseInt(f[3]),Integer.parseInt(f[4]));
                if ((!r.currency().equals("COINS") && !r.currency().equals("MESOS")) || r.item() < 1000000
                        || r.item() >= 5000000 || r.quantity() < 1 || r.quantity() > 32767 || r.price() < 1
                        || r.weeklyLimit() < 0 || (r.currency().equals("MESOS") && r.weeklyLimit() != 0)
                        || !keys.add(r.currency()+":"+r.item())) throw new IllegalArgumentException("Invalid cafe reward: " + row);
                rewards.add(r);
            }
        } catch (IOException e) { throw new IllegalStateException("Cannot load PC Cafe rewards", e); }
        return List.copyOf(rewards);
    }
}
