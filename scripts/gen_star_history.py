#!/usr/bin/env python3
"""生成 XianYuSmart 的浅色与深色 Star History 趋势图。"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

import matplotlib

matplotlib.use("Agg")

import matplotlib.dates as mdates
import numpy as np
from matplotlib import pyplot as plt
from matplotlib.colors import LinearSegmentedColormap, to_rgba
from matplotlib.ticker import FuncFormatter

REPO = "Evvvvvvvan/XianYuSmart"
START_DATE = "2026-07-14"
CACHE = Path(__file__).with_name(".star-history-cache.json")

ACCENT = "#2f6f5e"

THEMES = {
    "light": dict(bg="#ffffff", text="#1f2328", subtext="#6a737d", grid="#dfe3e8"),
    "dark": dict(bg="#0d1117", text="#e6edf3", subtext="#8b949e", grid="#272d35"),
}

# 限制横轴标签数量，避免日期范围增长后文字重叠。
MAX_XTICKS = 12
DAY_STEPS = (1, 2, 3, 7, 14)
MONTH_STEPS = (1, 2, 3, 6)
YEAR_STEPS = (1, 2, 5, 10)


def get_token() -> str | None:
    for variable in ("GITHUB_TOKEN", "GH_TOKEN"):
        if token := os.environ.get(variable, "").strip():
            return token
    try:
        result = subprocess.run(
            ["gh", "auth", "token"],
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
        if result.returncode == 0 and result.stdout.strip():
            return result.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        pass
    return None


def get_json(url: str, headers: dict[str, str], retries: int = 4) -> list[dict]:
    request = urllib.request.Request(url, headers=headers)
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except Exception as exc:
            if attempt == retries - 1:
                raise
            wait_seconds = 2**attempt
            print(
                f"request failed ({exc}); retrying in {wait_seconds}s...",
                file=sys.stderr,
            )
            time.sleep(wait_seconds)
    return []


def fetch_starred_at(repo: str, refresh: bool) -> list[str]:
    """读取并按时间排序全部星标事件。"""
    if CACHE.exists() and not refresh:
        print(f"using cached stargazers from {CACHE}", file=sys.stderr)
        return json.loads(CACHE.read_text(encoding="utf-8"))

    headers = {
        "Accept": "application/vnd.github.star+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "xianyusmart-star-history",
    }
    if token := get_token():
        headers["Authorization"] = f"Bearer {token}"

    starred: list[str] = []
    page = 1
    while True:
        url = f"https://api.github.com/repos/{repo}/stargazers?per_page=100&page={page}"
        data = get_json(url, headers)
        if not data:
            break
        starred.extend(item["starred_at"] for item in data)
        print(f"\rfetched {len(starred)} stargazers...", end="", file=sys.stderr)
        page += 1
    print(file=sys.stderr)

    starred.sort()
    CACHE.write_text(json.dumps(starred), encoding="utf-8")
    return starred


def build_series(starred: list[str], start: datetime) -> tuple[np.ndarray, np.ndarray]:
    """按星标事件生成从指定日期开始的累计序列。"""
    times = [
        datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
        for value in starred
    ]
    base = sum(1 for value in times if value < start)
    visible_times = [value for value in times if value >= start]
    x = [mdates.date2num(start)] + [mdates.date2num(value) for value in visible_times]
    y = [base] + [base + index for index in range(1, len(visible_times) + 1)]
    return np.array(x), np.array(y)


def pick_xticks(x_start: float, x_end: float) -> tuple[list[float], str]:
    """按时间跨度选择横轴刻度，并保留最新日期。"""
    start = mdates.num2date(x_start)
    end = mdates.num2date(x_end)
    span_days = x_end - x_start

    for step in DAY_STEPS:
        if span_days / step <= MAX_XTICKS:
            anchor = end.replace(hour=0, minute=0, second=0, microsecond=0)
            ticks = []
            while (value := mdates.date2num(anchor)) >= x_start:
                ticks.append(value)
                anchor -= timedelta(days=step)
            date_format = "day-year" if start.year != end.year else "day"
            return sorted(ticks), date_format

    span_months = (end.year - start.year) * 12 + end.month - start.month
    for step in MONTH_STEPS:
        if span_months / step <= MAX_XTICKS:
            year = end.year
            month = end.month
            ticks = []
            while (
                value := mdates.date2num(
                    end.replace(
                        year=year,
                        month=month,
                        day=1,
                        hour=0,
                        minute=0,
                        second=0,
                        microsecond=0,
                    )
                )
            ) >= x_start:
                ticks.append(value)
                month -= step
                while month < 1:
                    month += 12
                    year -= 1
            date_format = "month-year" if start.year != end.year else "month"
            return sorted(ticks), date_format

    span_years = end.year - start.year
    step = next(
        (value for value in YEAR_STEPS if span_years / value <= MAX_XTICKS),
        max(1, -(-span_years // MAX_XTICKS)),
    )
    year = end.year
    ticks = []
    while (
        value := mdates.date2num(
            end.replace(
                year=year,
                month=1,
                day=1,
                hour=0,
                minute=0,
                second=0,
                microsecond=0,
            )
        )
    ) >= x_start:
        ticks.append(value)
        year -= step
    return sorted(ticks), "year"


def format_xtick(value: float, date_format: str) -> str:
    """使用跨平台格式输出横轴日期。"""
    date = mdates.num2date(value)
    if date_format == "day":
        return f"{date:%b} {date.day}"
    if date_format == "day-year":
        return f"{date:%b} {date.day}, {date.year}"
    if date_format == "month":
        return f"{date:%b}"
    if date_format == "month-year":
        return f"{date:%b} {date.year}"
    return str(date.year)


def thin_xticklabels(figure, axes, minimum_gap: float = 14.0) -> None:
    """按实际渲染宽度继续精简标签，并保留最新日期。"""
    ticks = list(axes.get_xticks())
    for interval in range(1, max(len(ticks), 1) + 1):
        visible_ticks = ticks[::-1][::interval][::-1]
        axes.set_xticks(visible_ticks)
        figure.canvas.draw()
        renderer = figure.canvas.get_renderer()
        boxes = [
            label.get_window_extent(renderer=renderer)
            for label in axes.get_xticklabels()
            if label.get_text()
        ]
        if all(
            next_box.x0 - current_box.x1 >= minimum_gap
            for current_box, next_box in zip(boxes, boxes[1:])
        ):
            return


def draw(
    x: np.ndarray,
    y: np.ndarray,
    repo: str,
    theme: dict[str, str],
    output: Path,
) -> None:
    """绘制单个主题的趋势图。"""
    background = theme["bg"]
    text = theme["text"]
    subtext = theme["subtext"]
    grid = theme["grid"]

    figure, axes = plt.subplots(figsize=(12, 6.2), dpi=200)
    figure.patch.set_facecolor(background)
    axes.set_facecolor(background)
    figure.subplots_adjust(left=0.075, right=0.97, top=0.80, bottom=0.10)

    y_limit = max(float(y.max()) * 1.10, 1.0)
    x_span = max(float(x[-1] - x[0]), 1.0)
    axes.set_ylim(0, y_limit)
    axes.set_xlim(x[0], x[-1] + x_span * 0.03)

    red, green, blue, _ = to_rgba(ACCENT)
    fade = LinearSegmentedColormap.from_list(
        "fade",
        [(red, green, blue, 0.0), (red, green, blue, 0.35)],
    )
    gradient = np.linspace(0, 1, 256).reshape(-1, 1)
    image = axes.imshow(
        gradient,
        aspect="auto",
        cmap=fade,
        origin="lower",
        extent=[axes.get_xlim()[0], axes.get_xlim()[1], 0, axes.get_ylim()[1]],
        zorder=1,
    )
    polygon_x = np.concatenate([[x[0]], x, [x[-1]]])
    polygon_y = np.concatenate([[0.0], y, [0.0]])
    (clip,) = axes.fill(polygon_x, polygon_y, alpha=0, zorder=1)
    image.set_clip_path(clip)

    axes.plot(
        x,
        y,
        color=ACCENT,
        linewidth=7,
        alpha=0.10,
        solid_capstyle="round",
        zorder=2,
    )
    axes.plot(
        x,
        y,
        color=ACCENT,
        linewidth=2.6,
        solid_capstyle="round",
        zorder=3,
    )
    axes.scatter(
        [x[-1]],
        [y[-1]],
        s=70,
        color=ACCENT,
        edgecolor=background,
        linewidth=2.2,
        zorder=4,
    )
    axes.annotate(
        f"{int(y[-1]):,} stars",
        xy=(x[-1], y[-1]),
        xytext=(-6, 14),
        textcoords="offset points",
        ha="right",
        fontsize=16,
        fontweight="bold",
        color=text,
    )

    figure.text(0.075, 0.93, "Star History", fontsize=22, fontweight="bold", color=text)
    figure.text(0.075, 0.862, repo, fontsize=12.5, color=subtext)

    axes.yaxis.grid(True, color=grid, linewidth=0.9, linestyle=(0, (5, 4)))
    axes.set_axisbelow(True)
    for side in ("top", "right", "left"):
        axes.spines[side].set_visible(False)
    axes.spines["bottom"].set_color(grid)
    axes.tick_params(axis="both", length=0, labelsize=11.5, colors=subtext, pad=8)
    ticks, date_format = pick_xticks(*axes.get_xlim())
    axes.set_xticks(ticks)
    axes.xaxis.set_major_formatter(
        FuncFormatter(lambda value, _position: format_xtick(value, date_format))
    )
    axes.yaxis.set_major_formatter(FuncFormatter(lambda value, _position: f"{int(value):,}"))

    thin_xticklabels(figure, axes)

    figure.savefig(output, facecolor=background, bbox_inches="tight", pad_inches=0.3)
    plt.close(figure)
    print(f"wrote {output}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=REPO)
    parser.add_argument("--start-date", default=START_DATE)
    parser.add_argument("--out-dir", default="docs/assets")
    parser.add_argument("--refresh", action="store_true", help="忽略缓存并重新读取")
    arguments = parser.parse_args()

    start = datetime.strptime(arguments.start_date, "%Y-%m-%d").replace(
        tzinfo=timezone.utc
    )
    starred = fetch_starred_at(arguments.repo, refresh=arguments.refresh)
    x, y = build_series(starred, start)

    output_directory = Path(arguments.out_dir)
    output_directory.mkdir(parents=True, exist_ok=True)
    for name, theme in THEMES.items():
        draw(
            x,
            y,
            arguments.repo,
            theme,
            output_directory / f"star-history-{name}.png",
        )


if __name__ == "__main__":
    main()
