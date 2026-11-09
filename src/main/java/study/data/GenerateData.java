package study.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import study.util.Cli;

/**
 * Generates every dataset and data/manifest.csv.
 *
 * <pre>
 * java -cp target/benchmarks.jar study.data.GenerateData
 *      [--out data] [--sizes 10000,100000,1000000] [--seeds 5]
 *      [--doubling-sizes 4096,8192,...,1048576]
 * </pre>
 *
 * Group "sweep": 3 sizes x (3 seeded distributions x 5 seeds + 2 deterministic) = 51 datasets.
 * Group "doubling": random, seed 0, N = 2^12 .. 2^20 (doubling test).
 * Re-running overwrites files with the same name: archive data/ + manifest of a finished study first.
 */
public final class GenerateData {

    private GenerateData() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = Cli.parse(args);
        Path out = Paths.get(opt.getOrDefault("out", "data"));
        int[] sizes = Cli.ints(opt.getOrDefault("sizes", "10000,100000,1000000"));
        int seeds = Integer.parseInt(opt.getOrDefault("seeds", "5"));
        int[] dsizes = Cli.ints(opt.getOrDefault("doubling-sizes",
                "4096,8192,16384,32768,65536,131072,262144,524288,1048576"));

        study.bench.BenchSupport.checkIntegerCache();
        Files.createDirectories(out);

        record Spec(String group, String dist, int n, int seed) { }
        List<Spec> specs = new ArrayList<>();
        for (int n : sizes) {
            for (String d : Dataset.SEEDED)
                for (int s = 0; s < seeds; s++) specs.add(new Spec("sweep", d, n, s));
            for (String d : Dataset.DETERMINISTIC) specs.add(new Spec("sweep", d, n, 0));
        }
        for (int n : dsizes) {
            if (specs.stream().noneMatch(s -> s.dist.equals("random") && s.n == n && s.seed == 0))
                specs.add(new Spec("doubling", "random", n, 0));
        }

        StringBuilder manifest = new StringBuilder(Dataset.MANIFEST_HEADER).append('\n');
        long bytes = 0;
        for (Spec s : specs) {
            String id = Dataset.id(s.dist, s.n, s.seed);
            String file = id + ".bin";
            Path f = out.resolve(file);
            Dataset.write(f, Dataset.generate(s.dist, s.n, s.seed));
            String sha = Dataset.sha256(f);
            bytes += Files.size(f);
            manifest.append(new Dataset.Entry(id, s.group, s.dist, s.n, s.seed,
                    Dataset.rngSeed(s.dist, s.n, s.seed), file, sha).toCsv()).append('\n');
            System.out.printf("  %-28s %-8s %,10d  %s...%n", id, s.group, s.n, sha.substring(0, 12));
        }
        Files.writeString(out.resolve("manifest.csv"), manifest.toString());
        long sweep = specs.stream().filter(s -> s.group.equals("sweep")).count();
        System.out.printf("Generated %d datasets (%d sweep + %d doubling), %.1f MB in %s%n",
                specs.size(), sweep, specs.size() - sweep, bytes / 1e6, out.toAbsolutePath());
    }
}
