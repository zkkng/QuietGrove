package server.pccafe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Properties;

public record PcCafeConfig(boolean enabled, ZoneId resetZone, int weeklyCap, int maxBalance,
                           int welcomeCoins, int killsPerCoin, double mouseChance, int coinsPerMouse,
                           double expMultiplier, double dropMultiplier, int maxLevelDifference) {
    public PcCafeConfig {
        if (weeklyCap < 1 || maxBalance < weeklyCap || welcomeCoins < 0 || welcomeCoins > maxBalance
                || killsPerCoin < 1 || coinsPerMouse < 1 || coinsPerMouse > weeklyCap
                || !Double.isFinite(mouseChance) || mouseChance < 0 || mouseChance > 1
                || !Double.isFinite(expMultiplier) || expMultiplier < 1 || expMultiplier > 3
                || !Double.isFinite(dropMultiplier) || dropMultiplier < 1 || dropMultiplier > 3
                || maxLevelDifference < 0 || resetZone == null) throw new IllegalArgumentException("Invalid PC Cafe configuration");
    }
    public static PcCafeConfig load(Path path) {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(path)) { p.load(reader); }
        catch (IOException e) { throw new IllegalStateException("Cannot load " + path, e); }
        return new PcCafeConfig(Boolean.parseBoolean(p.getProperty("enabled")), ZoneId.of(p.getProperty("resetZone")),
                number(p,"weeklyCoinCap"), number(p,"maxCoinBalance"), number(p,"welcomeCoins"),
                number(p,"killsPerCoin"), decimal(p,"mouseDropChance"), number(p,"coinsPerMouse"),
                decimal(p,"expMultiplier"), decimal(p,"dropMultiplier"), number(p,"maxLevelDifference"));
    }
    private static int number(Properties p, String key) { return Integer.parseInt(p.getProperty(key)); }
    private static double decimal(Properties p, String key) { return Double.parseDouble(p.getProperty(key)); }
}
