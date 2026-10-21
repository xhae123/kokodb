package benchmark;

import kokodb.Database;
import kotlin.Unit;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Measures one INSERT transaction and verifies its complete contents outside the timed interval. */
public final class InsertBenchmark {
    private static final com.sun.management.ThreadMXBean ALLOCATION =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        ALLOCATION.setThreadAllocatedMemoryEnabled(true);
        System.out.println("# java=" + System.getProperty("java.version") + ",filesystem=" + Files.getFileStore(directory).type());
        System.out.println("mode,key_type,rows,round,elapsed_ms,allocated_bytes,retained_heap_delta,wal_bytes");
        for (int warm = 0; warm < 4; warm++) for (boolean text : new boolean[]{false, true}) {
            measure(directory, "memory", text, 1000, -warm - 1);
        }
        for (String mode : new String[]{"memory", "file"}) for (boolean text : new boolean[]{false, true}) {
            for (int rows : new int[]{1000, 4000, 16000}) for (int round = 1; round <= 3; round++) {
                measure(directory, mode, text, rows, round);
            }
        }
    }

    private static void measure(Path directory, String mode, boolean text, int size, int round) throws Exception {
        Path path = directory.resolve(mode + "-" + text + "-" + size + "-" + round + ".koko");
        try (Database db = mode.equals("memory") ? Database.Companion.inMemory(InsertBenchmark.class.getClassLoader())
                : Database.Companion.open(path, InsertBenchmark.class.getClassLoader())) {
            db.invoke("CREATE TABLE bench (id " + (text ? "TEXT" : "INT") + " PRIMARY KEY, value INT)", Map.of());
            long heapBefore = heap();
            long allocated = allocated();
            long started = System.nanoTime();
            db.transaction(tx -> {
                for (int i = 0; i < size; i++) {
                    // A fixed odd multiplier gives unique, non-monotonic integer keys.
                    int key = i * -1640531527;
                    if (tx.invoke("INSERT INTO bench VALUES (:id, :value)", Map.of("id", text ? "key-" + key : key, "value", i)) != 1) {
                        throw new AssertionError("Affected row count");
                    }
                }
                return Unit.INSTANCE;
            });
            double elapsed = (System.nanoTime() - started) / 1e6;
            long allocation = allocated() - allocated;
            long retained = heap() - heapBefore;
            verify(db, text, size);
            if (round > 0) System.out.printf(Locale.ROOT, "%s,%s,%d,%d,%.3f,%d,%d,%d%n", mode, text ? "text" : "int", size,
                round, elapsed, allocation, retained, mode.equals("file") ? Files.size(path.resolveSibling(path.getFileName() + ".wal")) : 0);
        }
        if (mode.equals("file")) try (Database recovered = Database.Companion.open(path, InsertBenchmark.class.getClassLoader())) {
            verify(recovered, text, size);
        }
    }

    private static void verify(Database db, boolean text, int size) {
        var rows = db.query("SELECT * FROM bench", Map.of());
        if (rows.size() != size) throw new AssertionError("Row count");
        var actual = new HashMap<Object, Integer>();
        for (var row : rows) if (actual.put(text ? row.getString("id") : row.getInt("id"), row.getInt("value")) != null) {
            throw new AssertionError("Duplicate key");
        }
        for (int i = 0; i < size; i++) {
            Object key = text ? "key-" + (i * -1640531527) : i * -1640531527;
            if (!Integer.valueOf(i).equals(actual.get(key))) throw new AssertionError("Incorrect value");
        }
    }

    private static long allocated() {
        return ALLOCATION.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    private static long heap() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
