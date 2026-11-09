package study.jmh;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import study.bench.BenchSupport;
import study.data.Dataset;
import study.sort.MergeSort;

/**
 * JMH benchmark: one iteration = one sort (SingleShotTime).
 * Trial setup: read (SHA-256 verified) + box the dataset. Iteration setup: clone (not timed).
 * Iteration teardown: verify the result is sorted (not timed).
 * The auxiliary-array allocation inside MergeSort.sort IS part of the measured time.
 * Parameters are normally supplied by study.Launcher, one (dataset, cutoff) per run.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class SortBenchmark {

    @Param({"random_n10000_s0"})
    public String dataset;

    @Param({"10"})
    public int cutoff;

    @Param({"data"})
    public String dataDir;

    private Comparable[] base;
    private Comparable[] work;

    @Setup(Level.Trial)
    public void loadDataset() throws IOException {
        BenchSupport.checkIntegerCache();
        BenchSupport.requireImplemented();
        Path dir = Path.of(dataDir);
        Dataset.Entry e = Dataset.readManifest(dir).stream()
                .filter(x -> x.id().equals(dataset)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown dataset " + dataset));
        base = BenchSupport.box(Dataset.readVerified(dir, e));
    }

    @Setup(Level.Iteration)
    public void copyInput() {
        work = base.clone();
    }

    @Benchmark
    public Comparable[] sort() {
        MergeSort.sort(work, cutoff);
        return work;   // returned -> consumed by JMH, cannot be dead-code eliminated
    }

    @TearDown(Level.Iteration)
    public void verify() {
        if (!BenchSupport.isSorted(work))
            throw new IllegalStateException("Result not sorted: " + dataset + " cutoff=" + cutoff);
    }
}
