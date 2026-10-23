package comparison;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms64m", "-Xmx256m", "-XX:-UsePerfData"})
public class WriteComparisonBenchmark {
    @Param({"kokodb", "h2"}) public String engine;
    @Param({"1000", "16000"}) public int rows;
    private StartupProbe.Session session;

    @Setup(Level.Invocation)
    public void setup() throws Exception { session = StartupProbe.open(engine); }

    @Benchmark
    public void insert() throws Exception { session.insert(rows); }

    @TearDown(Level.Invocation)
    public void teardown() throws Exception {
        try { StartupProbe.verify(session.query(), rows); } finally { session.close(); }
    }
}
