package comparison;

import kokodb.Database;
import kotlin.Unit;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms64m", "-Xmx256m", "-XX:-UsePerfData"})
public class InsertBatchBenchmark {
    @Param({"1000", "16000"}) public int rows;
    @Param({"int", "text"}) public String keyType;
    private Database db;

    @Setup(Level.Invocation)
    public void setup() {
        db = Database.Companion.inMemory(InsertBatchBenchmark.class.getClassLoader());
        db.invoke("CREATE TABLE insert_items (id " + (keyType.equals("int") ? "INT" : "TEXT") + " PRIMARY KEY, value INT)", Map.of());
    }

    @Benchmark
    public void insertTransaction() {
        db.transaction(tx -> {
            for (int i = 0; i < rows; i++) {
                int key = i * -1640531527;
                int count = tx.invoke("INSERT INTO insert_items VALUES (:id, :value)",
                    Map.of("id", keyType.equals("int") ? key : "key-" + key, "value", i));
                if (count != 1) throw new AssertionError("Affected row count");
            }
            return Unit.INSTANCE;
        });
    }

    @TearDown(Level.Invocation)
    public void verify() {
        try {
            var result = db.query("SELECT * FROM insert_items", Map.of());
            if (result.size() != rows) throw new AssertionError("Row count");
            var seen = new boolean[rows];
            for (var row : result) {
                int value = row.getInt("value");
                if (value < 0 || value >= rows || seen[value]) throw new AssertionError("Row identity");
                seen[value] = true;
                int expected = value * -1640531527;
                if (keyType.equals("int") ? row.getInt("id") != expected : !row.getString("id").equals("key-" + expected)) {
                    throw new AssertionError("Incorrect key");
                }
            }
        } finally { db.close(); }
    }
}
