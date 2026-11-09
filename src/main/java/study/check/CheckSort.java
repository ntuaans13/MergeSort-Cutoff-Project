package study.check;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Map;
import java.util.SplittableRandom;

import study.bench.BenchSupport;
import study.sort.MergeSort;
import study.util.Cli;

/**
 * Correctness gate: must pass before any measurement.
 *
 * <pre>
 * java -cp target/benchmarks.jar study.check.CheckSort            # scaffold mode
 * java -cp target/benchmarks.jar study.check.CheckSort --require  # fails while MergeSort is TODO
 * </pre>
 *
 * Checks, for cutoff 0..64 and 31 sizes x 5 input kinds:
 * sorted output, permutation of the input, stability; plus empty input, negative cutoff
 * (IllegalArgumentException), the cutoff convention (cutoffs giving the same recursion tree must
 * compare identically), and, as a warning, that one sort() allocates about one auxiliary array.
 */
public final class CheckSort {

    /** (key, original index); compares by key only, index is used to verify stability. */
    static final class Item implements Comparable<Item> {
        final int key, idx;
        Item(int key, int idx) { this.key = key; this.idx = idx; }
        @Override public int compareTo(Item o) { return Integer.compare(key, o.key); }
    }

    private CheckSort() { }

    public static void main(String[] args) {
        Map<String, String> opt = Cli.parse(args);
        boolean require = opt.containsKey("require");
        int[] cutoffs = Cli.ints(opt.getOrDefault("cutoffs", "0-64"));

        if (BenchSupport.isPlaceholder()) {
            if (require) {
                System.err.println("FAIL: " + BenchSupport.TODO_MESSAGE
                        + " - implement src/main/java/study/sort/MergeSort.java first.");
                System.exit(1);
            }
            System.out.println("SCAFFOLD ONLY: " + BenchSupport.TODO_MESSAGE
                    + ". The project builds, but nothing about the algorithm has been verified.");
            return;
        }

        // 1. edge cases of the contract
        try {
            MergeSort.sort(new Comparable[0], 0);
            MergeSort.sort(new Comparable[0], 10);
            MergeSort.sort(new Comparable[] {7}, 10);
        } catch (RuntimeException e) {
            fail("empty / single-element array threw " + e, -1, 0, "edge");
        }
        try {
            MergeSort.sort(new Comparable[] {2, 1}, -1);
            fail("negative cutoff must throw IllegalArgumentException", -1, 2, "edge");
        } catch (IllegalArgumentException expected) {
            // ok
        }

        // 2. sortedness, permutation, stability
        int[] sizes = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 15, 16, 17, 31, 32, 33, 63, 64, 65,
                100, 127, 128, 129, 1000, 1023, 1024, 1025, 4999};
        String[] kinds = {"random", "dup3", "all_equal", "sorted", "reversed"};
        SplittableRandom rnd = new SplittableRandom(2026);
        long cases = 0;
        for (int c : cutoffs) {
            for (int n : sizes) {
                for (String kind : kinds) {
                    int[] keys = new int[n];
                    for (int i = 0; i < n; i++) {
                        keys[i] = switch (kind) {
                            case "random" -> rnd.nextInt(1_000_000);
                            case "dup3" -> rnd.nextInt(3);
                            case "all_equal" -> 42;
                            case "sorted" -> i / 2;
                            default -> n - i / 2;
                        };
                    }
                    Comparable[] a = new Comparable[n];
                    for (int i = 0; i < n; i++) a[i] = new Item(keys[i], i);
                    MergeSort.sort(a, c);

                    int[] expected = keys.clone();
                    Arrays.sort(expected);
                    boolean[] seen = new boolean[n];
                    for (int i = 0; i < n; i++) {
                        Item it = (Item) a[i];
                        if (it == null || it.key != expected[i])
                            fail("wrong result (not sorted or element lost) at index " + i, c, n, kind);
                        if (seen[it.idx]) fail("element duplicated", c, n, kind);
                        seen[it.idx] = true;
                        if (i > 0) {
                            Item prev = (Item) a[i - 1];
                            if (prev.key == it.key && prev.idx > it.idx)
                                fail("NOT stable at index " + i, c, n, kind);
                        }
                    }
                    cases++;
                }
            }
        }
        System.out.printf("PASS: MergeSort is correct and stable on %,d cases (cutoff %d..%d) + edge cases.%n",
                cases, cutoffs[0], cutoffs[cutoffs.length - 1]);

        String v = study.analysis.CompareCounter.contractViolation(1000, cutoffs, 11);
        if (v == null) v = study.analysis.CompareCounter.contractViolation(10_000, cutoffs, 12);
        if (v != null) {
            System.err.println("FAIL (cutoff convention): " + v);
            System.exit(1);
        }
        System.out.println("PASS: cutoff convention (length <= cutoff -> insertion sort, mid = lo + (hi-lo)/2) "
                + "verified via compare traces.");

        allocationCheck();
    }

    /** Warning only: estimates how many N-sized arrays one sort() allocates (expected ~1). */
    private static void allocationCheck() {
        java.lang.management.ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        if (!(mx instanceof com.sun.management.ThreadMXBean bean) || !bean.isThreadAllocatedMemorySupported()) {
            System.out.println("NOTE: allocation check skipped (not supported by this JVM).");
            return;
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        int n = 200_000;
        SplittableRandom rnd = new SplittableRandom(7);
        Comparable[] base = new Comparable[n];
        for (int i = 0; i < n; i++) base[i] = rnd.nextInt();
        for (int w = 0; w < 5; w++) MergeSort.sort(base.clone(), 10);   // warm-up
        long tid = Thread.currentThread().getId();
        Comparable[] a = base.clone();
        long before = bean.getThreadAllocatedBytes(tid);
        MergeSort.sort(a, 10);
        long bytes = bean.getThreadAllocatedBytes(tid) - before;
        // Reference array of n elements: 4 bytes/ref with compressed oops (heap < 32 GB), else 8.
        int ref = Runtime.getRuntime().maxMemory() < (32L << 30) ? 4 : 8;
        double arrays = bytes / (double) (16 + (long) ref * n);
        if (arrays > 1.5) {
            System.out.printf("WARN: one sort() allocated ~%.1f arrays of size N (%,d bytes). "
                    + "Expected ~1 auxiliary array - is aux allocated inside the recursion/merge?%n", arrays, bytes);
        } else if (arrays < 0.5) {
            System.out.printf("WARN: one sort() allocated only %,d bytes. The contract asks for one auxiliary "
                    + "array per public sort() call (allocation cost is part of what is measured).%n", bytes);
        } else {
            System.out.printf("PASS: one sort() allocates ~%.2f auxiliary arrays (%,d bytes).%n", arrays, bytes);
        }
    }

    private static void fail(String msg, int cutoff, int n, String kind) {
        System.err.printf("FAIL: %s  [cutoff=%d, n=%d, input=%s]%n", msg, cutoff, n, kind);
        System.exit(1);
    }
}
