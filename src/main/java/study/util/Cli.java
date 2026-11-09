package study.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny command-line helper: "--key value" pairs, plus list/range parsing ("0-10,12,15"). */
public final class Cli {

    private Cli() { }

    public static Map<String, String> parse(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("Unexpected argument: " + args[i]);
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) m.put(key, args[++i]);
            else m.put(key, "true");
        }
        return m;
    }

    /** Parses "0-5,7,10-12" into [0,1,2,3,4,5,7,10,11,12] (order kept, duplicates removed). */
    public static int[] ints(String s) {
        List<Integer> out = new ArrayList<>();
        for (String part : s.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int dash = part.indexOf('-', 1);
            if (dash > 0) {
                int a = Integer.parseInt(part.substring(0, dash).trim());
                int b = Integer.parseInt(part.substring(dash + 1).trim());
                for (int x = a; x <= b; x++) if (!out.contains(x)) out.add(x);
            } else {
                int x = Integer.parseInt(part);
                if (!out.contains(x)) out.add(x);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    public static List<String> strings(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }
}
