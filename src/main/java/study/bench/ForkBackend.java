package study.bench;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure-JDK backend: no dependency besides the JDK. Each fork is a fresh child JVM
 * ({@link ForkWorker}); the result file has the same shape as JMH's JSON output
 * (plus a non-standard "warmupRawData" field), so the analysis treats both backends alike.
 */
public final class ForkBackend implements Backend {

    @Override
    public String name() {
        return "fork";
    }

    @Override
    public void run(String dataset, int cutoff, int warmup, int measurements, int forks,
                    List<String> jvmArgs, Path dataDir, Path jsonOut, Path log) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<List<Long>> raw = new ArrayList<>();
        List<List<Long>> warm = new ArrayList<>();

        try (BufferedWriter lw = Files.newBufferedWriter(log, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int f = 0; f < forks; f++) {
                List<String> cmd = new ArrayList<>();
                cmd.add(java);
                cmd.addAll(jvmArgs);
                cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), ForkWorker.class.getName(),
                        "--data", dataDir.toAbsolutePath().toString(), "--dataset", dataset,
                        "--cutoff", Integer.toString(cutoff), "--warmup", Integer.toString(warmup),
                        "--measurements", Integer.toString(measurements)));
                lw.write("# Fork: " + (f + 1) + " of " + forks + "  " + String.join(" ", cmd) + "\n");
                ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
                Process p = pb.start();
                List<Long> m = new ArrayList<>(), w = new ArrayList<>();
                boolean done = false;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        lw.write(line + "\n");
                        String[] t = line.trim().split("\\s+");
                        if (t.length == 3 && t[0].equals("M")) m.add(Long.parseLong(t[2]));
                        else if (t.length == 3 && t[0].equals("W")) w.add(Long.parseLong(t[2]));
                        else if (line.trim().equals("DONE")) done = true;
                    }
                }
                int code = p.waitFor();
                lw.flush();
                if (code != 0 || !done || m.size() != measurements)
                    throw new IllegalStateException("Fork " + (f + 1) + " failed (exit " + code + "), see " + log);
                raw.add(m);
                warm.add(w);
            }
        }
        Files.writeString(jsonOut, toJson(dataset, cutoff, dataDir, warmup, measurements, forks, jvmArgs, raw, warm));
    }

    private static String toJson(String dataset, int cutoff, Path dataDir, int warmup, int meas, int forks,
                                 List<String> jvmArgs, List<List<Long>> raw, List<List<Long>> warm) {
        double sum = 0;
        int cnt = 0;
        for (List<Long> f : raw) for (long v : f) { sum += v; cnt++; }
        StringBuilder sb = new StringBuilder();
        sb.append("[\n  {\n");
        sb.append("    \"jmhVersion\" : \"none (pure-JDK fork backend)\",\n");
        sb.append("    \"backend\" : \"fork\",\n");
        sb.append("    \"benchmark\" : \"study.jmh.SortBenchmark.sort\",\n");
        sb.append("    \"mode\" : \"ss\",\n    \"threads\" : 1,\n");
        sb.append("    \"forks\" : ").append(forks).append(",\n");
        sb.append("    \"jvm\" : ").append(q(Path.of(System.getProperty("java.home"), "bin", "java").toString())).append(",\n");
        sb.append("    \"jvmArgs\" : [").append(String.join(", ", jvmArgs.stream().map(ForkBackend::q).toList())).append("],\n");
        sb.append("    \"jdkVersion\" : ").append(q(System.getProperty("java.version"))).append(",\n");
        sb.append("    \"vmName\" : ").append(q(System.getProperty("java.vm.name"))).append(",\n");
        sb.append("    \"warmupIterations\" : ").append(warmup).append(",\n");
        sb.append("    \"measurementIterations\" : ").append(meas).append(",\n");
        sb.append("    \"params\" : {\n");
        sb.append("      \"cutoff\" : \"").append(cutoff).append("\",\n");
        sb.append("      \"dataDir\" : ").append(q(dataDir.toAbsolutePath().toString())).append(",\n");
        sb.append("      \"dataset\" : ").append(q(dataset)).append("\n    },\n");
        sb.append("    \"primaryMetric\" : {\n");
        sb.append("      \"score\" : ").append(String.format(Locale.ROOT, "%.3f", cnt == 0 ? 0 : sum / cnt)).append(",\n");
        sb.append("      \"scoreUnit\" : \"ns/op\",\n");
        sb.append("      \"rawData\" : ").append(arr(raw)).append("\n    },\n");
        sb.append("    \"warmupRawData\" : ").append(arr(warm)).append(",\n");
        sb.append("    \"secondaryMetrics\" : {}\n  }\n]\n");
        return sb.toString();
    }

    private static String arr(List<List<Long>> data) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < data.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('[');
            List<Long> f = data.get(i);
            for (int j = 0; j < f.size(); j++) {
                if (j > 0) sb.append(", ");
                sb.append(f.get(j)).append(".0");
            }
            sb.append(']');
        }
        return sb.append(']').toString();
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
