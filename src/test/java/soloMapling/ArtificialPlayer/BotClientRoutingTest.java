package soloMapling.ArtificialPlayer;

import client.Client;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class BotClientRoutingTest {
    @Test void eachVenueChannelHasOneDistinctHeadlessRoute() {
        BotClientHandler.initHeadlessBotClient();
        Client first = BotClientHandler.getBotClient(0, 1);
        Client second = BotClientHandler.getBotClient(0, 2);
        Client third = BotClientHandler.getBotClient(0, 3);
        assertSame(BotClientHandler.getBotClient(), first);
        assertSame(second, BotClientHandler.getBotClient(0, 2));
        assertNotSame(first, second);
        assertNotSame(second, third);
        assertEquals(1, first.getChannel());
        assertEquals(2, second.getChannel());
        assertEquals(3, third.getChannel());
        assertEquals(0, third.getWorld());
    }
}
