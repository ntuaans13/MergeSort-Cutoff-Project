package study.data;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Datasets for the experiment: deterministic generation, binary I/O, SHA-256, manifest.
 *
 * <p>File format: one big-endian int32 holding N, followed by N big-endian int32 values.
 *
 * <p>Distributions
 * <ul>
 *   <li>{@code random}        uniform in [0, 2^31-1), 5 seeds</li>
 *   <li>{@code nearly_sorted} ascending, then ~1% random pair swaps, 5 seeds</li>
 *   <li>{@code duplicates}    1_000_000 + nextInt(16): 16 distinct keys, 5 seeds.
 *       The offset keeps keys outside the Integer cache (-128..127), so every element is a
 *       distinct object like in the other distributions (fair memory behaviour).</li>
 *   <li>{@code sorted}        1_000_000 + 2i, one deterministic dataset per size</li>
 *   <li>{@code reversed}      sorted, reversed, one deterministic dataset per size</li>
 * </ul>
 */
public final class Dataset {

    public static final long BASE_SEED = 20261103L;
    public static final List<String> SEEDED = List.of("random", "nearly_sorted", "duplicates");
    public static final List<String> DETERMINISTIC = List.of("sorted", "reversed");
    public static final List<String> ALL_DISTS =
            List.of("random", "nearly_sorted", "duplicates", "sorted", "reversed");
    public static final String MANIFEST_HEADER = "id,group,dist,n,seed,rng_seed,file,sha256";

    private Dataset() { }

    public static String id(String dist, int n, int seed) {
        return dist + "_n" + n + "_s" + seed;
    }

    /** Deterministic RNG seed; String.hashCode is specified by the JLS so it is stable across JVMs. */
    public static long rngSeed(String dist, int n, int seed) {
        long h = BASE_SEED;
        h = h * 1_000_003L + dist.hashCode();
        h = h * 1_000_003L + n;
        h = h * 1_000_003L + seed;
        return h;
    }

    public static int[] generate(String dist, int n, int seed) {
        SplittableRandom rnd = new SplittableRandom(rngSeed(dist, n, seed));
        int[] a = new int[n];
        switch (dist) {
            case "random" -> {
                for (int i = 0; i < n; i++) a[i] = rnd.nextInt(Integer.MAX_VALUE);
            }
            case "duplicates" -> {
                for (int i = 0; i < n; i++) a[i] = 1_000_000 + rnd.nextInt(16);
            }
            case "nearly_sorted" -> {
                for (int i = 0; i < n; i++) a[i] = rnd.nextInt(Integer.MAX_VALUE);
                Arrays.sort(a);
                int swaps = Math.max(1, n / 100);
                for (int s = 0; s < swaps; s++) {
                    int i = rnd.nextInt(n), j = rnd.nextInt(n);
                    int t = a[i]; a[i] = a[j]; a[j] = t;
                }
            }
            case "sorted" -> {
                for (int i = 0; i < n; i++) a[i] = 1_000_000 + 2 * i;
            }
            case "reversed" -> {
                for (int i = 0; i < n; i++) a[i] = 1_000_000 + 2 * (n - 1 - i);
            }
            default -> throw new IllegalArgumentException("Unknown distribution: " + dist);
        }
        return a;
    }

    public static void write(Path file, int[] a) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 * a.length);   // big-endian by default
        buf.putInt(a.length);
        buf.asIntBuffer().put(a);
        Files.write(file, buf.array());
    }

    public static int[] read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int n = buf.getInt();
        if (bytes.length != 4 + 4L * n) {
            throw new IOException("Corrupt dataset " + file + ": header says N=" + n
                    + " but file has " + bytes.length + " bytes");
        }
        IntBuffer ib = buf.asIntBuffer();
        int[] a = new int[n];
        ib.get(a);
        return a;
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One row of data/manifest.csv. */
    public record Entry(String id, String group, String dist, int n, int seed,
                        long rngSeed, String file, String sha256) {
        public String toCsv() {
            return String.join(",", id, group, dist, Integer.toString(n), Integer.toString(seed),
                    Long.toString(rngSeed), file, sha256);
        }

        public Path path(Path dataDir) {
            return dataDir.resolve(file);
        }
    }

    public static List<Entry> readManifest(Path dataDir) throws IOException {
        Path m = dataDir.resolve("manifest.csv");
        if (!Files.exists(m)) {
            throw new IOException("Missing " + m.toAbsolutePath()
                    + " - run: java -cp target/benchmarks.jar study.data.GenerateData");
        }
        List<Entry> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(m);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] p = line.split(",");
            out.add(new Entry(p[0], p[1], p[2], Integer.parseInt(p[3]), Integer.parseInt(p[4]),
                    Long.parseLong(p[5]), p[6], p[7]));
        }
        return out;
    }

    /** Reads a dataset and checks its SHA-256 against the manifest. */
    public static int[] readVerified(Path dataDir, Entry e) throws IOException {
        Path f = e.path(dataDir);
        String h = sha256(f);
        if (!h.equals(e.sha256())) {
            throw new IOException("SHA-256 mismatch for " + f + " (dataset changed after manifest was written)");
        }
        return read(f);
    }
}
