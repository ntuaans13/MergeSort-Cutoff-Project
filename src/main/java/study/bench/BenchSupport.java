package study.bench;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import study.sort.MergeSort;

/** Helpers shared by both measurement backends (JMH and the pure-JDK fork worker). */
public final class BenchSupport {

    public static final String TODO_MESSAGE = "MergeSort is still TODO";

    private BenchSupport() { }

    /**
     * Boxes keys into Integer objects. Every dataset uses keys >= 1_000_000 or random 31-bit keys,
     * so with the default Integer cache (-128..127) each element is a separate object.
     */
    public static Comparable[] box(int[] v) {
        Comparable[] a = new Comparable[v.length];
        for (int i = 0; i < v.length; i++) a[i] = Integer.valueOf(v[i]);
        return a;
    }

    /** Fails if this JVM was started with an Integer cache large enough to share our duplicate keys. */
    public static void checkIntegerCache() {
        if (Integer.valueOf(1_000_000) == Integer.valueOf(1_000_000)
                || Integer.valueOf(1_000_015) == Integer.valueOf(1_000_015)) {
            throw new IllegalStateException("Integer cache covers 1_000_000..1_000_015 on this JVM "
                    + "(-XX:AutoBoxCacheMax / java.lang.Integer.IntegerCache.high?). "
                    + "Duplicate keys would share objects; remove that option.");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean isSorted(Comparable[] a) {
        for (int i = 1; i < a.length; i++) if (a[i].compareTo(a[i - 1]) < 0) return false;
        return true;
    }

    /** True if MergeSort is still the placeholder. */
    public static boolean isPlaceholder() {
        try {
            MergeSort.sort(new Comparable[] {3, 1, 2}, 0);
            return false;
        } catch (UnsupportedOperationException e) {
            return true;
        }
    }

    /** Benchmarks refuse to measure the placeholder. */
    public static void requireImplemented() {
        if (isPlaceholder()) {
            throw new IllegalStateException(TODO_MESSAGE + " - refusing to benchmark a placeholder. "
                    + "Implement src/main/java/study/sort/MergeSort.java and run build with -RequireImplementation.");
        }
    }

    /** SHA-256 of the compiled MergeSort.class: identifies exactly which implementation was measured. */
    public static String implementationHash() {
        try (InputStream in = MergeSort.class.getResourceAsStream("MergeSort.class")) {
            if (in == null) return "unknown";
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(in.readAllBytes()));
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            return "unknown";
        }
    }
}
