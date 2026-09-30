package server.trainer;

import client.Character;
import server.ItemInformationProvider;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.movement.LifeMovementFragment;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.DropGameBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.server.BotTiming;
import soloMapling.itemPool.ItemUtilities;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotEmote;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;

/** Reactions to committed, nearby trainer outcomes; never reads trainer toggle state. */
public final class TrainerBotReactions {
    private static final int SIGHT_X = 900;
    private static final int SIGHT_Y = 600;
    private static final long DEFRAME_COOLDOWN_MS = 24 * 60 * 60 * 1000L;
    private static final TrainerReactionPolicy conversation = new TrainerReactionPolicy();
    private static final TrainerObservationBudget observationBudget = new TrainerObservationBudget();
    private static final Map<Long, Long> lastDefame = new ConcurrentHashMap<>();
    private static final Map<Integer, AirMotion> airMotion = new ConcurrentHashMap<>();

    private static final class AirMotion {
        final TrainerFlightObservation observation = new TrainerFlightObservation();
        volatile long lastMs;
    }

    private TrainerBotReactions() { }

    /** Fresh absolute movement only: jumps/teleports/climbing must not reuse stale player coordinates. */
    public static void onAcceptedMovement(Character actor, MapleMap map, List<LifeMovementFragment> movements) {
        if (actor == null || map == null || isBot(actor) || actor.isHidden() || actor.getPosition() == null) return;
        long now = System.currentTimeMillis();
        if (!TrainerFlightObservation.hasUnexplainedAir(map.getFootholds(), map.getRopes(), map.isSwim(), movements)) {
            airMotion.remove(actor.getId());
            return;
        }
        if (airMotion.size() >= 4096) {
            airMotion.entrySet().removeIf(e -> now - e.getValue().lastMs > 60_000);
            if (airMotion.size() >= 4096 && !airMotion.containsKey(actor.getId())) return;
        }
        AirMotion motion = airMotion.computeIfAbsent(actor.getId(), id -> new AirMotion());
        Point nowAt = new Point(actor.getPosition());
        synchronized (motion) {
            motion.lastMs = now;
            if (!motion.observation.observe(map, nowAt, true, now)) return;
        }
        List<Character> nearby = witnesses(map, actor, nowAt);
        react(nearby, actor, actor, map, nowAt, TrainerReactionPolicy.Kind.FLIGHT, List.of(
                "Wait... are you floating?", "How are you staying up there?", "That's a long way to go without landing.",
                "I don't see a platform under you.", "Did you just glide across that whole gap?",
                "What was that movement?", "You've been hovering for a while...", "Okay, how did you cross that?"
        ), 8, false, 550, 0);
    }

    /** One FMA cast is one incident even if it affects dozens of monsters. */
    public static void onMapAttack(Character actor, MapleMap map, Point origin, int addedTargets) {
        if (addedTargets < 6 || map == null) return;
        List<Character> witnesses = witnesses(map, actor, origin);
        if (witnesses.isEmpty()) return;
        List<String> lines = addedTargets >= 20 ? List.of(
                "I was training here. Can you leave me something?", "You cleared everything before I could get a hit in.",
                "Great. Now I get to wait for the respawn.", "The whole map? Come on.",
                "There goes my pull...", "Guess I'm finding another channel.",
                "Leave the far side alone, at least.", "That's pretty rough on everyone trying to grind here."
        ) : List.of(
                "Save a few mobs for me?", "Those were my targets...", "Mind leaving this corner alone?",
                "I had that group lined up.", "How did you hit the ones over here?", "You're taking my whole pull.",
                "Can we split the map?", "I'm barely getting a swing in."
        );
        react(witnesses, actor, actor, map, origin, TrainerReactionPolicy.Kind.FMA,
                lines, 6, addedTargets >= 20, 550, 0);
    }

    public static void onMobRelocated(Character actor, MapleMap map, Point origin, int moved) {
        if (moved < 8 || map == null) return;
        List<Character> witnesses = witnesses(map, actor, origin);
        if (witnesses.isEmpty()) return;
        react(witnesses, actor, actor, map, origin, TrainerReactionPolicy.Kind.VAC, List.of(
                "My mobs just disappeared over to your side.", "I was about to hit that group!",
                "Could you leave my pull where it was?", "Why is everything piling up there?",
                "I chased that mob all this way...", "That was my corner of the map.",
                "There goes everything I was grinding on.", "At least leave something on this side."
        ), 6, moved >= 20, 700, 0);
    }

    /** Called only after the real pickup transaction marked the drop consumed. */
    public static void onCommittedPickup(Character actor, MapleMap map, MapItem drop, Point pickupFrom) {
        onCommittedPickup(actor, map, drop, pickupFrom, false);
    }

    /** A pet next to the drop explains a remote owner without proving a vac. */
    public static void onCommittedPickup(Character actor, MapleMap map, MapItem drop, Point pickupFrom,
                                         boolean petPickup) {
        if (actor == null || map == null || drop == null || drop.getMeso() > 0
                || !drop.isPickedUp() || drop.getCollectedByCharacterId() != actor.getId()
                || isBot(actor)) return;
        SocialStakeGame.onCommittedPickup(drop);
        if (drop.getVenueRoundId() != null) {
            try { if (TrainerVenueLedger.isPaidRound(drop.getVenueRoundId())) return; }
            catch (java.sql.SQLException unknown) { return; } // Unknown consent is not evidence of theft.
        }
        Character owner = map.getCharacterById(drop.getOwnerId());
        if (owner == null || owner == actor || !isBot(owner)) return;
        // The paid host deliberately gives away prizes; collecting one is the game.
        if (CharacterStorage.getBotById(owner.getId()) instanceof DropGameBot) return;
        int value = 0;
        try {
            Integer estimate = ItemUtilities.getItemMarketValue(drop.getItem());
            if (estimate != null) value = Math.max(0, estimate);
        } catch (RuntimeException ignored) { // Unpriced content is unknown, never zero-value evidence.
        }
        int itemId = drop.getItem().getItemId();
        boolean highLevelGear = itemId >= 1_000_000 && itemId < 2_000_000
                && ItemInformationProvider.getInstance().getEquipLevelReq(itemId) >= 100;
        boolean highValue = value >= 10_000_000 || highLevelGear
                || itemId == 2340000 || itemId == 2049100;
        String itemName = ItemInformationProvider.getInstance().getName(itemId);
        if (itemName == null || itemName.isBlank()) itemName = "item";
        boolean remote = !petPickup && actor.getPosition().distanceSq(pickupFrom) > 180L * 180L;
        boolean ownerSaw = !actor.isHidden() && actor.getMap() == map && visible(owner, pickupFrom)
                && visible(owner, actor.getPosition());
        UUID lossEvent = drop.getVenueAssetId() != null ? drop.getVenueAssetId()
                : UUID.nameUUIDFromBytes(("drop:" + map.getWorld() + ":" + map.getChannelServer().getId()
                + ":" + map.getId() + ":" + drop.getObjectId() + ":" + drop.getDropTime())
                .getBytes(StandardCharsets.UTF_8));
        TrainerSocialMemory.lostItem(lossEvent, owner, actor, itemId, ownerSaw, remote, highValue);
        List<String> lines;
        int emote;
        if (highValue && ownerSaw && remote) {
            lines = List.of("My " + itemName + "! You weren't even next to it!",
                    "That " + itemName + " was mine. Give it back.", "Seriously? You took my " + itemName + " from over there?",
                    "I worked for that " + itemName + ".", "You just pulled my " + itemName + " away!",
                    "Don't walk off with my " + itemName + ".", "I saw you grab that. It wasn't yours.",
                    "That's my " + itemName + " you're holding.");
            emote = 14;
        } else if (highValue) {
            lines = ownerSaw ? List.of("Wait, my " + itemName + "...", "I really wanted to keep that " + itemName + ".",
                    "Could I have my " + itemName + " back?", "You took the one thing I cared about.",
                    "That wasn't a giveaway...", "I was saving that " + itemName + ".", "Why take mine?",
                    "That " + itemName + " took me ages to get.")
                    : List.of("My " + itemName + " is gone...", "Where did my " + itemName + " go?",
                    "I swear my " + itemName + " was right here.", "Did anyone see what happened to my drop?",
                    "Oh no. I lost my " + itemName + ".", "Please tell me somebody still has my " + itemName + ".",
                    "I should've picked that up sooner.", "That was a really expensive mistake.");
            emote = 15;
        } else if (ownerSaw && remote) {
            lines = List.of("How did you get my drop from over there?", "That went straight to you?",
                    "Hey, you weren't even beside it.", "I saw that item jump across the screen.",
                    "Could you leave my drops alone?", "How am I supposed to pick anything up?",
                    "You pulled that right out from under me.", "I was going to grab that.");
            emote = 6;
        } else {
            lines = List.of("I was going for that drop.", "Aw, I wanted that one.", "A little quick on the pickup there.",
                    "Could you leave the next one?", "I didn't mean to give that away.", "That was mine...",
                    "Guess I was too slow.", "I was about to pick that up.");
            emote = 6;
        }
        react(List.of(owner), actor, ownerSaw ? actor : null, map, pickupFrom,
                TrainerReactionPolicy.Kind.LOOT, lines, emote, highValue && ownerSaw && remote, 450, itemId);
    }

    private static List<Character> witnesses(MapleMap map, Character actor, Point event) {
        List<Character> bots = new ArrayList<>();
        if (actor == null || actor.isHidden() || event == null || actor.getPosition() == null) return bots;
        for (Character candidate : map.getMapPlayers().values()) {
            if (candidate == actor || !isBot(candidate) || !visible(candidate, event)
                    || !visible(candidate, actor.getPosition())) continue;
            BotSM brain = CharacterStorage.getBotById(candidate.getId());
            // A grinder is precisely the person harmed by map-wide mob grief;
            // TrainingBot intentionally reports false for ambient chatter.
            if (brain != null && brain.getRunning()
                    && (brain instanceof TrainingBot || "VenueBot".equals(brain.getBotType())
                    || brain.isAvailableForAmbientActions())) bots.add(candidate);
        }
        bots.sort(Comparator.comparingDouble(b -> b.getPosition().distanceSq(event)));
        return bots;
    }

    private static boolean visible(Character bot, Point point) {
        if (bot == null || point == null || bot.getPosition() == null) return false;
        Point at = bot.getPosition();
        return Math.abs((long) at.x - point.x) <= SIGHT_X && Math.abs((long) at.y - point.y) <= SIGHT_Y;
    }

    private static void react(List<Character> candidates, Character actor, Character suspect, MapleMap map, Point event,
                              TrainerReactionPolicy.Kind kind, List<String> lines, int emote, boolean defame,
                              int delayMs, int itemId) {
        if (candidates.isEmpty()) return;
        Point eventPosition = new Point(event);
        long now = System.currentTimeMillis();
        // Capture the witnessed outcome now, not when a later speech callback happens to run.
        // Thirty nearby observers can remember, but the existing conversation budget still
        // permits at most one speaker. Item loss has its own idempotent committed receipt above.
        if (kind != TrainerReactionPolicy.Kind.LOOT && suspect != null
                && observationBudget.admit(map, actor.getId(), kind, now)) {
            TrainerSocialMemory.observedAll(candidates, suspect, kind, itemId);
        }
        var random = ThreadLocalRandom.current();
        var decision = conversation.reserve(map, actor.getId(), kind,
                candidates.stream().map(Character::getId).toList(), now, random);
        if (decision == null) return;
        Character bot = candidates.stream().filter(b -> b.getId() == decision.botId()).findFirst().orElseThrow();
        if (lastDefame.size() > 4096) lastDefame.entrySet().removeIf(e -> now - e.getValue() > 2 * DEFRAME_COOLDOWN_MS);
        long key = (((long) bot.getId()) << 32) ^ (suspect == null ? 0 : suspect.getId());
        BotTiming.after(delayMs + random.nextInt(650), () -> {
            BotSM brain = CharacterStorage.getBotById(bot.getId());
            if (bot.getMap() != map || (suspect != null && suspect.getMap() != map)
                    || !visible(bot, eventPosition) || brain == null || !brain.getRunning()) return;
            BotEmote(bot, emote);
            if (decision.speak()) BotSpeak(bot, conversation.line(map, lines, ThreadLocalRandom.current()));
            if (defame && suspect != null && visible(bot, suspect.getPosition())) {
                Long last = lastDefame.putIfAbsent(key, System.currentTimeMillis());
                long current = System.currentTimeMillis();
                if (last == null || (current - last >= DEFRAME_COOLDOWN_MS
                        && lastDefame.replace(key, last, current))) {
                    suspect.gainFame(-1, bot, 0);
                }
            }
        });
    }
}
