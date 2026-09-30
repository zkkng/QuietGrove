package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Client;
import soloMapling.server.SoloMaplingConstants.GameConstants;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Single source of truth for the shared headless bot {@link Client}.
 */
public class BotClientHandler {

    private static volatile Client botClient = null;
    private static final ConcurrentMap<Long, Client> channelClients = new ConcurrentHashMap<>();

    /**
     * Constructs the one shared headless bot client. Idempotent — safe to call
     * more than once (only the first call builds the instance). Must run after
     * the channels exist, since the client reports {@code WORLD_SCANIA} /
     * {@code CHANNEL_1} for routing.
     */
    public static synchronized void initHeadlessBotClient() {
        if (botClient == null) {
            botClient = new BotClient(GameConstants.WORLD_SCANIA, GameConstants.CHANNEL_1);
            channelClients.put(key(GameConstants.WORLD_SCANIA, GameConstants.CHANNEL_1), botClient);
        }
    }

    /** The shared headless client every bot routes through. Null until {@link #initHeadlessBotClient()} runs. */
    public static Client getBotClient() {
        return botClient;
    }

    /** Headless routing identity for a bot whose actual map is on another channel. */
    public static Client getBotClient(int world, int channel) {
        if (channel <= 0 || world < 0) throw new IllegalArgumentException("bot route");
        return channelClients.computeIfAbsent(key(world, channel), ignored -> new BotClient(world, channel));
    }

    private static long key(int world, int channel) {
        return ((long) world << 32) | (channel & 0xffffffffL);
    }
}
