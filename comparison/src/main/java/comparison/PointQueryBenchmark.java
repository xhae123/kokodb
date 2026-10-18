package comparison;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms64m", "-Xmx256m", "-XX:-UsePerfData"})
public class PointQueryBenchmark {
    @Param({"kokodb", "h2"}) public String engine;
    @Param({"1000", "10000"}) public int rows;
    private PointFixture fixture;
    private int cursor;

    @Setup(Level.Trial)
    public void setup() throws Exception { fixture = new PointFixture(engine, rows); }

    @Benchmark
    public List<BenchmarkUser> hit() throws Exception { return fixture.query(cursor++ % rows); }

    @Benchmark
    public List<BenchmarkUser> miss() throws Exception { return fixture.query(-1 - (cursor++ % rows)); }

    @Benchmark
    public List<BenchmarkUser> updateThenRead() throws Exception {
        int id = cursor++ % rows;
        fixture.update(id, "changed" + cursor);
        return fixture.query(id);
    }

    @TearDown(Level.Trial)
    public void teardown() throws Exception {
        try { fixture.verify(); } finally { fixture.close(); }
    }
}
