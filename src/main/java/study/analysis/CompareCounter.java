package study.analysis;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import study.data.Dataset;
import study.sort.MergeSort;
import study.util.Cli;

/**
 * Machine-independent part of the experiment.
 *
 * <p>1. Counts the exact number of compareTo() calls for each (dataset, cutoff).
 * <p>2. Equivalence classes: top-down mergesort only produces subarray lengths of about N/2^k, so many
 * consecutive cutoffs give exactly the same recursion tree ("staircase" effect). The classes are
 * computed exactly from the tree and VALIDATED on your implementation with a hash of the whole
 * sequence of comparisons (an execution trace). The launcher measures one representative per class
 * and the analysis copies its timing to the other members.
 *
 * <pre>
 * java -cp target/benchmarks.jar study.analysis.CompareCounter --cutoffs 0-64 --out results/compares.csv
 * </pre>
 */
public final class CompareCounter {

    /** Key that counts and fingerprints comparisons. Never used for timing. */
    static final class TraceKey implements Comparable<TraceKey> {
        static long count;
        static long hash;
        final int v;

        TraceKey(int v) { this.v = v; }

        @Override
        public int compareTo(TraceKey o) {
            count++;
            hash = hash * 0x9E3779B97F4A7C15L + ((((long) v) << 32) ^ (o.v & 0xFFFFFFFFL)) + 1;
            return Integer.compare(v, o.v);
        }
    }

    public record Row(String dataset, String dist, int n, int cutoff, long compares, long trace) { }

    private CompareCounter() { }

    public static Row measure(Dataset.Entry e, int[] keys, int cutoff) {
        Comparable[] a = new Comparable[keys.length];
        for (int i = 0; i < keys.length; i++) a[i] = new TraceKey(keys[i]);
        TraceKey.count = 0;
        TraceKey.hash = 17;
        MergeSort.sort(a, cutoff);
        for (int i = 1; i < a.length; i++) {
            if (((TraceKey) a[i]).v < ((TraceKey) a[i - 1]).v)
                throw new IllegalStateException("not sorted: " + e.id() + " cutoff=" + cutoff);
        }
        return new Row(e.id(), e.dist(), keys.length, cutoff, TraceKey.count, TraceKey.hash);
    }

    /** Seed-0 dataset of every (dist, n) present in the given entries. */
    public static List<Dataset.Entry> representatives(List<Dataset.Entry> entries) {
        Map<String, Dataset.Entry> rep = new LinkedHashMap<>();
        for (Dataset.Entry e : entries) {
            String k = e.dist() + "|" + e.n();
            Dataset.Entry cur = rep.get(k);
            if (cur == null || e.seed() < cur.seed()) rep.put(k, e);
        }
        return new ArrayList<>(rep.values());
    }

    public static List<Row> run(Path dataDir, List<Dataset.Entry> entries, int[] cutoffs, boolean verbose)
            throws IOException {
        List<Row> rows = new ArrayList<>();
        for (Dataset.Entry e : representatives(entries)) {
            int[] keys = Dataset.readVerified(dataDir, e);
            for (int c : cutoffs) rows.add(measure(e, keys, c));
            if (verbose) System.out.printf("  compares: %-28s done%n", e.id());
        }
        return rows;
    }

    /** All subarray lengths that occur in the recursion tree of top-down mergesort (mid = lo + (hi-lo)/2). */
    public static java.util.SortedSet<Integer> nodeLengths(int n) {
        java.util.TreeSet<Integer> seen = new java.util.TreeSet<>();
        java.util.ArrayDeque<Integer> todo = new java.util.ArrayDeque<>();
        if (n > 0) todo.add(n);
        while (!todo.isEmpty()) {
            int len = todo.poll();
            if (!seen.add(len) || len <= 1) continue;
            todo.add(len / 2 + len % 2);   // left half  a[lo..mid]
            todo.add(len / 2);             // right half a[mid+1..hi]
        }
        return seen;
    }

    /**
     * Equivalence classes of cutoffs, per n: cutoff -> representative (smallest cutoff of its class).
     *
     * <p>Exact rule (from the contract): with cutoff c a node of length L is insertion-sorted iff
     * L <= c, so c1 and c2 run the identical algorithm iff no node length L of the recursion tree
     * satisfies min(c1,c2) < L <= max(c1,c2). Compare counts alone cannot prove this (an insertion sort
     * of 2 elements and a 1+1 merge both make one compare but move data differently), so the traces are
     * used only to VALIDATE that the implementation follows the contract: cutoffs in the same exact class
     * must produce identical compare sequences on every distribution. If not, no merging is done.
     */
    public static Map<Integer, Map<Integer, Integer>> classes(List<Row> rows) {
        Map<Integer, Map<Integer, StringBuilder>> sig = new TreeMap<>();
        rows.stream()
                .sorted((x, y) -> x.dist().compareTo(y.dist()))
                .forEach(r -> sig.computeIfAbsent(r.n(), k -> new TreeMap<>())
                        .computeIfAbsent(r.cutoff(), k -> new StringBuilder())
                        .append(r.dist()).append(':').append(r.compares()).append(':').append(r.trace()).append(';'));
        Map<Integer, Map<Integer, Integer>> out = new TreeMap<>();
        for (var en : sig.entrySet()) {
            int n = en.getKey();
            java.util.SortedSet<Integer> lengths = nodeLengths(n);
            Map<Integer, Integer> firstByKey = new HashMap<>();
            Map<Integer, Integer> rep = new TreeMap<>();
            boolean valid = true;
            for (var c : en.getValue().entrySet()) {          // TreeMap: ascending cutoff
                int key = lengths.headSet(c.getKey() + 1).size();   // #node lengths <= cutoff
                int r = firstByKey.computeIfAbsent(key, k -> c.getKey());
                rep.put(c.getKey(), r);
                if (!en.getValue().get(r).toString().equals(c.getValue().toString())) valid = false;
            }
            if (!valid) {
                System.out.println("WARNING: N=" + n + ": cutoffs that should be equivalent (same recursion tree) "
                        + "produced different compare sequences. Your MergeSort does not follow the contract "
                        + "(split mid = lo + (hi-lo)/2, insertion sort iff length <= cutoff); dedupe disabled for this N.");
                rep.replaceAll((c, r) -> c);
            }
            out.put(n, rep);
        }
        return out;
    }

    /**
     * Contract check used by CheckSort: on random keys, cutoffs that give the same recursion tree must
     * produce identical compare sequences. Returns null if fine, otherwise a description of the first violation.
     */
    public static String contractViolation(int n, int[] cutoffs, long seed) {
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(seed);
        int[] keys = new int[n];
        for (int i = 0; i < n; i++) keys[i] = rnd.nextInt();
        java.util.SortedSet<Integer> lengths = nodeLengths(n);
        Map<Integer, long[]> first = new HashMap<>();
        Map<Integer, Integer> firstCut = new HashMap<>();
        Dataset.Entry e = new Dataset.Entry("check_n" + n, "check", "random", n, 0, seed, "-", "-");
        for (int c : cutoffs) {
            Row r = measure(e, keys, c);
            int key = lengths.headSet(c + 1).size();
            long[] sig = {r.compares(), r.trace()};
            long[] f = first.putIfAbsent(key, sig);
            firstCut.putIfAbsent(key, c);
            if (f != null && (f[0] != sig[0] || f[1] != sig[1])) {
                return "N=" + n + ": cutoff " + firstCut.get(key) + " and " + c + " must run the same recursion tree "
                        + "(no subarray length between them) but compare differently (" + f[0] + " vs " + sig[0]
                        + " compares). Check: insertion sort iff (hi - lo + 1) <= cutoff, and mid = lo + (hi - lo) / 2.";
            }
        }
        return null;
    }

    public static void writeCsv(List<Row> rows, Path out) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            w.write("dataset,dist,n,cutoff,compares,trace\n");
            for (Row r : rows)
                w.write(String.format("%s,%s,%d,%d,%d,%016x%n", r.dataset(), r.dist(), r.n(), r.cutoff(),
                        r.compares(), r.trace()));
        }
    }

    public static void writeClasses(Map<Integer, Map<Integer, Integer>> cls, Path out) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            w.write("n,cutoff,representative\n");
            for (var en : cls.entrySet())
                for (var c : en.getValue().entrySet())
                    w.write(en.getKey() + "," + c.getKey() + "," + c.getValue() + "\n");
        }
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> opt = Cli.parse(args);
        Path data = Paths.get(opt.getOrDefault("data", "data"));
        int[] cutoffs = Cli.ints(opt.getOrDefault("cutoffs", "0-64"));
        String group = opt.getOrDefault("group", "sweep");
        Path out = Paths.get(opt.getOrDefault("out", "results/compares.csv"));
        study.bench.BenchSupport.requireImplemented();
        List<Dataset.Entry> entries = Dataset.readManifest(data).stream()
                .filter(e -> e.group().equals(group)).toList();
        List<Row> rows = run(data, entries, cutoffs, true);
        Files.createDirectories(out.toAbsolutePath().getParent());
        writeCsv(rows, out);
        writeClasses(classes(rows), out.resolveSibling("equivalence.csv"));
        System.out.println("Saved " + out + " and equivalence.csv");
    }
}
