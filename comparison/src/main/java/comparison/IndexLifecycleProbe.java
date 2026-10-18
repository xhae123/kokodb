package comparison;

import kokodb.Database;
import kotlin.Unit;
import java.lang.management.ManagementFactory;
import java.util.Map;

/** Exploratory first-read/build observations; steady-state comparisons belong to JMH. */
public final class IndexLifecycleProbe {
    public static void main(String[] args) {
        var bean = ManagementFactory.getThreadMXBean();
        var allocation = bean instanceof com.sun.management.ThreadMXBean supported
            && supported.isThreadAllocatedMemorySupported() ? supported : null;
        if (allocation != null && !allocation.isThreadAllocatedMemoryEnabled()) allocation.setThreadAllocatedMemoryEnabled(true);
        System.out.println("rows,round,phase,nanoseconds,allocated_bytes");
        for (int round = -2; round < 3; round++) {
            try (var db = Database.Companion.inMemory(IndexLifecycleProbe.class.getClassLoader())) {
                db.transaction(tx -> {
                    for (int id = 0; id < 10000; id++) tx.invoke("INSERT INTO benchmark_users VALUES (:id, :name)", Map.of("id", id, "name", "user" + id));
                    return Unit.INSTANCE;
                });
                var params = Map.<String, Object>of("id", 5000);
                for (String phase : new String[]{"first_scan", "build", "cached"}) {
                    long before = allocation == null ? 0 : allocation.getThreadAllocatedBytes(Thread.currentThread().getId());
                    long start = System.nanoTime();
                    var result = db.queryModels(BenchmarkUser.class, "SELECT id, name FROM benchmark_users WHERE id = :id", params);
                    long elapsed = System.nanoTime() - start;
                    long bytes = allocation == null ? -1 : allocation.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
                    if (result.size() != 1 || result.get(0).getId() != 5000 || !result.get(0).getName().equals("user5000")) throw new AssertionError("Lookup content");
                    if (round >= 0) System.out.printf("10000,%d,%s,%d,%d%n", round + 1, phase, elapsed, bytes);
                }
            }
        }
    }
}
