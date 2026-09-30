package server.statistics;

import java.io.*;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Immutable, deterministic wire batch; only the background worker creates these. */
public record StatisticsBatch(String epoch, UUID producer, UUID boot, long sequence,
                              long firstAt, long lastAt, List<StatisticsAccumulator.Delta> deltas) {
    public static final int VERSION = 1, MAX_BYTES = 1_048_576, MAX_ROWS = 1000;
    public StatisticsBatch {
        if (epoch == null || !epoch.matches("[A-Za-z0-9_.-]{1,64}") || producer == null || boot == null
                || sequence < 1 || firstAt < 0 || lastAt < firstAt || deltas == null
                || deltas.isEmpty() || deltas.size() > MAX_ROWS)
            throw new IllegalArgumentException("Invalid statistics batch");
        deltas = List.copyOf(deltas);
        Set<StatisticsAccumulator.Key> unique = new HashSet<>();
        for (var delta : deltas) if (!unique.add(delta.key())) throw new IllegalArgumentException("Duplicate delta key");
    }

    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(VERSION); out.writeUTF(epoch);
            out.writeLong(producer.getMostSignificantBits()); out.writeLong(producer.getLeastSignificantBits());
            out.writeLong(boot.getMostSignificantBits()); out.writeLong(boot.getLeastSignificantBits());
            out.writeLong(sequence); out.writeLong(firstAt); out.writeLong(lastAt); out.writeInt(deltas.size());
            for (var delta : deltas) {
                var key = delta.key();
                out.writeInt(key.metric()); out.writeInt(key.projection()); out.writeInt(key.world());
                out.writeInt(key.population()); out.writeInt(key.assistance()); out.writeInt(key.entity());
                out.writeInt(key.region()); out.writeInt(key.reason()); out.writeInt(key.method()); out.writeLong(key.day());
                out.writeUTF(delta.value().toString());
            }
            out.flush();
            byte[] result = bytes.toByteArray();
            if (result.length > MAX_BYTES) throw new IllegalArgumentException("Batch byte limit");
            return result;
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }

    public static StatisticsBatch decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IOException("Invalid batch size");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != VERSION) throw new IOException("Unsupported batch version");
            String epoch = in.readUTF();
            UUID producer = new UUID(in.readLong(), in.readLong()), boot = new UUID(in.readLong(), in.readLong());
            long sequence = in.readLong(), firstAt = in.readLong(), lastAt = in.readLong();
            int count = in.readInt();
            if (count < 1 || count > MAX_ROWS) throw new IOException("Invalid row count");
            List<StatisticsAccumulator.Delta> deltas = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                var key = new StatisticsAccumulator.Key(in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                        in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readLong());
                String value = in.readUTF();
                if (!value.matches("[1-9][0-9]{0,37}")) throw new IOException("Invalid integer delta");
                deltas.add(new StatisticsAccumulator.Delta(key, new BigInteger(value)));
            }
            if (in.available() != 0) throw new IOException("Unexpected batch trailing data");
            return new StatisticsBatch(epoch, producer, boot, sequence, firstAt, lastAt, deltas);
        } catch (IllegalArgumentException exception) { throw new IOException("Invalid batch content", exception); }
    }

    public String checksum() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode())); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
