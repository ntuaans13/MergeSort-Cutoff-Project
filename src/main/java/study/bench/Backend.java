package study.bench;

import java.nio.file.Path;
import java.util.List;

/**
 * A measurement backend runs ONE configuration (dataset, cutoff) in {@code forks} fresh JVMs,
 * each doing {@code warmup} unrecorded + {@code measurements} recorded single-shot sorts,
 * and writes a JMH-format JSON result file.
 */
public interface Backend {

    String name();

    void run(String dataset, int cutoff, int warmup, int measurements, int forks,
             List<String> jvmArgs, Path dataDir, Path jsonOut, Path log) throws Exception;

    /** "jmh" if the JMH classes are on the classpath, otherwise the pure-JDK fork backend. */
    static Backend create(String wanted) {
        boolean jmhAvailable;
        try {
            Class.forName("org.openjdk.jmh.runner.Runner");
            Class.forName("study.jmh.JmhBackend");
            jmhAvailable = true;
        } catch (ClassNotFoundException | LinkageError e) {
            jmhAvailable = false;
        }
        switch (wanted) {
            case "fork":
                return new ForkBackend();
            case "jmh":
                if (!jmhAvailable) throw new IllegalStateException(
                        "backend=jmh but JMH is not on the classpath (build with Maven, or use backend=fork)");
                return loadJmh();
            case "auto":
                return jmhAvailable ? loadJmh() : new ForkBackend();
            default:
                throw new IllegalArgumentException("backend must be auto, jmh or fork: " + wanted);
        }
    }

    private static Backend loadJmh() {
        try {
            return (Backend) Class.forName("study.jmh.JmhBackend").getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot load JMH backend", e);
        }
    }
}
