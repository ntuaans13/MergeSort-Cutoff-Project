package study.jmh;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import study.bench.Backend;

/** Runs one (dataset, cutoff) configuration through JMH: {@code forks} JVMs, SingleShotTime. */
public final class JmhBackend implements Backend {

    @Override
    public String name() {
        return "jmh";
    }

    @Override
    public void run(String dataset, int cutoff, int warmup, int measurements, int forks,
                    List<String> jvmArgs, Path dataDir, Path jsonOut, Path log) throws Exception {
        Options opt = new OptionsBuilder()
                .include(SortBenchmark.class.getName().replace(".", "\\.") + "\\.sort$")
                .param("dataset", dataset)
                .param("cutoff", Integer.toString(cutoff))
                .param("dataDir", dataDir.toAbsolutePath().toString())
                .mode(Mode.SingleShotTime)
                .timeUnit(TimeUnit.NANOSECONDS)
                .warmupIterations(warmup)
                .warmupBatchSize(1)
                .measurementIterations(measurements)
                .measurementBatchSize(1)
                .forks(forks)
                .threads(1)
                .jvmArgs(jvmArgs.toArray(new String[0]))
                .shouldFailOnError(true)
                .resultFormat(ResultFormatType.JSON)
                .result(jsonOut.toString())
                .output(log.toString())
                .build();
        new Runner(opt).run();
    }
}
