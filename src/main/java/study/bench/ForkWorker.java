package study.bench;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import study.data.Dataset;
import study.sort.MergeSort;
import study.util.Cli;

/**
 * Child-JVM entry point of the pure-JDK backend (same protocol as JMH SingleShotTime):
 * load + box the dataset once (trial setup), then for every iteration clone the array
 * (iteration setup, not timed), time ONE sort with System.nanoTime, verify the result (not timed).
 * Prints "W i ns" for warm-up iterations and "M i ns" for measurement iterations.
 */
public final class ForkWorker {

    private ForkWorker() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = Cli.parse(args);
        Path data = Paths.get(opt.get("data"));
        String id = opt.get("dataset");
        int cutoff = Integer.parseInt(opt.get("cutoff"));
        int warmup = Integer.parseInt(opt.get("warmup"));
        int meas = Integer.parseInt(opt.get("measurements"));

        BenchSupport.checkIntegerCache();
        BenchSupport.requireImplemented();
        Dataset.Entry e = Dataset.readManifest(data).stream()
                .filter(x -> x.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown dataset " + id));
        Comparable[] base = BenchSupport.box(Dataset.readVerified(data, e));

        for (int i = 0; i < warmup + meas; i++) {
            Comparable[] work = base.clone();
            long t0 = System.nanoTime();
            MergeSort.sort(work, cutoff);
            long t1 = System.nanoTime();
            if (!BenchSupport.isSorted(work))
                throw new IllegalStateException("Result not sorted: " + id + " cutoff=" + cutoff);
            System.out.println((i < warmup ? "W " + i : "M " + (i - warmup)) + " " + (t1 - t0));
        }
        System.out.println("DONE");
    }
}
