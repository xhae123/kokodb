package comparison;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms64m", "-Xmx256m", "-XX:-UsePerfData"})
public class ModelQueryBenchmark {
    @Param({"kokodb", "h2"}) public String engine;
    @Param({"1", "100", "1000"}) public int rows;
    private ModelFixture fixture;

    @Setup(Level.Trial)
    public void setup() throws Exception { fixture = new ModelFixture(engine, rows); }

    @Benchmark
    public List<BenchmarkUser> models() throws Exception { return fixture.query(); }

    @TearDown(Level.Trial)
    public void teardown() throws Exception {
        try { fixture.verify(); } finally { fixture.close(); }
    }
}
