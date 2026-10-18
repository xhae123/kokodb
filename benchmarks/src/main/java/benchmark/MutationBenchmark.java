package benchmark;

import kokodb.Database;
import kotlin.Unit;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Measures separately acknowledged point mutations against identical prepopulated tables. */
public final class MutationBenchmark {
    private static final com.sun.management.ThreadMXBean ALLOCATION =
        ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
            && bean.isThreadAllocatedMemorySupported() ? bean : null;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected an existing directory");
        if (ALLOCATION != null && !ALLOCATION.isThreadAllocatedMemoryEnabled()) ALLOCATION.setThreadAllocatedMemoryEnabled(true);
        Path directory = Path.of(args[0]);
        System.out.println("# java=" + System.getProperty("java.version") + ",filesystem=" + Files.getFileStore(directory).type());
        System.out.println("rows,operation,operations,ns_per_operation,allocated_bytes_per_operation,wal_bytes_per_operation,reopen_ms");
        for (int size : new int[]{100, 1000, 10000}) measure(directory.resolve("mutations-" + size + ".koko"), size);
    }

    private static Database open(Path path) {
        return Database.Companion.open(path, MutationBenchmark.class.getClassLoader());
    }

    private static void measure(Path path, int size) throws Exception {
        Map<Integer, String> expected = new HashMap<>();
        try (Database db = open(path)) {
            db.invoke("CREATE TABLE bench (id INT PRIMARY KEY, name TEXT)", Map.of());
            db.transaction(tx -> {
                for (int i = 0; i < size; i++) {
                    tx.invoke("INSERT INTO bench VALUES (:id, :name)", Map.of("id", i, "name", "row" + i));
                    expected.put(i, "row" + i);
                }
                return Unit.INSTANCE;
            });
            db.checkpoint();
        }
        Path wal = path.resolveSibling(path.getFileName() + ".wal");
        for (String operation : new String[]{"update", "key_move", "delete"}) {
            long before = Files.size(wal);
            long elapsed;
            long allocation;
            try (Database db = open(path)) {
                long allocated = allocated();
                long started = System.nanoTime();
                for (int i = 0; i < 20; i++) {
                    int id = operation.equals("update") ? i : operation.equals("key_move") ? i + 20 : size - i - 1;
                    int count = switch (operation) {
                        case "update" -> db.invoke("UPDATE bench SET name = :name WHERE id = :id", Map.of("id", id, "name", "new" + i));
                        case "key_move" -> db.invoke("UPDATE bench SET id = :next WHERE id = :id", Map.of("id", id, "next", size + i));
                        default -> db.invoke("DELETE FROM bench WHERE id = :id", Map.of("id", id));
                    };
                    if (count != 1) throw new AssertionError("Affected count");
                }
                elapsed = System.nanoTime() - started;
                allocation = allocated() - allocated;
            }
            for (int i = 0; i < 20; i++) {
                if (operation.equals("update")) expected.put(i, "new" + i);
                else if (operation.equals("key_move")) expected.put(size + i, expected.remove(i + 20));
                else expected.remove(size - i - 1);
            }
            long started = System.nanoTime();
            Database recovered = open(path);
            double reopen = (System.nanoTime() - started) / 1e6;
            try (recovered) {
                var actual = new HashMap<Integer, String>();
                for (var row : recovered.query("SELECT * FROM bench", Map.of())) {
                    if (actual.put(row.getInt("id"), row.getString("name")) != null) throw new AssertionError("Duplicate key");
                }
                if (!expected.equals(actual)) throw new AssertionError("Recovered mutation differs");
            }
            System.out.printf(Locale.ROOT, "%d,%s,20,%.1f,%.1f,%.1f,%.3f%n", size, operation,
                elapsed / 20.0, ALLOCATION == null ? Double.NaN : allocation / 20.0,
                (Files.size(wal) - before) / 20.0, reopen);
        }
    }

    private static long allocated() {
        return ALLOCATION == null ? -1 : ALLOCATION.getThreadAllocatedBytes(Thread.currentThread().getId());
    }
}
