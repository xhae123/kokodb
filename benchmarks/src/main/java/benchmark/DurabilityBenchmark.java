package benchmark;

import kokodb.Database;
import kokodb.Row;
import kotlin.Unit;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32C;

public final class DurabilityBenchmark {
    private static final com.sun.management.ThreadMXBean ALLOCATION =
        ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
            && bean.isThreadAllocatedMemorySupported() ? bean : null;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length != 2 || (!args[1].equals("full") && !args[1].equals("smoke"))) {
            throw new IllegalArgumentException("Expected existing-directory and full or smoke");
        }
        if (ALLOCATION != null && !ALLOCATION.isThreadAllocatedMemoryEnabled()) {
            ALLOCATION.setThreadAllocatedMemoryEnabled(true);
        }
        Path directory = Path.of(args[0]);
        boolean smoke = args[1].equals("smoke");
        System.out.println("# java=" + System.getProperty("java.version")
            + ",filesystem=" + Files.getFileStore(directory).type()
            + ",flags=" + ManagementFactory.getRuntimeMXBean().getInputArguments());
        System.out.println("mode,rows,round,commits,insert_ns_per_row,allocated_bytes_per_row,wal_bytes,"
            + "wal_reopen_ms,checkpoint_ms,snapshot_bytes,snapshot_reopen_ms");
        for (int size : smoke ? new int[]{10} : new int[]{100, 1000}) {
            for (String mode : new String[]{"autocommit", "batch"}) {
                for (int round = smoke ? 0 : -1; round < (smoke ? 1 : 3); round++) {
                    measure(directory.resolve(mode + "-" + size + "-" + round + ".koko"), mode, size, round);
                }
            }
        }
    }

    private static Database open(Path path) {
        return Database.Companion.open(path, DurabilityBenchmark.class.getClassLoader());
    }

    private static void measure(Path path, String mode, int size, int round) throws Exception {
        long elapsed;
        long allocation;
        long allocated;
        try (Database db = open(path)) {
            db.execute("CREATE TABLE bench (id INT PRIMARY KEY, name TEXT)", Map.of());
            db.checkpoint();
            allocation = allocated();
            long start = System.nanoTime();
            if (mode.equals("batch")) {
                db.transaction(tx -> {
                    for (int i = 0; i < size; i++) {
                        if (tx.execute("INSERT INTO bench VALUES (:id, :name)",
                            Map.of("id", i, "name", "row" + i)) != 1) throw new AssertionError("INSERT count");
                    }
                    return Unit.INSTANCE;
                });
            } else {
                for (int i = 0; i < size; i++) {
                    if (db.execute("INSERT INTO bench VALUES (:id, :name)",
                        Map.of("id", i, "name", "row" + i)) != 1) throw new AssertionError("INSERT count");
                }
            }
            elapsed = System.nanoTime() - start;
            allocated = allocated();
            verify(db, size);
        }
        Path wal = path.resolveSibling(path.getFileName() + ".wal");
        byte[] bytes = Files.readAllBytes(wal);
        int commits = mode.equals("batch") ? 1 : size;
        verifyFrames(bytes, commits);
        long start = System.nanoTime();
        Database replayed = open(path);
        double walReopen = (System.nanoTime() - start) / 1e6;
        double checkpoint;
        try (replayed) {
            verify(replayed, size);
            start = System.nanoTime();
            replayed.checkpoint();
            checkpoint = (System.nanoTime() - start) / 1e6;
        }
        if (Files.size(wal) != 40) throw new AssertionError("Checkpoint did not reset WAL");
        start = System.nanoTime();
        Database restored = open(path);
        double snapshotReopen = (System.nanoTime() - start) / 1e6;
        try (restored) { verify(restored, size); }
        if (round >= 0) {
            double perRow = allocation < 0 || allocated < 0 ? Double.NaN : (allocated - allocation) / (double) size;
            System.out.printf(Locale.ROOT, "%s,%d,%d,%d,%.1f,%.1f,%d,%.3f,%.3f,%d,%.3f%n",
                mode, size, round + 1, commits, elapsed / (double) size, perRow, bytes.length - 40,
                walReopen, checkpoint, Files.size(path), snapshotReopen);
        }
    }

    private static void verify(Database db, int size) {
        var rows = db.query("SELECT * FROM bench", Map.of());
        if (rows.size() != size) throw new AssertionError("Recovered row count");
        var seen = new HashSet<Integer>();
        for (Row row : rows) {
            int id = row.getInt("id");
            if (id < 0 || id >= size || !seen.add(id) || !row.getString("name").equals("row" + id)) {
                throw new AssertionError("Recovered row content");
            }
        }
    }

    private static void verifyFrames(byte[] bytes, int expected) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long sequence = buffer.getLong(28);
        int position = 40;
        int count = 0;
        while (position < bytes.length) {
            int length = buffer.getInt(position + 4);
            if (buffer.getInt(position) != 0x4b545831 || length < 36 || length > bytes.length - position
                || buffer.getLong(position + 8) != ++sequence || buffer.getInt(position + 16) != length - 32
                || buffer.getInt(position + length - 8) != 0x434d4954
                || buffer.getInt(position + length - 4) != length) {
                throw new AssertionError("WAL framing");
            }
            CRC32C crc = new CRC32C();
            crc.update(bytes, position, length - 12);
            if ((int) crc.getValue() != buffer.getInt(position + length - 12)) throw new AssertionError("WAL checksum");
            position += length;
            count++;
        }
        if (count != expected) throw new AssertionError("WAL commit count");
    }

    private static long allocated() {
        return ALLOCATION == null ? -1 : ALLOCATION.getThreadAllocatedBytes(Thread.currentThread().getId());
    }
}
