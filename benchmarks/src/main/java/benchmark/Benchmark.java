package benchmark;

import kokodb.Database;
import kokodb.DatabaseException;
import kokodb.Row;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;

public final class Benchmark {
    private static volatile Object retained;
    private static final com.sun.management.ThreadMXBean ALLOCATION =
        ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
            && bean.isThreadAllocatedMemorySupported() ? bean : null;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        String mode = args.length == 0 ? "crud" : args[0];
        if (ALLOCATION != null && !ALLOCATION.isThreadAllocatedMemoryEnabled()) {
            ALLOCATION.setThreadAllocatedMemoryEnabled(true);
        }
        if (mode.equals("empty")) return;
        if (mode.equals("open")) {
            retained = Database.Companion.inMemory(Benchmark.class.getClassLoader());
            return;
        }
        if (mode.equals("first-query")) {
            Database db = fixture(1);
            if (db.query("SELECT * FROM bench WHERE id = :id", Map.of("id", 0)).size() != 1) {
                throw new AssertionError("First-query result mismatch");
            }
            retained = db;
            return;
        }
        if (!mode.equals("crud") && !mode.equals("smoke")) {
            throw new IllegalArgumentException("Expected empty, open, first-query, crud, or smoke");
        }
        System.out.println("# java=" + System.getProperty("java.version")
            + ",os=" + System.getProperty("os.name") + ",arch=" + System.getProperty("os.arch")
            + ",flags=" + ManagementFactory.getRuntimeMXBean().getInputArguments());
        System.out.println("workload,rows,round,operations,ns_per_op,allocated_bytes_per_op,gc_count_delta");
        int[] sizes = mode.equals("smoke") ? new int[]{10} : new int[]{100, 1000, 5000};
        for (int size : sizes) {
            int warmups = mode.equals("smoke") ? 0 : 2;
            int rounds = mode.equals("smoke") ? 1 : 5;
            for (int round = -warmups; round < rounds; round++) {
                Database db = empty();
                final Database target = db;
                measure("insert", size, round, size, i -> {
                    if (target.execute("INSERT INTO bench VALUES (:id, :name)",
                        Map.of("id", i, "name", "row" + i)) != 1) throw new AssertionError("INSERT count");
                });
                Map<String, Object> key = Map.of("id", size / 2);
                measure("select_hit", size, round, 300, i -> {
                    List<Row> rows = target.query("SELECT * FROM bench WHERE id = :id", key);
                    if (rows.size() != 1 || rows.get(0).getInt("id") != size / 2) {
                        throw new AssertionError("SELECT result");
                    }
                });
                measure("select_miss", size, round, 300, i -> {
                    if (!target.query("SELECT * FROM bench WHERE id = :id",
                        Map.of("id", size + 1)).isEmpty()) throw new AssertionError("SELECT miss");
                });
                measure("update_one", size, round, 50, i -> {
                    if (target.execute("UPDATE bench SET name = :name WHERE id = :id",
                        Map.of("name", "updated" + i, "id", size / 2)) != 1) {
                        throw new AssertionError("UPDATE count");
                    }
                });
                measure("update_key_collision", size, round, 20, i -> {
                    boolean rejected = false;
                    try {
                        target.execute("UPDATE bench SET id = 1 WHERE id = 0", Map.of());
                    } catch (DatabaseException expected) { rejected = true; }
                    if (!rejected) throw new AssertionError("Missing key collision");
                });
                int deletes = Math.min(20, size);
                measure("delete_one", size, round, deletes, i -> {
                    if (target.execute("DELETE FROM bench WHERE id = :id", Map.of("id", i)) != 1) {
                        throw new AssertionError("DELETE count");
                    }
                });
                if (target.query("SELECT * FROM bench", Map.of()).size() != size - deletes) {
                    throw new AssertionError("Final row count");
                }
            }
        }
    }

    private static Database empty() {
        Database db = Database.Companion.inMemory(Benchmark.class.getClassLoader());
        db.execute("CREATE TABLE bench (id INT PRIMARY KEY, name TEXT)", Map.of());
        return db;
    }

    private static Database fixture(int size) {
        Database db = empty();
        for (int i = 0; i < size; i++) {
            db.execute("INSERT INTO bench VALUES (:id, :name)", Map.of("id", i, "name", "row" + i));
        }
        return db;
    }

    private static void measure(String name, int size, int round, int operations, IntConsumer operation) {
        long gc = gcCount();
        long allocation = allocated();
        long start = System.nanoTime();
        for (int i = 0; i < operations; i++) operation.accept(i);
        long elapsed = System.nanoTime() - start;
        long bytes = allocated();
        long gcDelta = gcCount() - gc;
        if (round >= 0) {
            double perOp = allocation < 0 || bytes < 0 ? Double.NaN : (bytes - allocation) / (double) operations;
            System.out.printf(Locale.ROOT, "%s,%d,%d,%d,%.1f,%.1f,%d%n",
                name, size, round + 1, operations, elapsed / (double) operations, perOp, gcDelta);
        }
    }

    private static long allocated() {
        return ALLOCATION == null ? -1 : ALLOCATION.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
    }

}
