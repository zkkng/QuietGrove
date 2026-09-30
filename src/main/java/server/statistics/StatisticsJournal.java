package server.statistics;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.zip.CRC32C;

/**
 * One worker, exclusive file lock, bounded spool, streaming recovery.
 * Incomplete physical tail is truncated on reopen. Checksum corruption is rejected.
 * WS1 does not prune/rotate this spool; capacity becomes an explicit collection gate.
 */
public final class StatisticsJournal implements AutoCloseable {
    private static final int MAGIC = 0x57533142, HEADER_BYTES = 12;
    @FunctionalInterface public interface Visitor { void accept(StatisticsBatch batch) throws IOException; }
    private final FileChannel channel;
    private final FileLock lock;
    private final long byteLimit;
    private boolean poisoned, closed;
    private long repairedTailBytes;

    public StatisticsJournal(Path path, long byteLimit) throws IOException {
        if (byteLimit < 128 || byteLimit > 2L * 1024 * 1024 * 1024) throw new IllegalArgumentException("Spool budget");
        this.byteLimit = byteLimit;
        Path parent = path.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("Journal already owned");
            lock = acquired;
            if (channel.size() > byteLimit) throw new IOException("Existing journal exceeds budget");
            scan(batch -> {}, true);
        } catch (IOException | RuntimeException exception) {
            if (acquired != null) acquired.release();
            channel.close();
            throw exception;
        }
    }

    public void appendAndSync(StatisticsBatch batch) throws IOException {
        ensureOpen();
        byte[] payload = batch.encode();
        if (channel.size() + HEADER_BYTES + payload.length > byteLimit) throw new IOException("Statistics spool full");
        CRC32C crc = new CRC32C(); crc.update(payload);
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).putInt(MAGIC).putInt(payload.length).putInt((int) crc.getValue());
        header.flip();
        try {
            channel.position(channel.size());
            writeFully(header); writeFully(ByteBuffer.wrap(payload));
            channel.force(true);
        } catch (IOException exception) { poisoned = true; throw exception; }
    }

    /** Visitor receives at most one decoded 1MiB batch at a time. */
    public void replay(Visitor visitor) throws IOException { ensureOpen(); scan(visitor, false); }

    private void scan(Visitor visitor, boolean repairTail) throws IOException {
        long validEnd = 0, size = channel.size();
        while (validEnd < size) {
            channel.position(validEnd);
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            if (!readFully(header)) { incompleteTail(validEnd, size, repairTail); return; }
            header.flip();
            if (header.getInt() != MAGIC) throw new IOException("Statistics journal magic mismatch at " + validEnd);
            int length = header.getInt(), expectedCrc = header.getInt();
            if (length < 1 || length > StatisticsBatch.MAX_BYTES) throw new IOException("Statistics journal length invalid");
            ByteBuffer payload = ByteBuffer.allocate(length);
            if (!readFully(payload)) { incompleteTail(validEnd, size, repairTail); return; }
            CRC32C crc = new CRC32C(); crc.update(payload.array());
            if ((int) crc.getValue() != expectedCrc) throw new IOException("Statistics journal checksum mismatch at " + validEnd);
            visitor.accept(StatisticsBatch.decode(payload.array()));
            validEnd += HEADER_BYTES + length;
        }
    }

    private void incompleteTail(long validEnd, long size, boolean repair) throws IOException {
        if (!repair) throw new IOException("Incomplete statistics journal tail");
        channel.truncate(validEnd); channel.force(true); repairedTailBytes += size - validEnd;
    }
    private boolean readFully(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) { if (channel.read(buffer) < 0) return false; }
        return true;
    }
    private void writeFully(ByteBuffer buffer) throws IOException { while (buffer.hasRemaining()) channel.write(buffer); }
    private void ensureOpen() throws IOException {
        if (closed || poisoned) throw new IOException("Statistics journal unavailable; reopen for recovery");
    }
    /** Only after every frame is acknowledged by the database. */
    public void resetAcknowledged() throws IOException { ensureOpen(); channel.truncate(0); channel.position(0); channel.force(true); }
    public long size() throws IOException { ensureOpen(); return channel.size(); }
    public long repairedTailBytes() { return repairedTailBytes; }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        try { lock.release(); } finally { channel.close(); }
    }
}
