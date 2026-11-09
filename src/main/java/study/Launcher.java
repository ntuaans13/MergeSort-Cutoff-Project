package study;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;

import study.analysis.CompareCounter;
import study.bench.Backend;
import study.bench.BenchSupport;
import study.data.Dataset;
import study.sort.MergeSort;
import study.util.Cli;

/**
 * Runs a whole experiment described by a .properties file (configs/pilot.properties,
 * configs/grid.properties, configs/doubling.properties).
 *
 * <pre>
 * java -cp target/benchmarks.jar study.Launcher configs/grid.properties [--set key=value ...] [--dry-run]
 * java -cp target/benchmarks.jar study.Launcher --resume results/grid-20261108-213000
 * </pre>
 *
 * What it does
 * <ol>
 *   <li>Refuses a placeholder MergeSort; records environment + MergeSort.class SHA-256.</li>
 *   <li>Counts compares and execution traces (machine-independent) -> compares.csv, equivalence.csv.</li>
 *   <li>Builds the (dataset, cutoff) task list. With dedupe=true only one cutoff per equivalence
 *       class is measured (same recursion tree = same work); cutoffs listed in "controls" are always
 *       measured separately, as a check of measurement noise.</li>
 *   <li>Shuffles the task order with orderSeed, estimates the running time (--dry-run stops here).</li>
 *   <li>Runs every task in its own forked JVMs (JMH, or the pure-JDK fork backend), one JSON per task,
 *       updating schedule.csv after each. Interrupted? Re-run with --resume DIR: finished tasks are skipped.</li>
 * </ol>
 */
public final class Launcher {

    record Task(Dataset.Entry entry, int cutoff, int warmup, int measurements, int forks) { }

    private final Properties cfg;

    private Launcher(Properties cfg) {
        this.cfg = cfg;
    }

    // ------------------------------------------------------------------ config helpers

    private String get(String key, String def) {
        return cfg.getProperty(key, def).trim();
    }

    private int sized(String key, int n, int def) {
        String v = cfg.getProperty(key + "." + n);
        if (v == null) v = cfg.getProperty(key);
        return v == null ? def : Integer.parseInt(v.trim());
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        Properties cfg = new Properties();
        Path resume = null;
        boolean dryRun = false;
        List<String> sets = new ArrayList<>();
        Path configFile = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--resume" -> resume = Paths.get(args[++i]);
                case "--dry-run" -> dryRun = true;
                case "--set" -> sets.add(args[++i]);
                default -> {
                    if (args[i].startsWith("--")) throw new IllegalArgumentException("Unknown option " + args[i]);
                    configFile = Paths.get(args[i]);
                }
            }
        }
        if (resume != null) configFile = resume.resolve("config.properties");
        if (configFile == null) {
            System.err.println("Usage: study.Launcher <config.properties> [--set k=v]... [--dry-run] | --resume <runDir>");
            System.exit(2);
        }
        try (Reader r = Files.newBufferedReader(configFile)) {
            cfg.load(r);
        }
        for (String s : sets) {
            int eq = s.indexOf('=');
            if (eq < 0) throw new IllegalArgumentException("--set expects key=value: " + s);
            cfg.setProperty(s.substring(0, eq).trim(), s.substring(eq + 1).trim());
        }
        new Launcher(cfg).run(resume, dryRun);
    }

    private void run(Path resume, boolean dryRun) throws Exception {
        String mode = get("mode", "grid");
        if (!Set.of("pilot", "grid", "doubling").contains(mode))
            throw new IllegalArgumentException("mode must be pilot, grid or doubling");
        Path dataDir = Paths.get(get("data", "data"));

        BenchSupport.checkIntegerCache();
        BenchSupport.requireImplemented();
        String implHash = BenchSupport.implementationHash();

        // ---------- datasets
        List<Dataset.Entry> manifest = Dataset.readManifest(dataDir);
        List<Dataset.Entry> entries = selectDatasets(manifest, mode);
        if (entries.isEmpty()) throw new IllegalStateException("No dataset matches the configuration");

        // ---------- cutoffs
        int[] cutoffs = parseCutoffs();
        for (int c : cutoffs) if (c < 0) throw new IllegalArgumentException("negative cutoff " + c);

        // ---------- run directory
        Path runDir;
        if (resume != null) {
            runDir = resume;
            String old = readEnvValue(runDir.resolve("environment.txt"), "mergesort.class.sha256");
            if (old != null && !old.equals(implHash))
                throw new IllegalStateException("MergeSort changed since this run started (SHA-256 differs). "
                        + "Results would mix two implementations - start a new run instead of --resume.");
        } else {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            runDir = Paths.get(get("results", "results"), mode + "-" + stamp);
        }

        boolean dedupe = Boolean.parseBoolean(get("dedupe", "true")) && !mode.equals("pilot");
        boolean count = Boolean.parseBoolean(get("countCompares", "true"));
        Set<Integer> controls = new TreeSet<>();
        for (int c : Cli.ints(get("controls", "0,1"))) controls.add(c);

        // ---------- machine-independent part: compares + execution traces
        Map<Integer, Map<Integer, Integer>> classes = new TreeMap<>();
        List<CompareCounter.Row> rows = List.of();
        if (dedupe || count) {
            System.out.println("Counting compares / execution traces (machine-independent)...");
            rows = CompareCounter.run(dataDir, entries, cutoffs, true);
            classes = CompareCounter.classes(rows);
        }

        // ---------- tasks
        List<Task> tasks = new ArrayList<>();
        for (Dataset.Entry e : entries) {
            Set<Integer> measured = new LinkedHashSet<>();
            for (int c : cutoffs) {
                int rep = dedupe ? classes.get(e.n()).get(c) : c;
                measured.add(controls.contains(c) ? c : rep);
            }
            int w = sized("warmup", e.n(), 20), m = sized("measurements", e.n(), 10), f = sized("forks", e.n(), 3);
            if (mode.equals("pilot")) {      // pilot: record warm-up iterations too, so they can be plotted
                m = w + m;
                w = 0;
            }
            for (int c : measured) tasks.add(new Task(e, c, w, m, f));
        }
        Collections.shuffle(tasks, new Random(Long.parseLong(get("orderSeed", "20261103"))));

        int jvms = tasks.stream().mapToInt(Task::forks).sum();
        long distinctAll = (long) entries.size() * cutoffs.length;
        System.out.printf("%nMode %s: %d datasets x %d cutoffs = %,d (dataset, cutoff) pairs%n",
                mode, entries.size(), cutoffs.length, distinctAll);
        if (dedupe) {
            System.out.printf("Equivalent cutoffs merged -> %,d configurations to measure (%,d JVM forks)%n",
                    tasks.size(), jvms);
            for (var en : classes.entrySet()) {
                System.out.printf("  N=%-8d classes: %s%n", en.getKey(), describeClasses(en.getValue()));
            }
        } else {
            System.out.printf("%,d configurations, %,d JVM forks%n", tasks.size(), jvms);
        }
        Backend backend = Backend.create(get("backend", "auto"));
        jvmOverhead = backend.name().equals("jmh") ? 1.3 : 0.35;
        double est = estimateSeconds(tasks);
        System.out.printf("Estimated running time: %s (rough; calibrated on this machine, backend %s)%n%n",
                hms(est), backend.name());
        if (dryRun) {
            System.out.println("--dry-run: nothing was measured.");
            return;
        }

        // ---------- write metadata
        Files.createDirectories(runDir.resolve("jmh"));
        Files.createDirectories(runDir.resolve("logs"));
        List<String> jvmArgs = Arrays.asList(get("jvmArgs", "-Xms2g -Xmx2g -XX:+UseParallelGC").split("\\s+"));
        if (resume == null) {
            Properties eff = new Properties();
            eff.putAll(cfg);
            eff.setProperty("resolved.cutoffs", joinInts(cutoffs));
            eff.setProperty("resolved.backend", backend.name());
            try (BufferedWriter w = Files.newBufferedWriter(runDir.resolve("config.properties"))) {
                eff.store(w, "Effective configuration of this run");
            }
            writeEnvironment(runDir.resolve("environment.txt"), backend, jvmArgs, implHash);
            if (!rows.isEmpty()) {
                CompareCounter.writeCsv(rows, runDir.resolve("compares.csv"));
                CompareCounter.writeClasses(classes, runDir.resolve("equivalence.csv"));
            }
            Files.copy(dataDir.resolve("manifest.csv"), runDir.resolve("manifest.csv"));
            writeRunReadme(runDir, mode, entries.size(), cutoffs, tasks.size(), jvms, backend.name(), implHash);
        }
        System.out.println("Backend: " + backend.name() + "   Run directory: " + runDir.toAbsolutePath());

        // ---------- run
        String[] status = new String[tasks.size()];
        double[] secs = new double[tasks.size()];
        long start = System.nanoTime();
        double doneEst = 0, measuredEst = 0;
        int failed = 0;
        for (int i = 0; i < tasks.size(); i++) {
            Task t = tasks.get(i);
            String base = String.format("%04d_%s_c%d", i, t.entry().id(), t.cutoff());
            Path json = runDir.resolve("jmh").resolve(base + ".json");
            Path log = runDir.resolve("logs").resolve(base + ".log");
            double taskEst = estimateSeconds(List.of(t));
            if (Files.exists(json) && Files.readString(json).contains("rawData")) {
                status[i] = "done-before";
                doneEst += taskEst;
                continue;
            }
            long t0 = System.nanoTime();
            try {
                backend.run(t.entry().id(), t.cutoff(), t.warmup(), t.measurements(), t.forks(),
                        jvmArgs, dataDir, json, log);
                status[i] = "ok";
            } catch (Exception ex) {
                status[i] = "failed";
                failed++;
                Files.deleteIfExists(json);
                System.err.println("FAILED " + base + ": " + ex.getMessage() + "  (see " + log + ")");
            }
            secs[i] = (System.nanoTime() - t0) / 1e9;
            doneEst += taskEst;
            measuredEst += taskEst;
            writeSchedule(runDir.resolve("schedule.csv"), tasks, status, secs);
            double el = (System.nanoTime() - start) / 1e9;
            double eta = el / Math.max(measuredEst, 1e-9) * (est - doneEst);
            System.out.printf(Locale.ROOT, "[%4d/%d] %-26s cutoff=%-3d %-6s %6.1fs  elapsed %s  ETA %s%n",
                    i + 1, tasks.size(), t.entry().id(), t.cutoff(), status[i], secs[i], hms(el), hms(eta));
        }
        writeSchedule(runDir.resolve("schedule.csv"), tasks, status, secs);
        System.out.println("Finished: " + runDir.toAbsolutePath()
                + (failed > 0 ? "   (" + failed + " FAILED - re-run with --resume " + runDir + ")" : ""));
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ selection

    private List<Dataset.Entry> selectDatasets(List<Dataset.Entry> manifest, String mode) {
        String explicit = get("datasets", "");
        if (!explicit.isEmpty()) {
            List<Dataset.Entry> out = new ArrayList<>();
            for (String id : Cli.strings(explicit)) {
                out.add(manifest.stream().filter(e -> e.id().equals(id)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown dataset id " + id)));
            }
            return out;
        }
        String group = get("group", mode.equals("doubling") ? "doubling" : "sweep");
        List<String> dists = Cli.strings(get("dists", String.join(",", Dataset.ALL_DISTS)));
        String sz = get("sizes", "");
        Set<Integer> sizes = sz.isEmpty() ? null : toSet(Cli.ints(sz));
        Set<Integer> seeds = toSet(Cli.ints(get("seeds", "0-4")));
        List<Dataset.Entry> out = new ArrayList<>();
        for (Dataset.Entry e : manifest) {
            if (!e.group().equals(group)) continue;
            if (!dists.contains(e.dist())) continue;
            if (sizes != null && !sizes.contains(e.n())) continue;
            if (!seeds.contains(e.seed())) continue;
            out.add(e);
        }
        return out;
    }

    private int[] parseCutoffs() throws IOException {
        String spec = get("cutoffs", "0-64");
        if (spec.contains("best")) {
            Path bf = Paths.get(get("bestFile", "results/best_cutoff.txt"));
            if (!Files.exists(bf)) throw new IllegalStateException("cutoffs contains 'best' but " + bf
                    + " does not exist - run scripts/analyze.py on the grid run first");
            spec = spec.replace("best", Files.readString(bf).trim());
        }
        return Cli.ints(spec);
    }

    private static Set<Integer> toSet(int[] a) {
        Set<Integer> s = new TreeSet<>();
        for (int x : a) s.add(x);
        return s;
    }

    private static String describeClasses(Map<Integer, Integer> rep) {
        Map<Integer, List<Integer>> groups = new TreeMap<>();
        rep.forEach((c, r) -> groups.computeIfAbsent(r, k -> new ArrayList<>()).add(c));
        StringBuilder sb = new StringBuilder();
        for (List<Integer> g : groups.values()) {
            if (sb.length() > 0) sb.append(' ');
            int a = g.get(0), b = g.get(g.size() - 1);
            sb.append(a == b ? "{" + a + "}" : "{" + a + ".." + b + (g.size() == b - a + 1 ? "" : "*") + "}");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ estimate

    private final Map<Integer, Double> sortSeconds = new TreeMap<>();
    private double jvmOverhead = 1.0;

    /** Rough wall-clock estimate: JVM start + data load + (warmup+measurements) x (sort + clone + verify). */
    private double estimateSeconds(List<Task> tasks) {
        double total = 0;
        for (Task t : tasks) {
            int n = t.entry().n();
            double sort = sortSeconds.computeIfAbsent(n, Launcher::calibrate);
            double perFork = jvmOverhead + n * 6e-8 + (t.warmup() + t.measurements()) * (sort + n * 6e-9);
            total += t.forks() * perFork;
        }
        return total;
    }

    private static double calibrate(int n) {
        SplittableRandom rnd = new SplittableRandom(n);
        int[] v = new int[n];
        for (int i = 0; i < n; i++) v[i] = rnd.nextInt(Integer.MAX_VALUE);
        Comparable[] base = BenchSupport.box(v);
        int reps = Math.max(3, Math.min(200, 2_000_000 / n));
        for (int i = 0; i < reps; i++) MergeSort.sort(base.clone(), 10);       // warm-up
        long t0 = System.nanoTime();
        for (int i = 0; i < 3; i++) MergeSort.sort(base.clone(), 10);
        return (System.nanoTime() - t0) / 3e9;
    }

    private static String hms(double s) {
        long x = Math.round(s);
        return String.format("%dh%02dm%02ds", x / 3600, (x / 60) % 60, x % 60);
    }

    // ------------------------------------------------------------------ files

    private static void writeSchedule(Path p, List<Task> tasks, String[] status, double[] secs) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(p)) {
            w.write("order,dataset,dist,n,seed,cutoff,warmup,measurements,forks,status,seconds,json\n");
            for (int i = 0; i < tasks.size(); i++) {
                Task t = tasks.get(i);
                Dataset.Entry e = t.entry();
                w.write(String.format(Locale.ROOT, "%d,%s,%s,%d,%d,%d,%d,%d,%d,%s,%.2f,jmh/%04d_%s_c%d.json%n",
                        i, e.id(), e.dist(), e.n(), e.seed(), t.cutoff(), t.warmup(), t.measurements(), t.forks(),
                        status[i] == null ? "pending" : status[i], secs[i], i, e.id(), t.cutoff()));
            }
        }
    }

    private static void writeEnvironment(Path p, Backend backend, List<String> jvmArgs, String implHash)
            throws IOException {
        Runtime rt = Runtime.getRuntime();
        StringBuilder sb = new StringBuilder();
        sb.append("timestamp=").append(LocalDateTime.now()).append('\n');
        sb.append("java.version=").append(System.getProperty("java.version")).append('\n');
        sb.append("java.vm.name=").append(System.getProperty("java.vm.name")).append('\n');
        sb.append("java.vendor=").append(System.getProperty("java.vendor")).append('\n');
        sb.append("os=").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
                .append(' ').append(System.getProperty("os.arch")).append('\n');
        sb.append("cpu=").append(cpuModel()).append('\n');
        sb.append("available.processors=").append(rt.availableProcessors()).append('\n');
        sb.append("backend=").append(backend.name()).append('\n');
        sb.append("fork.jvmArgs=").append(String.join(" ", jvmArgs)).append('\n');
        sb.append("mergesort.class.sha256=").append(implHash).append('\n');
        Files.writeString(p, sb.toString());
    }

    /** README.md of the run directory, so GitHub shows what the folder contains. */
    private void writeRunReadme(Path runDir, String mode, int datasets, int[] cutoffs, int configs, int jvms,
                                String backend, String implHash) throws IOException {
        String name = runDir.getFileName().toString();
        String role = switch (mode) {
            case "pilot" -> "Pilot chọn số vòng warm-up: mọi vòng (kể cả warm-up) đều được ghi lại.";
            case "doubling" -> "Doubling test: T(N) với N tăng gấp đôi, để kiểm tra bậc tăng.";
            default -> "Lưới chính: thời gian sort theo CUTOFF trên các bộ dữ liệu.";
        };
        StringBuilder sb = new StringBuilder();
        sb.append("# Lượt chạy `").append(name).append("`\n\n").append(role).append("\n\n");
        sb.append("| Mục | Giá trị |\n|---|---|\n");
        sb.append("| Chế độ | `").append(mode).append("` |\n");
        sb.append("| Bắt đầu | ").append(LocalDateTime.now().withNano(0)).append(" |\n");
        sb.append("| Backend | ").append(backend).append(" |\n");
        sb.append("| JDK | ").append(System.getProperty("java.vm.name")).append(' ')
                .append(System.getProperty("java.version")).append(" |\n");
        sb.append("| Datasets | ").append(datasets).append(" |\n");
        sb.append("| CUTOFF | `").append(get("cutoffs", "0-64")).append("` (").append(cutoffs.length).append(" giá trị) |\n");
        sb.append("| Cấu hình được đo | ").append(configs).append(" (").append(jvms).append(" JVM fork) |\n");
        sb.append("| warm-up / đo / fork | ").append(get("warmup", "20")).append(" / ").append(get("measurements", "10"))
                .append(" / ").append(get("forks", "3")).append(" (ghi đè theo N: xem `config.properties`) |\n");
        sb.append("| SHA-256 `MergeSort.class` | `").append(implHash, 0, Math.min(16, implHash.length())).append("…` |\n\n");
        sb.append("## Nội dung\n\n| File | Nội dung |\n|---|---|\n");
        sb.append("| [`config.properties`](config.properties) | cấu hình hiệu lực của lượt chạy |\n");
        sb.append("| [`environment.txt`](environment.txt) | JDK, OS, CPU, tham số JVM, SHA-256 của cài đặt |\n");
        sb.append("| [`manifest.csv`](manifest.csv) | bản sao manifest dữ liệu (SHA-256 từng dataset) |\n");
        sb.append("| [`schedule.csv`](schedule.csv) | thứ tự chạy (đã xáo trộn), trạng thái, thời gian từng cấu hình |\n");
        if (Files.exists(runDir.resolve("compares.csv"))) {
            sb.append("| [`compares.csv`](compares.csv) | số phép so sánh chính xác theo (dataset, CUTOFF) |\n");
            sb.append("| [`equivalence.csv`](equivalence.csv) | lớp CUTOFF tương đương (cùng cây đệ quy) theo N |\n");
        }
        sb.append("| [`jmh/`](jmh) | số liệu gốc định dạng JMH JSON, mỗi cấu hình một file |\n");
        sb.append("| [`logs/`](logs) | log của từng cấu hình |\n\n");
        sb.append("## Phân tích\n\n```powershell\n.\\scripts\\analyze.ps1 results\\").append(name)
                .append("\n```\n\nKết quả: [`../analysis-").append(name).append("/README.md`](../analysis-")
                .append(name).append("/README.md)\n");
        Files.writeString(runDir.resolve("README.md"), sb.toString());
    }

    private static String cpuModel() {
        try {
            Path ci = Paths.get("/proc/cpuinfo");
            if (Files.exists(ci)) {
                for (String line : Files.readAllLines(ci))
                    if (line.startsWith("model name")) return line.split(":", 2)[1].trim();
            }
            String win = System.getenv("PROCESSOR_IDENTIFIER");
            if (win != null) return win;
            if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")) {
                Process p = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string").start();
                return new String(p.getInputStream().readAllBytes()).trim();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "unknown";
    }

    private static String readEnvValue(Path p, String key) throws IOException {
        if (!Files.exists(p)) return null;
        for (String line : Files.readAllLines(p)) if (line.startsWith(key + "=")) return line.substring(key.length() + 1);
        return null;
    }

    private static String joinInts(int[] a) {
        StringBuilder sb = new StringBuilder();
        for (int x : a) {
            if (sb.length() > 0) sb.append(',');
            sb.append(x);
        }
        return sb.toString();
    }
}
