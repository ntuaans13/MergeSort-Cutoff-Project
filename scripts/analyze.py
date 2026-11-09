#!/usr/bin/env python3
"""
Statistics and figures for the MergeSort CUTOFF experiment.

    python scripts/analyze.py results/grid-YYYYMMDD-HHMMSS [--output DIR] [--doubling results/doubling-...]
    python scripts/analyze.py results/pilot-YYYYMMDD-HHMMSS
    python scripts/analyze.py results/doubling-YYYYMMDD-HHMMSS

The mode (pilot / grid / doubling) is read from the run's config.properties.
Input: one run directory (JMH-format JSON in jmh/, schedule.csv, equivalence.csv, compares.csv, manifest.csv).
Output (default results/analysis-<run name>/):
    raw.csv        every measured iteration            (dataset, cutoff, fork, iteration, ns)
    forks.csv      mean per fork
    datasets.csv   mean of fork means per (dataset, cutoff); equivalent cutoffs filled in (derived=True)
    summary.csv    per (dist, N, cutoff): mean of dataset means, SD, 95% CI, relative time
    README.md      tables + figures, ready to copy into the report (Vietnamese);
                   named README.md so GitHub shows it when the folder is opened
    figures/*.png

Aggregation is hierarchical, matching the design: iterations -> fork mean -> dataset mean -> (dist, N).
With several seeds the SD describes variation between dataset means; with one deterministic dataset it
describes variation between fork means. Mean +/- SD is descriptive; the CI, bootstrap and paired tests
below are what support statistical statements.
"""
import argparse
import json
import math
import os
import re
import sys

import numpy as np
import pandas as pd
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

try:
    from scipy import stats
except ImportError:  # scipy is optional; tests are skipped without it
    stats = None

# ----------------------------------------------------------------------------- style
SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#6250d6", "#e34948"]
INK, INK2, GRID = "#0b0b0b", "#52514e", "#e4e3df"
DIST_ORDER = ["random", "nearly_sorted", "duplicates", "sorted", "reversed"]
DIST_VI = {"random": "random (ngẫu nhiên)", "nearly_sorted": "nearly sorted (gần tăng dần)",
           "duplicates": "duplicates (16 giá trị)", "sorted": "sorted (tăng dần)",
           "reversed": "reversed (giảm dần)"}
plt.rcParams.update({
    "figure.dpi": 150, "savefig.dpi": 150, "font.size": 9,
    "axes.edgecolor": INK2, "axes.labelcolor": INK, "xtick.color": INK2, "ytick.color": INK2,
    "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.6,
    "axes.spines.top": False, "axes.spines.right": False, "legend.frameon": False,
    "lines.linewidth": 1.5,
})


def fmt_n(n):
    n = int(n)
    if n >= 1_000_000 and n % 1_000_000 == 0:
        return f"{n // 1_000_000}M"
    if n >= 1000 and n % 1000 == 0:
        return f"{n // 1000}K"
    return f"{n:,}"


def md_table(df, floatfmt="{:.3f}"):
    cols = list(df.columns)
    out = ["| " + " | ".join(str(c) for c in cols) + " |", "|" + "|".join("---" for _ in cols) + "|"]
    for i in range(len(df)):
        cells = []
        for c in cols:
            v = df[c].iloc[i]
            if isinstance(v, (float, np.floating)):
                cells.append("–" if math.isnan(v) else floatfmt.format(v))
            else:
                cells.append(str(v))
        out.append("| " + " | ".join(cells) + " |")
    return "\n".join(out)


def parse_ints(spec):
    out = []
    for part in str(spec).split(","):
        part = part.strip()
        if not part:
            continue
        m = re.fullmatch(r"(\d+)\s*-\s*(\d+)", part)
        if m:
            out += list(range(int(m.group(1)), int(m.group(2)) + 1))
        else:
            out.append(int(part))
    return sorted(set(out))


def read_props(path):
    props = {}
    if not os.path.exists(path):
        return props
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line.strip() or line.lstrip().startswith(("#", "!")):
                continue
            m = re.match(r"\s*((?:\\.|[^=:\s])+)\s*[=:]\s*(.*)$", line)
            if not m:
                continue
            k, v = m.group(1), m.group(2)
            unesc = lambda s: re.sub(r"\\(.)", r"\1", s)
            props[unesc(k)] = unesc(v)
    return props


def sized(cfg, key, n, default):
    return int(cfg.get(f"{key}.{n}", cfg.get(key, default)))


# ----------------------------------------------------------------------------- loading
def load_run(run_dir):
    cfg = read_props(os.path.join(run_dir, "config.properties"))
    env = read_props(os.path.join(run_dir, "environment.txt"))
    manifest = pd.read_csv(os.path.join(run_dir, "manifest.csv"))
    rows, warm_rows = [], []
    jdir = os.path.join(run_dir, "jmh")
    files = sorted(f for f in os.listdir(jdir) if f.endswith(".json")) if os.path.isdir(jdir) else []
    for fn in files:
        with open(os.path.join(jdir, fn), encoding="utf-8") as f:
            try:
                data = json.load(f)
            except json.JSONDecodeError:
                print(f"skip unreadable {fn}", file=sys.stderr)
                continue
        for b in data:
            p = b["params"]
            ds, c = p["dataset"], int(p["cutoff"])
            unit = b["primaryMetric"].get("scoreUnit", "ns/op")
            scale = {"ns/op": 1.0, "us/op": 1e3, "ms/op": 1e6, "s/op": 1e9}.get(unit, 1.0)
            for fi, fork in enumerate(b["primaryMetric"]["rawData"]):
                for it, v in enumerate(fork):
                    rows.append((ds, c, fi, it, float(v) * scale))
            for fi, fork in enumerate(b.get("warmupRawData", []) or []):
                for it, v in enumerate(fork):
                    warm_rows.append((ds, c, fi, it, float(v) * scale))
    raw = pd.DataFrame(rows, columns=["dataset", "cutoff", "fork", "iteration", "ns"])
    if raw.empty:
        sys.exit(f"No JMH JSON results in {jdir}")
    meta = manifest.set_index("id")[["dist", "n", "seed"]]
    raw = raw.join(meta, on="dataset")
    eq_path = os.path.join(run_dir, "equivalence.csv")
    eq = pd.read_csv(eq_path) if os.path.exists(eq_path) else None
    cmp_path = os.path.join(run_dir, "compares.csv")
    cmp = pd.read_csv(cmp_path) if os.path.exists(cmp_path) else None
    sched_path = os.path.join(run_dir, "schedule.csv")
    sched = pd.read_csv(sched_path) if os.path.exists(sched_path) else None
    return dict(cfg=cfg, env=env, manifest=manifest, raw=raw, eq=eq, cmp=cmp, sched=sched,
                name=os.path.basename(os.path.normpath(run_dir)))


def dataset_level(raw, eq, cutoffs):
    """iterations -> fork means -> dataset means; then fill equivalent cutoffs from their representative."""
    forks = raw.groupby(["dataset", "dist", "n", "seed", "cutoff", "fork"], as_index=False)["ns"].mean()
    ds = forks.groupby(["dataset", "dist", "n", "seed", "cutoff"]).agg(
        mean=("ns", "mean"), fork_sd=("ns", "std"), forks=("ns", "count")).reset_index()
    ds["derived"] = False
    if eq is None or not len(cutoffs):
        return forks, ds
    rep = {(int(r.n), int(r.cutoff)): int(r.representative) for r in eq.itertuples()}
    have = {(d, c) for d, c in zip(ds["dataset"], ds["cutoff"])}
    extra, extra_forks = [], []
    for d, g in ds.groupby("dataset"):
        n = int(g["n"].iloc[0])
        by_c = g.set_index("cutoff")
        for c in cutoffs:
            if (d, c) in have:
                continue
            r = rep.get((n, c))
            if r is None or r not in by_c.index:
                continue
            row = by_c.loc[r].copy()
            row["cutoff"], row["derived"], row["dataset"] = c, True, d
            extra.append(row)
            fr = forks[(forks["dataset"] == d) & (forks["cutoff"] == r)].copy()
            fr["cutoff"] = c
            extra_forks.append(fr)
    if extra:
        ds = pd.concat([ds, pd.DataFrame(extra)[ds.columns]], ignore_index=True)
        forks = pd.concat([forks] + extra_forks, ignore_index=True)
    ds["cutoff"] = ds["cutoff"].astype(int)
    return forks, ds.sort_values(["dist", "n", "seed", "cutoff"]).reset_index(drop=True)


def summarize(ds, near):
    rows = []
    for (dist, n, c), g in ds.groupby(["dist", "n", "cutoff"]):
        k = len(g)
        mean = g["mean"].mean()
        if k >= 2:
            sd = g["mean"].std(ddof=1)
            level = "datasets"
            half = (stats.t.ppf(0.975, k - 1) if stats else 2.0) * sd / math.sqrt(k)
        else:  # one deterministic dataset: variation between fork means
            sd = g["fork_sd"].iloc[0]
            f = int(g["forks"].iloc[0])
            level = "forks"
            half = (stats.t.ppf(0.975, f - 1) if stats and f >= 2 else 2.0) * sd / math.sqrt(max(f, 1))
        rows.append(dict(dist=dist, n=n, cutoff=c, mean_ns=mean, sd_ns=sd, sd_level=level, k=k,
                         ci95_lo=mean - half, ci95_hi=mean + half, derived=bool(g["derived"].all())))
    s = pd.DataFrame(rows)
    s["ns_per_nlgn"] = s["mean_ns"] / (s["n"] * np.log2(s["n"]))
    s["rel"] = s["mean_ns"] / s.groupby(["dist", "n"])["mean_ns"].transform("min")
    s["near_opt"] = s["rel"] <= 1 + near
    return s


def class_range(eq, n, c):
    """[lo, hi] of the cutoffs equivalent to c for size n."""
    if eq is None:
        return c, c
    e = eq[eq["n"] == n]
    r = e.loc[e["cutoff"] == c, "representative"]
    if r.empty:
        return c, c
    members = e.loc[e["representative"] == int(r.iloc[0]), "cutoff"]
    return int(members.min()), int(members.max())


def bootstrap_argmin(forks, B, seed=1):
    """Hierarchical bootstrap: resample datasets, then forks within each dataset."""
    rng = np.random.default_rng(seed)
    out = {}
    for (dist, n), g in forks.groupby(["dist", "n"]):
        cuts = np.sort(g["cutoff"].unique())
        dsets = sorted(g["dataset"].unique())
        nf = int(g.groupby(["dataset", "cutoff"])["fork"].count().min())
        arr = np.full((len(dsets), len(cuts), nf), np.nan)
        for i, d in enumerate(dsets):
            gd = g[g["dataset"] == d]
            for j, c in enumerate(cuts):
                v = gd.loc[gd["cutoff"] == c, "ns"].to_numpy()[:nf]
                arr[i, j, :len(v)] = v
        D = len(dsets)
        di = rng.integers(0, D, size=(B, D))
        fi = rng.integers(0, nf, size=(B, D, nf))
        sel = arr[di]                                   # (B, D, C, F)
        sel = np.take_along_axis(sel, fi[:, :, None, :].repeat(len(cuts), axis=2), axis=3)
        means = np.nanmean(np.nanmean(sel, axis=3), axis=1)   # (B, C)
        am = cuts[np.nanargmin(means, axis=1)]
        out[(dist, n)] = (int(np.percentile(am, 2.5)), int(np.percentile(am, 97.5)))
    return out


def overall(s, near):
    piv = s.pivot_table(index=["dist", "n"], columns="cutoff", values="rel")
    geo_all = np.exp(np.log(piv).mean(axis=0))
    res = pd.DataFrame({"cutoff": geo_all.index, "geomean_rel_all": geo_all.values})
    for d in DIST_ORDER:
        sub = piv[piv.index.get_level_values("dist") == d]
        if len(sub):
            res[f"geomean_rel_{d}"] = np.exp(np.log(sub).mean(axis=0)).values
    col = "geomean_rel_random" if "geomean_rel_random" in res else "geomean_rel_all"
    res["score"] = res[col]
    best = int(res.loc[res["score"].idxmin(), "cutoff"])
    band = res.loc[res["score"] <= res["score"].min() * (1 + near), "cutoff"]
    return res, best, int(band.min()), int(band.max()), col


def paired(ds, cstar, others, dist_filter=None):
    rows = []
    piv = ds.pivot_table(index=["dist", "n", "dataset"], columns="cutoff", values="mean")
    groups = [("random", piv[piv.index.get_level_values("dist") == "random"])]
    groups += [(d, piv[piv.index.get_level_values("dist") == d]) for d in DIST_ORDER if d != "random"]
    groups.append(("TẤT CẢ", piv))
    for label, sub in groups:
        if sub.empty or cstar not in sub.columns:
            continue
        for c in others:
            if c == cstar or c not in sub.columns:
                continue
            lr = np.log((sub[c] / sub[cstar]).dropna().to_numpy())
            k = len(lr)
            if k == 0:
                continue
            gm = math.exp(lr.mean())
            lo = hi = p_w = p_t = float("nan")
            if k >= 3 and stats is not None and lr.std(ddof=1) > 0:
                half = stats.t.ppf(0.975, k - 1) * lr.std(ddof=1) / math.sqrt(k)
                lo, hi = math.exp(lr.mean() - half), math.exp(lr.mean() + half)
                p_t = stats.ttest_1samp(lr, 0.0).pvalue
                if k >= 6 and np.any(lr != 0):
                    p_w = stats.wilcoxon(lr).pvalue
            rows.append({"nhóm": label, "so_sánh": f"{c} vs {cstar}", "datasets": k,
                         "tỉ lệ TB nhân": gm, "CI95 thấp": lo, "CI95 cao": hi,
                         "chậm hơn ở": f"{int((lr > 0).sum())}/{k}", "p (t, log)": p_t, "p (Wilcoxon)": p_w})
    return pd.DataFrame(rows)


def avg_leaf(n, c):
    """Mean length of the subarrays handed to insertion sort (length <= c), mid = lo + (hi-lo)/2."""
    memo = {}

    def rec(L):
        if L in memo:
            return memo[L]
        if L <= max(c, 1):
            memo[L] = (1, L)
        else:
            a, b = rec(L // 2 + L % 2), rec(L // 2)
            memo[L] = (a[0] + b[0], a[1] + b[1])
        return memo[L]

    k, s = rec(int(n))
    return s / k


# ----------------------------------------------------------------------------- figures
def mark_refs(ax):
    for c in (7, 10):
        ax.axvline(c, color=INK2, lw=0.8, ls=":", zorder=0)
        ax.text(c, 1.0, str(c), transform=ax.get_xaxis_transform(), ha="center", va="bottom", fontsize=7, color=INK2)


def grid_axes(dists, height=3.0):
    ncol = 2 if len(dists) > 1 else 1
    nrow = math.ceil(len(dists) / ncol)
    fig, axes = plt.subplots(nrow, ncol, figsize=(9.5, height * nrow + 0.6), squeeze=False, sharex=True)
    for ax in list(axes.flat)[len(dists):]:
        ax.set_visible(False)
    return fig, axes


def fig_time(s, figdir):
    dists = [d for d in DIST_ORDER if d in set(s["dist"])]
    fig, axes = grid_axes(dists)
    ns = sorted(s["n"].unique())
    for ax, dist in zip(axes.flat, dists):
        for i, n in enumerate(ns):
            d = s[(s["dist"] == dist) & (s["n"] == n)].sort_values("cutoff")
            if d.empty:
                continue
            col = SERIES[i % len(SERIES)]
            scale = n * math.log2(n)
            y = d["mean_ns"] / scale
            ax.plot(d["cutoff"], y, color=col, label=f"N = {fmt_n(n)}", drawstyle="steps-mid")
            ax.fill_between(d["cutoff"], (d["mean_ns"] - d["sd_ns"]) / scale, (d["mean_ns"] + d["sd_ns"]) / scale,
                            color=col, alpha=0.13, lw=0, step="mid")
            m = d[~d["derived"]]
            ax.errorbar(m["cutoff"], m["mean_ns"] / scale, yerr=m["sd_ns"] / scale, fmt="none",
                        ecolor=col, elinewidth=0.8, capsize=2)
            b = d.loc[d["mean_ns"].idxmin()]
            ax.plot([b["cutoff"]], [b["mean_ns"] / scale], "o", ms=6, color=col, mec="white", mew=1.5, zorder=5)
        mark_refs(ax)
        ax.set_title(DIST_VI.get(dist, dist), loc="left", fontsize=9, color=INK, pad=12)
        ax.set_ylabel("ns / (N lg N)")
    for ax in axes[-1]:
        ax.set_xlabel("CUTOFF")
    h, l = axes.flat[0].get_legend_handles_labels()
    fig.legend(h, l, loc="upper right", ncol=len(ns), fontsize=8)
    fig.suptitle("Thời gian chuẩn hoá theo CUTOFF - Mean ± SD (thanh: điểm đo thật; bậc thang: cùng cây đệ quy)",
                 x=0.01, ha="left", fontsize=10, color=INK)
    fig.tight_layout(rect=(0, 0, 1, 0.95))
    fig.savefig(os.path.join(figdir, "fig1_time_vs_cutoff.png"))
    plt.close(fig)


def fig_heatmap(s, figdir):
    piv = s.pivot_table(index=["dist", "n"], columns="cutoff", values="rel")
    piv = piv.reindex(sorted(piv.index, key=lambda t: (DIST_ORDER.index(t[0]) if t[0] in DIST_ORDER else 9, t[1])))
    fig, ax = plt.subplots(figsize=(10, 0.3 * len(piv) + 1.5))
    im = ax.imshow(np.clip(piv.values, 1.0, 1.3), aspect="auto", cmap="Blues", vmin=1.0, vmax=1.3)
    ax.set_yticks(range(len(piv)))
    ax.set_yticklabels([f"{d}  N={fmt_n(n)}" for d, n in piv.index], fontsize=7)
    cuts = list(piv.columns)
    step = max(1, len(cuts) // 22)
    ax.set_xticks(range(0, len(cuts), step))
    ax.set_xticklabels(cuts[::step], fontsize=7)
    ax.grid(False)
    for i, row in enumerate(piv.values):
        j = int(np.nanargmin(row))
        ax.plot(j, i, "o", ms=4, color="white", mec=INK, mew=0.8)
    ax.set_xlabel("CUTOFF")
    cb = fig.colorbar(im, ax=ax, fraction=0.025, pad=0.01)
    cb.set_label("thời gian tương đối (1.0 = tốt nhất; cắt ở 1.3)", fontsize=7)
    ax.set_title("Thời gian tương đối theo CUTOFF cho từng cấu hình (chấm = tốt nhất)", loc="left", fontsize=10, color=INK)
    fig.tight_layout()
    fig.savefig(os.path.join(figdir, "fig2_heatmap_relative.png"))
    plt.close(fig)


def fig_overall(score, cstar, lo, hi, col, figdir):
    fig, ax = plt.subplots(figsize=(8.5, 3.4))
    ax.axvspan(lo - 0.5, hi + 0.5, color=SERIES[0], alpha=0.10, lw=0)
    ax.plot(score["cutoff"], score[col], color=SERIES[0], marker="o", ms=2.5, label=col.replace("geomean_rel_", ""))
    if col != "geomean_rel_all":
        ax.plot(score["cutoff"], score["geomean_rel_all"], color=SERIES[1], marker="o", ms=2.5, label="mọi phân phối")
    yb = float(score.loc[score["cutoff"] == cstar, col].iloc[0])
    ax.annotate(f"CUTOFF* = {cstar}", (cstar, yb), xytext=(0, 30), textcoords="offset points", ha="center",
                fontsize=8, color=INK, arrowprops=dict(arrowstyle="-", color=INK2, lw=0.8))
    mark_refs(ax)
    ax.set_xlabel("CUTOFF")
    ax.set_ylabel("trung bình nhân của\nthời gian tương đối")
    ax.legend(loc="upper right")
    ax.set_title(f"Điểm tổng hợp - vùng tô: trong 2% so với tốt nhất ({lo}–{hi})", loc="left", fontsize=10,
                 color=INK, pad=12)
    fig.tight_layout()
    fig.savefig(os.path.join(figdir, "fig3_overall_score.png"))
    plt.close(fig)


def fig_paired(ds, cstar, others, figdir):
    d = ds[ds["dist"] == "random"]
    if d.empty:
        d = ds
    piv = d.pivot_table(index=["n", "dataset"], columns="cutoff", values="mean")
    cs = [c for c in others if c in piv.columns and c != cstar]
    if cstar not in piv.columns or not cs:
        return
    fig, ax = plt.subplots(figsize=(7, 3.4))
    ns = sorted(piv.index.get_level_values("n").unique())
    for j, c in enumerate(cs):
        for i, n in enumerate(ns):
            sub = piv.loc[n]
            r = (sub[c] / sub[cstar] - 1) * 100
            x = j + (i - (len(ns) - 1) / 2) * 0.15
            ax.scatter(np.full(len(r), x), r, s=18, color=SERIES[i % len(SERIES)], label=f"N = {fmt_n(n)}" if j == 0 else None,
                       edgecolor="white", linewidth=0.6, zorder=3)
    ax.axhline(0, color=INK2, lw=0.8)
    ax.set_xticks(range(len(cs)))
    ax.set_xticklabels([f"CUTOFF {c}" for c in cs])
    ax.set_ylabel(f"chậm hơn CUTOFF* = {cstar} (%)")
    ax.legend(loc="upper right", fontsize=8)
    ax.set_title("So sánh cặp trên từng dataset random (mỗi chấm = 1 dataset)", loc="left", fontsize=10, color=INK)
    fig.tight_layout()
    fig.savefig(os.path.join(figdir, "fig4_paired.png"))
    plt.close(fig)


def fig_compares(cmp, figdir):
    c = cmp.copy()
    c["y"] = c["compares"] / (c["n"] * np.log2(c["n"]))
    dists = [d for d in DIST_ORDER if d in set(c["dist"])]
    fig, axes = grid_axes(dists, 2.8)
    ns = sorted(c["n"].unique())
    for ax, dist in zip(axes.flat, dists):
        for i, n in enumerate(ns):
            d = c[(c["dist"] == dist) & (c["n"] == n)].sort_values("cutoff")
            if d.empty:
                continue
            col = SERIES[i % len(SERIES)]
            ax.plot(d["cutoff"], d["y"], color=col, label=f"N = {fmt_n(n)}", drawstyle="steps-mid")
            b = d.loc[d["compares"].idxmin()]
            ax.plot([b["cutoff"]], [b["y"]], "o", ms=5, color=col, mec="white", mew=1.2, zorder=5)
        mark_refs(ax)
        ax.set_title(DIST_VI.get(dist, dist), loc="left", fontsize=9, color=INK, pad=12)
        ax.set_ylabel("compares / (N lg N)")
    for ax in axes[-1]:
        ax.set_xlabel("CUTOFF")
    h, l = axes.flat[0].get_legend_handles_labels()
    fig.legend(h, l, loc="upper right", ncol=len(ns), fontsize=8)
    fig.suptitle("Số phép so sánh theo CUTOFF (đếm chính xác, không phụ thuộc máy)", x=0.01, ha="left", fontsize=10, color=INK)
    fig.tight_layout(rect=(0, 0, 1, 0.95))
    fig.savefig(os.path.join(figdir, "fig5_compares.png"))
    plt.close(fig)


# ----------------------------------------------------------------------------- environment section
def env_table(run):
    env, cfg, raw = run["env"], run["cfg"], run["raw"]
    rows = [("CPU", env.get("cpu", "?")), ("Số luồng logic", env.get("available.processors", "?")),
            ("Hệ điều hành", env.get("os", "?")),
            ("JDK", f"{env.get('java.vm.name', '?')} {env.get('java.version', '?')} ({env.get('java.vendor', '')})"),
            ("Backend đo", env.get("backend", "?")), ("Tham số JVM (fork)", env.get("fork.jvmArgs", "?")),
            ("SHA-256 MergeSort.class", env.get("mergesort.class.sha256", "?")[:16] + "…"),
            ("Thời điểm", env.get("timestamp", "?")), ("Lượt chạy", run["name"])]
    return md_table(pd.DataFrame(rows, columns=["Mục", "Giá trị"]))


# ----------------------------------------------------------------------------- pilot
def analyze_pilot(run, out, figdir):
    raw, cfg = run["raw"], run["cfg"]
    L = ["# Pilot: chọn số vòng warm-up\n", f"Số liệu gốc: [`{run['name']}`](../{run['name']})\n",
         "## Môi trường\n", env_table(run) + "\n"]
    rows, curves = [], []
    for (ds, c, n), g in raw.groupby(["dataset", "cutoff", "n"]):
        W, M = sized(cfg, "warmup", n, 20), sized(cfg, "measurements", n, 10)
        for f, gf in g.groupby("fork"):
            v = gf.sort_values("iteration")["ns"].to_numpy()
            if len(v) < W + M:
                continue
            final = v[W:W + M].mean()
            rec = {"dataset": ds, "n": n, "cutoff": c, "fork": f, "W": W, "M": M, "final_mean_ms": final / 1e6,
                   "drift_%/iter": 100 * np.polyfit(np.arange(M), v[W:W + M], 1)[0] / final}
            for w in [0, 5, 10, 15, 20, 30, 50, 75, 100, 150, 200, 300]:
                if w < W:   # window [w, w+M) compared with the reference window [W, W+M)
                    rec[f"dev_w{w}"] = abs(v[w:w + M].mean() / final - 1) * 100
            rows.append(rec)
            curves.append((ds, c, f, v / final, W))
    t = pd.DataFrame(rows)
    t.to_csv(os.path.join(out, "pilot_forks.csv"), index=False)

    devcols = sorted([c for c in t.columns if c.startswith("dev_w")], key=lambda c: int(c[5:]))
    L.append("## 1. Warm-up bao nhiêu là đủ?\n")
    L.append("Với mỗi fork, so sánh trung bình M vòng bắt đầu từ vòng w với trung bình M vòng sau W vòng warm-up "
             "(cửa sổ tham chiếu, coi là trạng thái ổn định). Độ lệch (%) tính trên mọi dataset × CUTOFF × fork, "
             "tách theo N vì mỗi N có thể dùng W khác nhau. Chỉ kiểm tra được w < W.\n")
    sugg = []
    for n, tn in t.groupby("n"):
        W = int(tn["W"].iloc[0])
        cols = [c for c in devcols if tn[c].notna().any()]
        cand = pd.DataFrame({"warm-up w": [int(c[5:]) for c in cols],
                             "lệch TB (%)": [tn[c].mean() for c in cols],
                             "lệch lớn nhất (%)": [tn[c].max() for c in cols]})
        L.append(f"**N = {fmt_n(n)}** (W = {W}, M = {int(tn['M'].iloc[0])}, {tn['fork'].nunique()} forks):\n")
        L.append(md_table(cand, "{:.2f}") + "\n")
        ok = cand[cand["lệch lớn nhất (%)"] <= 2.0]
        if len(ok):
            w = int(ok["warm-up w"].min())
            sugg.append(f"N = {fmt_n(n)}: w = {w} đã lệch ≤ 2% → W = {W} đủ (có dư).")
        else:
            okm = cand[cand["lệch TB (%)"] <= 2.0]
            extra = (f" (trung bình đã ≤ 2% từ w = {int(okm['warm-up w'].min())})" if len(okm) else "")
            sugg.append(f"N = {fmt_n(n)}: chưa có w < {W} lệch lớn nhất ≤ 2%{extra}; đánh giá thêm bằng CV giữa fork "
                        "và xu hướng ở mục 2, hoặc chạy pilot với W lớn hơn.")
    L.append("**Gợi ý:**\n\n" + "\n".join("- " + x for x in sugg) + "\n")

    fm = t.groupby(["dataset", "cutoff"]).agg(fork_means_ms=("final_mean_ms", lambda x: ", ".join(f"{v:.3f}" for v in x)),
                                               cv_forks=("final_mean_ms", lambda x: 100 * x.std(ddof=1) / x.mean()),
                                               drift=("drift_%/iter", "mean")).reset_index()
    fm = fm.rename(columns={"fork_means_ms": "mean từng fork (ms)", "cv_forks": "CV giữa fork (%)",
                            "drift": "xu hướng trong measurement (%/vòng)"})
    L.append("## 2. Ổn định giữa các fork và xu hướng trong measurement\n")
    L.append("CV giữa fork lớn (> vài %) nghĩa là các JVM cho kết quả khác nhau: cần nhiều fork hơn hoặc kiểm tra "
             "JIT/GC/tải máy. Xu hướng khác 0 rõ rệt nghĩa là measurement vẫn đang trôi (chưa ổn định).\n")
    L.append(md_table(fm, "{:.2f}") + "\n")

    # figure: warm-up curves
    dsets = sorted(raw["dataset"].unique(), key=lambda d: (raw.loc[raw["dataset"] == d, "n"].iloc[0], d))
    fig, axes = plt.subplots(len(dsets), 1, figsize=(9, 2.4 * len(dsets) + 0.6), squeeze=False)
    cuts = sorted(raw["cutoff"].unique())
    for ax, ds in zip(axes[:, 0], dsets):
        W = None
        for (d, c, f, y, w) in curves:
            if d != ds:
                continue
            W = w
            col = SERIES[cuts.index(c) % len(SERIES)]
            ax.plot(np.arange(len(y)), y, color=col, lw=0.9, alpha=0.75, label=f"CUTOFF {c}" if f == 0 else None)
        if W is not None:
            ax.axvline(W - 0.5, color=INK2, ls="--", lw=0.8)
            ax.text(W - 0.5, 1.0, " bắt đầu đo", transform=ax.get_xaxis_transform(), fontsize=7, color=INK2, va="bottom")
        ys = np.concatenate([y for (d, c, f, y, w) in curves if d == ds]) if curves else np.array([1.0])
        ax.set_ylim(min(0.9, float(np.nanmin(ys))) * 0.95, min(2.0, max(1.1, float(np.nanmax(ys)) * 1.02)))
        ax.set_ylabel("t / t ổn định")
        ax.set_title(ds, loc="left", fontsize=9, color=INK)
    axes[-1, 0].set_xlabel("vòng (iteration)")
    axes[0, 0].legend(loc="upper right", ncol=len(cuts), fontsize=7)
    fig.suptitle("Đường cong warm-up (mỗi đường = 1 fork; chuẩn hoá theo trung bình M vòng cuối)", x=0.01,
                 ha="left", fontsize=10, color=INK)
    fig.tight_layout(rect=(0, 0, 1, 0.96))
    fig.savefig(os.path.join(figdir, "pilot_warmup_curves.png"))
    plt.close(fig)
    L.append("![Warm-up](figures/pilot_warmup_curves.png)\n")
    L.append("> Không gộp pilot vào kết quả chính và không chọn CUTOFF từ pilot.\n")
    with open(os.path.join(out, "README.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(L))


# ----------------------------------------------------------------------------- doubling
def doubling_section(run, figdir, split=131072):
    """Doubling test. Power law T(N) = a*N^b fitted separately on the cache-resident range
    (smallest N excluded, N <= split) and on the large range (N >= split)."""
    raw = run["raw"]
    cuts = parse_ints(run["cfg"].get("resolved.cutoffs", ""))
    forks, ds = dataset_level(raw, run["eq"], cuts)
    t = ds.groupby(["cutoff", "n"])["mean"].mean().reset_index()
    derived = ds[ds["derived"]].groupby("cutoff")["n"].apply(lambda x: ", ".join(fmt_n(v) for v in sorted(x)))
    rows, fits = [], []
    fig, ax = plt.subplots(figsize=(7, 4))
    for i, (c, g) in enumerate(t.groupby("cutoff")):
        g = g.sort_values("n")
        ax.loglog(g["n"], g["mean"] / 1e9, marker="o", ms=4, color=SERIES[i % len(SERIES)], label=f"CUTOFF = {c}", base=2)
        prev = None
        for r in g.itertuples():
            ratio = r.mean / prev if prev else float("nan")
            theory = 2 * (1 + 1 / math.log2(r.n / 2)) if prev else float("nan")
            rows.append({"CUTOFF": int(c), "N": f"{int(r.n):,}", "T (ms)": r.mean / 1e6,
                         "T(2N)/T(N)": ratio, "lý thuyết N lg N": theory})
            prev = r.mean
        for label, sub in ((f"{fmt_n(g['n'].iloc[1]) if len(g) > 1 else '?'}–{fmt_n(split)}",
                            g[(g["n"] > g["n"].min()) & (g["n"] <= split)]),
                           (f"{fmt_n(split)}–{fmt_n(g['n'].max())}", g[g["n"] >= split])):
            if len(sub) < 2:
                continue
            b, lga = np.polyfit(np.log2(sub["n"]), np.log2(sub["mean"] / 1e9), 1)
            n0, n1 = sub["n"].min(), sub["n"].max()
            b_th = 1 + math.log2(math.log2(n1) / math.log2(n0)) / math.log2(n1 / n0)
            fits.append({"CUTOFF": int(c), "khoảng N": label, "điểm": len(sub), "b đo": b,
                         "b lý thuyết (N lg N)": b_th, "a (s)": 2 ** lga})
    ax.axvline(split, color=INK2, ls=":", lw=0.8)
    ax.set_xlabel("N (thang log)")
    ax.set_ylabel("thời gian (s, thang log)")
    ax.legend()
    ax.set_title("Doubling test: T(N) trên đồ thị log-log (random)", loc="left", fontsize=10, color=INK)
    fig.tight_layout()
    fig.savefig(os.path.join(figdir, "fig6_doubling_loglog.png"))
    plt.close(fig)
    L = ["## Doubling test (random)\n",
         "Cột *lý thuyết N lg N* là tỉ số T(2N)/T(N) mà mô hình c·N lg N dự đoán: 2·(1 + 1/lg N).\n",
         md_table(pd.DataFrame(rows), "{:.4g}") + "\n",
         f"Mô hình luỹ thừa T(N) ≈ a·N^b khớp riêng trên hai khoảng, tách tại N = {fmt_n(split)} "
         "(bỏ điểm N nhỏ nhất). *b lý thuyết* là độ dốc log-log của N lg N trên cùng khoảng: b đo xấp xỉ b lý thuyết "
         "nghĩa là khớp N lg N; b đo lớn hơn rõ rệt ở **mọi** CUTOFF cho thấy ảnh hưởng của hệ thống bộ nhớ, "
         "không phải của cutoff. CUTOFF chủ yếu đổi hằng số a.\n",
         md_table(pd.DataFrame(fits), "{:.4g}") + "\n",
         "![Doubling](figures/fig6_doubling_loglog.png)\n"]
    if len(derived):
        L.append("Ghi chú: với N = 2^k, một số CUTOFF cho cùng cây đệ quy với CUTOFF khác trong danh sách nên số liệu "
                 "được lấy từ CUTOFF tương đương (không đo lặp): "
                 + "; ".join(f"CUTOFF {c} tại N = {v}" for c, v in derived.items()) + ".\n")
    return "\n".join(L)


# ----------------------------------------------------------------------------- grid
def analyze_grid(run, out, figdir, near, B, doubling_dir):
    cfg, raw, eq = run["cfg"], run["raw"], run["eq"]
    cutoffs = parse_ints(cfg.get("resolved.cutoffs", cfg.get("cutoffs", "")))
    forks, ds = dataset_level(raw, eq, cutoffs)
    s = summarize(ds, near)
    boot = bootstrap_argmin(forks, B)
    score, cstar, lo, hi, col = overall(s, near)
    others = [c for c in (0, 7, 10) if c in cutoffs]
    pt = paired(ds, cstar, others)

    raw.to_csv(os.path.join(out, "raw.csv"), index=False)
    forks.to_csv(os.path.join(out, "forks.csv"), index=False)
    ds.to_csv(os.path.join(out, "datasets.csv"), index=False)
    s.to_csv(os.path.join(out, "summary.csv"), index=False)
    score.to_csv(os.path.join(out, "overall_score.csv"), index=False)
    pt.to_csv(os.path.join(out, "paired.csv"), index=False)
    results_root = os.path.dirname(os.path.normpath(out)) or "."
    with open(os.path.join(results_root, "best_cutoff.txt"), "w") as f:
        f.write(str(cstar))

    fig_time(s, figdir)
    fig_heatmap(s, figdir)
    fig_overall(score, cstar, lo, hi, col, figdir)
    fig_paired(ds, cstar, others, figdir)
    if run["cmp"] is not None:
        fig_compares(run["cmp"], figdir)

    measured = raw.groupby(["dataset", "cutoff"]).ngroups
    L = ["# Kết quả thí nghiệm CUTOFF cho MergeSort (tự sinh bởi scripts/analyze.py)\n",
         "> Dùng bảng/hình ở đây cho phần *Kết quả*; phần *Phân tích* hãy tự viết từ số liệu này.\n",
         f"Số liệu gốc: [`{run['name']}`](../{run['name']})"
         + (f" · doubling: [`{os.path.basename(os.path.normpath(doubling_dir))}`](../{os.path.basename(os.path.normpath(doubling_dir))})"
            if doubling_dir else "") + "\n",
         "## 1. Môi trường và quy mô đo\n", env_table(run) + "\n",
         f"- {raw['dataset'].nunique()} datasets × {len(cutoffs)} CUTOFF ({cutoffs[0]}–{cutoffs[-1]}); "
         f"đo thật {measured} cấu hình, các CUTOFF còn lại được suy ra từ lớp tương đương (cùng cây đệ quy).",
         f"- {len(raw):,} lần sort được ghi nhận; warm-up/measurement/forks theo config.properties của lượt chạy.\n"]

    r = score.set_index("cutoff")["score"]
    L.append("## 2. CUTOFF khuyến nghị\n")
    L.append(f"- **CUTOFF\\* = {cstar}**: nhỏ nhất theo trung bình nhân thời gian tương đối trên các cấu hình "
             f"**{col.replace('geomean_rel_', '')}** (mọi N).")
    L.append(f"- Vùng trong {near:.0%} so với tốt nhất: **{lo}–{hi}**.")
    for c in others:
        if c != cstar:
            L.append(f"- CUTOFF = {c}: chậm hơn CUTOFF\\* **{(r[c] / r[cstar] - 1) * 100:+.1f}%** (trung bình nhân).")
    c_all = int(score.loc[score["geomean_rel_all"].idxmin(), "cutoff"])
    L.append(f"- Tính trên mọi phân phối, tốt nhất là CUTOFF = {c_all}. Các phân phối có thể kéo theo hướng khác nhau "
             "(dữ liệu gần sắp xếp thường ưa CUTOFF lớn, dữ liệu giảm dần thường ưa CUTOFF nhỏ).\n")
    L.append("![Điểm tổng hợp](figures/fig3_overall_score.png)\n")

    L.append("## 3. Theo từng (phân phối, N)\n")
    L.append("`lớp tốt nhất` = các CUTOFF cho cùng cây đệ quy với CUTOFF tốt nhất (không phân biệt được bằng đo). "
             "`bootstrap 95%` = khoảng chứa argmin khi lấy mẫu lại dataset và fork. `rel_X` = thời gian của CUTOFF X / tốt nhất.\n")
    rows = []
    for (dist, n), g in s.groupby(["dist", "n"]):
        b = g.loc[g["mean_ns"].idxmin()]
        a, z = class_range(eq, n, int(b["cutoff"]))
        band = g.loc[g["near_opt"], "cutoff"]
        rr = g.set_index("cutoff")["rel"]
        bl = boot.get((dist, n), (np.nan, np.nan))
        rows.append({"_n": n, "dist": dist, "N": fmt_n(n), "k": int(b["k"]), "lớp tốt nhất": f"{a}–{z}",
                     "vùng ≤2%": f"{int(band.min())}–{int(band.max())}", "bootstrap 95%": f"{bl[0]}–{bl[1]}",
                     **{f"rel_{c}": rr.get(c, np.nan) for c in others},
                     "t tốt nhất (ms)": b["mean_ns"] / 1e6, "SD (ms)": b["sd_ns"] / 1e6, "SD giữa": b["sd_level"]})
    bt = pd.DataFrame(rows)
    bt["_o"] = bt["dist"].map(lambda d: DIST_ORDER.index(d) if d in DIST_ORDER else 9)
    bt = bt.sort_values(["_o", "_n"]).drop(columns=["_o", "_n"])
    bt.to_csv(os.path.join(out, "best_by_config.csv"), index=False)
    L.append(md_table(bt) + "\n")
    L.append("![Thời gian theo CUTOFF](figures/fig1_time_vs_cutoff.png)\n")
    L.append("![Heatmap](figures/fig2_heatmap_relative.png)\n")

    L.append("## 4. So sánh cặp với CUTOFF\\* (đơn vị = dataset)\n")
    L.append("Mỗi dataset cho một tỉ số t(CUTOFF)/t(CUTOFF\\*) từ dataset means (đã gộp fork). Tỉ lệ > 1 nghĩa là chậm hơn. "
             "CI95 và p (t) dùng phân phối t trên log tỉ số (cần ≥ 3 dataset); Wilcoxon chỉ tính khi có ≥ 6 dataset.\n")
    if not pt.empty:
        L.append(md_table(pt, "{:.4g}") + "\n")
    L.append("![So sánh cặp](figures/fig4_paired.png)\n")

    if {0, 1} <= set(raw["cutoff"].unique()):
        piv = ds[~ds["derived"]].pivot_table(index="dataset", columns="cutoff", values="mean")
        if 0 in piv and 1 in piv:
            q = (piv[1] / piv[0]).dropna()
            L.append("## 5. Kiểm tra nhiễu: CUTOFF 0 và 1\n")
            L.append(f"Theo hợp đồng cài đặt, CUTOFF 1 làm cùng công việc với 0, nên t(1)/t(0) lý tưởng = 1. Trên {len(q)} "
                     f"dataset: trung vị {q.median():.4f}, nhỏ nhất {q.min():.4f}, lớn nhất {q.max():.4f}. "
                     "Độ lệch này là mức nhiễu đo: chênh lệch giữa các CUTOFF nhỏ hơn mức này không nên được diễn giải.\n")

    L.append("## 6. Lớp tương đương và kích thước lá (hiện tượng bậc thang)\n")
    L.append("Top-down mergesort chỉ tạo các đoạn con dài ≈ N/2^k, nên nhiều CUTOFF liên tiếp cho **cùng** một cây đệ quy. "
             "Các lớp dưới đây được tính chính xác từ cây và đã được kiểm chứng trên cài đặt của bạn bằng chuỗi phép so sánh.\n")
    if eq is not None:
        for n, g in eq.groupby("n"):
            parts = []
            for rep, gg in g.groupby("representative"):
                a, z = gg["cutoff"].min(), gg["cutoff"].max()
                parts.append(f"{a}" if a == z else f"{a}–{z}")
            L.append(f"- N = {fmt_n(n)}: " + ", ".join(parts))
        L.append("")
    sel = [c for c in (1, 4, 7, 8, 10, 12, 15, 16, 20, 24, 31, 32, 48, 64) if c in cutoffs]
    ns = sorted(raw["n"].unique())
    leaf = pd.DataFrame({"CUTOFF": sel, **{f"lá TB, N={fmt_n(n)}": [avg_leaf(n, c) for c in sel] for n in ns}})
    L.append(md_table(leaf, "{:.1f}") + "\n")

    if run["cmp"] is not None:
        cm = run["cmp"]
        cmr = cm[cm["dist"] == "random"] if "random" in set(cm["dist"]) else cm
        nmax = cmr["n"].max()
        cmr = cmr[cmr["n"] == nmax].set_index("cutoff")["compares"]
        sel2 = [c for c in sorted(set([0, 4, 7, 10, cstar, 16, 24, 32, 48, 64])) if c in cmr.index]
        L.append("## 7. Số phép so sánh (không phụ thuộc máy)\n")
        L.append(f"Random, N = {fmt_n(nmax)}. Số so sánh nhỏ nhất ở CUTOFF = {int(cmr.idxmin())}, trong khi thời gian nhỏ nhất ở "
                 f"CUTOFF\\* = {cstar}: một bước insertion sort rẻ hơn một bước merge (không copy sang aux, không đệ quy).\n")
        L.append(md_table(pd.DataFrame({"CUTOFF": sel2, "compares": [int(cmr[c]) for c in sel2],
                                        "so với CUTOFF 0": [cmr[c] / cmr[0] for c in sel2] if 0 in cmr.index else np.nan})) + "\n")
        L.append("![Số phép so sánh](figures/fig5_compares.png)\n")

    if doubling_dir:
        L.append(doubling_section(load_run(doubling_dir), figdir))

    with open(os.path.join(out, "README.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(L))
    print(f"CUTOFF* = {cstar}  (within {near:.0%}: {lo}-{hi});  wrote {os.path.join(results_root, 'best_cutoff.txt')}")


# ----------------------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("run_dir")
    ap.add_argument("--output", default=None)
    ap.add_argument("--doubling", default=None, help="doubling run directory to include in the analysis README.md")
    ap.add_argument("--near", type=float, default=0.02)
    ap.add_argument("--boot", type=int, default=1000)
    a = ap.parse_args()

    run = load_run(a.run_dir)
    mode = run["cfg"].get("mode", "grid")
    out = a.output or os.path.join(os.path.dirname(os.path.normpath(a.run_dir)), "analysis-" + run["name"])
    figdir = os.path.join(out, "figures")
    os.makedirs(figdir, exist_ok=True)
    if stats is None:
        print("NOTE: scipy not installed - confidence intervals use 2.0 instead of t quantiles, tests skipped.")

    if mode == "pilot":
        analyze_pilot(run, out, figdir)
    elif mode == "doubling":
        run["raw"].to_csv(os.path.join(out, "raw.csv"), index=False)
        with open(os.path.join(out, "README.md"), "w", encoding="utf-8") as f:
            f.write(f"# Doubling test\n\nSố liệu gốc: [`{run['name']}`](../{run['name']})\n\n"
                    + env_table(run) + "\n\n" + doubling_section(run, figdir))
    else:
        analyze_grid(run, out, figdir, a.near, a.boot, a.doubling)
    print(f"Wrote {out}/README.md and figures/")


if __name__ == "__main__":
    main()
