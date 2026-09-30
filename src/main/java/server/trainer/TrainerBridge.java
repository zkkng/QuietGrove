package server.trainer;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Explicit private-LAN playtest bridge. No listener until an allowlisted player requests pairing. */
public final class TrainerBridge {
    private static final Logger log = LoggerFactory.getLogger(TrainerBridge.class);
    private static final int MAX_BODY = 4096;
    private record Budget(long minute, int count) {}
    private static final Map<String, Budget> pairBudget = new HashMap<>();
    private TrainerBridge() {}
    public static void start(TrainerService service) throws Exception {
        String bind = System.getProperty("solo.trainer.bind", "127.0.0.1");
        int port = Integer.parseInt(System.getProperty("solo.trainer.port", "8488"));
        InetAddress address = InetAddress.getByName(bind);
        if (!address.isLoopbackAddress() && !address.isSiteLocalAddress()) throw new IllegalArgumentException("Trainer must bind explicit private LAN/loopback address.");
        HttpServer server = HttpServer.create(new InetSocketAddress(address, port), 8);
        server.setExecutor(new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), r -> {
            Thread t = new Thread(r, "Own-Server-Trainer-Bridge"); t.setDaemon(true); return t;
        }, new ThreadPoolExecutor.AbortPolicy()));
        server.createContext("/v2/", exchange -> handle(service, exchange));
        server.createContext("/v3/", exchange -> handle(service, exchange)); server.start();
        log.info("Trainer bridge listening on {}:{}", address.getHostAddress(), port);
    }
    private static synchronized boolean pairAllowed(String ip) {
        long minute = System.currentTimeMillis() / 60_000;
        pairBudget.entrySet().removeIf(e -> e.getValue().minute != minute);
        Budget old = pairBudget.get(ip);
        if (old == null && pairBudget.size() >= 128) return false;
        int count = old == null ? 1 : old.count + 1;
        pairBudget.put(ip, new Budget(minute, count)); return count <= 5;
    }
    private static void handle(TrainerService service, HttpExchange e) throws IOException {
        try {
            e.getResponseHeaders().set("Cache-Control", "no-store");
            if (!e.getRequestMethod().equals("POST") || e.getRequestURI().getRawQuery() != null)
                throw new IllegalArgumentException("POST only; no URL credentials/query.");
            String type = e.getRequestHeaders().getFirst("Content-Type");
            if (type == null || !type.startsWith("application/x-www-form-urlencoded")) throw new IllegalArgumentException("Unsupported body type.");
            boolean profile = e.getRequestURI().getPath().equals("/v3/profile") || e.getRequestURI().getPath().equals("/v3/profileValidate");
            int maximum=profile ? 8192 : MAX_BODY;
            byte[] bytes = e.getRequestBody().readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IllegalArgumentException("Request too large.");
            Map<String, String> fields = decode(new String(bytes, StandardCharsets.UTF_8), profile ? 2048 : 256);
            Map<String, String> result;
            String path = e.getRequestURI().getPath();
            boolean candidate = path.startsWith("/v3/");
            String operation = path.length() >= 4 ? path.substring(4) : "";
            if (operation.equals("attach")) {
                if (!fields.isEmpty()) throw new IllegalArgumentException("Attach takes no fields.");
                result = service.attach(e.getRemoteAddress().getAddress().getHostAddress());
            } else if (operation.equals("pair") && !candidate) {
                if (!pairAllowed(e.getRemoteAddress().getAddress().getHostAddress())) throw new IllegalArgumentException("Pairing rate limit; wait one minute.");
                if (fields.size() != 1 || !fields.containsKey("code")) throw new IllegalArgumentException("Pairing requires one code.");
                result = service.pair(fields.get("code"));
            } else {
                String auth = e.getRequestHeaders().getFirst("Authorization");
                if (auth == null || !auth.startsWith("Bearer ") || auth.length() != 55) throw new IllegalArgumentException("Not paired.");
                if (!Set.of("regenoptions", "inspect", "pickupoptions", "profile", "profileValidate", "observations", "status", "configure", "autopotion", "powers", "loottools", "mobtools", "cleanse", "refill", "lootPulse", "off").contains(operation)) throw new IllegalArgumentException("Unknown operation.");
                if (!operation.equals("regenoptions") && !operation.equals("inspect") && !operation.equals("pickupoptions") && !operation.equals("profile") && !operation.equals("profileValidate") && !operation.equals("configure") && !operation.equals("autopotion") && !operation.equals("powers") && !operation.equals("loottools") && !operation.equals("refill") && !operation.equals("mobtools") && !fields.isEmpty()) throw new IllegalArgumentException("Unexpected fields.");
                result = service.request(auth.substring(7), operation, fields);
            }
            if (candidate) result.put("protocol", "SoloMapling-Trainer-v3");
            write(e, 200, result);
        } catch (IllegalArgumentException denied) { log.warn("Trainer request rejected path={} remote={} reason={}", e.getRequestURI().getPath(), e.getRemoteAddress(), denied.getMessage()); write(e, 400, Map.of("error", denied.getMessage() == null ? "Invalid request" : denied.getMessage())); }
        catch (Exception failure) { log.error("Trainer request failed path={} remote={}", e.getRequestURI().getPath(), e.getRemoteAddress(), failure); write(e, 503, Map.of("error", "Trainer unavailable; no powers enabled by this request.")); }
        finally { e.close(); }
    }
    public static Map<String, String> decode(String text) { return decode(text,256); }
    static Map<String,String> decode(String text,int valueLimit) {
        if (text.length() > (valueLimit > 256 ? 8192 : MAX_BODY)) throw new IllegalArgumentException("Body too large.");
        Map<String, String> result = new LinkedHashMap<>();
        if (text.isEmpty()) return result;
        for (String field : text.split("&", -1)) {
            int split = field.indexOf('='); if (split < 1) throw new IllegalArgumentException("Malformed field.");
            String key = URLDecoder.decode(field.substring(0, split), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(field.substring(split + 1), StandardCharsets.UTF_8);
            if (key.length() > 32 || value.length() > valueLimit || result.putIfAbsent(key, value) != null || result.size() > 24)
                throw new IllegalArgumentException("Duplicate/oversized/too many fields.");
        }
        return result;
    }
    private static void write(HttpExchange e, int code, Map<String, String> fields) throws IOException {
        StringJoiner text = new StringJoiner("&");
        fields.forEach((k, v) -> text.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        byte[] body = text.toString().getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        e.sendResponseHeaders(code, body.length); e.getResponseBody().write(body);
    }
}
