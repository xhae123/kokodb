#!/usr/bin/env python3
"""Rebuild README artwork from the checked-in experiment; requires Matplotlib."""

import csv
from pathlib import Path
import xml.etree.ElementTree as ET

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from brand import main as render_brand

HERE = Path(__file__).resolve().parent
DATA = HERE.parent / "baselines/2026-10-23-engine-comparison"
CATALOG = HERE.parent / "baselines/2026-10-23-catalog-cli"
INK, MUTED, BG = "#2b1b43", "#6b5c7d", "#f8f6fc"
COLORS = ["#7c3aed", "#637fba"]
plt.rcParams.update({"font.family": ["DejaVu Sans", "Arial", "sans-serif"], "font.size": 17,
                     "text.color": INK, "axes.labelcolor": MUTED,
                     "xtick.color": MUTED, "ytick.color": INK,
                     "svg.fonttype": "none", "svg.hashsalt": "kokodb-readme"})


def save(fig, name, description):
    fig.savefig(HERE / f"{name}.png", dpi=150, facecolor=fig.get_facecolor())
    path = HERE / f"{name}.svg"
    fig.savefig(path, metadata={"Date": None, "Description": description}, facecolor=fig.get_facecolor())
    path.write_text("\n".join(line.rstrip() for line in path.read_text().splitlines()) + "\n")
    ET.parse(path)
    plt.close(fig)


def bars(ax, values, title, unit, errors=None, fmt=".1f"):
    ax.set_facecolor(BG)
    ax.barh([1, 0], values, height=.5, color=COLORS, xerr=errors,
            error_kw={"ecolor": INK, "capsize": 5, "elinewidth": 1.4})
    ax.set_yticks([1, 0], ["KoKoDB", "H2"])
    ax.set_ylim(-.65, 1.7)
    largest = max(v + (errors[1][i] if errors else 0) for i, v in enumerate(values))
    ax.set_xlim(0, largest * 1.3)
    ax.set_title(title, fontsize=20, weight="bold", loc="left", pad=15)
    ax.set_xlabel(unit, fontsize=17)
    ax.xaxis.grid(True, alpha=.15)
    ax.set_axisbelow(True)
    ax.tick_params(axis="x", length=0, labelsize=18)
    ax.tick_params(axis="y", length=0, labelsize=20)
    for spine in ax.spines.values():
        spine.set_visible(False)
    for i, value in enumerate(values):
        offset = errors[1][i] if errors else 0
        ax.text(value + offset + largest*.025, 1-i, format(value, fmt), va="center", fontsize=20, weight="bold")


def startup():
    rows = list(csv.DictReader((DATA / "startup/summary.csv").open()))
    data = [next(r for r in rows if r["engine"] == e and r["mode"] == "first") for e in ("kokodb", "h2")]
    fig, axes = plt.subplots(2, 1, figsize=(8.5, 8.6), facecolor=BG)
    fig.subplots_adjust(left=.20, right=.92, top=.81, bottom=.24, hspace=.95)
    fig.text(.06, .95, "The first result costs less here.", fontsize=23, weight="bold", va="top")
    for ax, metric, scale, title, unit in (
        (axes[0], "wall_ms", 1, "Launch to verified result and exit", "milliseconds · lower is better"),
        (axes[1], "max_rss_bytes", 2**20, "Peak whole-process memory", "MiB · lower is better"),
    ):
        values = [float(r[f"median_{metric}"])/scale for r in data]
        errors = [[v-float(r[f"min_{metric}"])/scale for v,r in zip(values,data)],
                  [float(r[f"max_{metric}"])/scale-v for v,r in zip(values,data)]]
        bars(ax, values, title, unit, errors)
    fig.text(.06, .065, "One row, memory mode · 20 fresh JVMs per engine\nMedian + full range · M1 / JDK 17 · H2 2.5.252", fontsize=14, color=MUTED)
    save(fig, "startup-comparison", "Memory database first-result lifecycle, median and full observed range; peak RSS includes the JVM. Source: 2026-10-23 engine comparison.")


def queries():
    models = list(csv.DictReader((DATA / "models/summary.csv").open()))
    fig, axes = plt.subplots(2, 1, figsize=(8.5, 8.6), facecolor=BG)
    fig.subplots_adjust(left=.20, right=.92, top=.81, bottom=.24, hspace=.95)
    fig.text(.06, .95, "Warm Kotlin model queries", fontsize=24, weight="bold", va="top")
    for ax, records, case, count, title in (
        (axes[0], models, "models", "1000", "Materialize 1,000 Kotlin models"),
        (axes[1], models, "models", "1", "Materialize one Kotlin model"),
    ):
        rows = [next(r for r in records if r["engine"]==e and r["rows"]==count and r["case"]==case)
                for e in ("kokodb", "h2")]
        values = [float(r["mean_us_per_operation"]) for r in rows]
        errors = [float(r["error_us"]) for r in rows]
        bars(ax, values, title, "microseconds / query · lower is better", [errors, errors], ".2f")
    fig.text(.06, .065, "Warm memory SELECT · 3 JVM forks per case\nMean + JMH 99.9% CI · M1 / JDK 17 · H2 2.5.252", fontsize=14, color=MUTED)
    save(fig, "query-comparison", "Warm complete-model SELECT at 1000 and 1 rows; mean and JMH 99.9 percent confidence interval. All cases in the linked dataset.")


def catalog():
    records = list(csv.DictReader((CATALOG / "summary.csv").open()))
    rows = [next(row for row in records if row["engine"] == engine) for engine in ("kokodb", "h2")]
    fig, axes = plt.subplots(3, 1, figsize=(8.5, 11.6), facecolor=BG)
    fig.subplots_adjust(left=.20, right=.92, top=.85, bottom=.20, hspace=1.1)
    fig.text(.06, .96, "Kotlin catalog CLI", fontsize=26, weight="bold", va="top")
    fig.text(.06, .91, "1,000 input rows → 800 verified output rows", fontsize=18, color=MUTED)
    for ax, metric, scale, title, unit in (
        (axes[0], "wall_ms", 1, "Launch through report and exit", "milliseconds · lower is better"),
        (axes[1], "max_rss_bytes", 2**20, "Peak process RSS, including JVM", "MiB · lower is better"),
    ):
        values = [float(row[f"median_{metric}"]) / scale for row in rows]
        errors = [[value - float(row[f"min_{metric}"]) / scale for value, row in zip(values, rows)],
                  [float(row[f"max_{metric}"]) / scale - value for value, row in zip(values, rows)]]
        bars(ax, values, title, unit, errors)
    bars(axes[2], [float(row["runtime_jars_bytes"]) / 2**20 for row in rows],
         "Example + runtime JARs", "MiB · JDK excluded · lower is better", fmt=".2f")
    fig.text(.06, .045, "Memory mode · 20 fresh JVMs per engine\nTime / RSS: median + full observed range\nApple M1 / JDK 17 · H2 2.5.252", fontsize=16, color=MUTED)
    save(fig, "catalog-comparison", "Complete in-memory Kotlin catalog CLI: launch-to-exit time and whole-process peak RSS (median and full range, 20 fresh JVMs per engine), plus shared example and selected-engine runtime JAR payload excluding the JDK. Source: 2026-10-23 catalog CLI study.")


if __name__ == "__main__":
    render_brand()
    startup()
    queries()
    catalog()
