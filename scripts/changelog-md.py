#!/usr/bin/env python3
"""Writes CHANGELOG.md from the in-app changelog (app/src/main/java/app/ultimate/home/AboutInfo.kt).

The in-app list is the single source of truth: it is what About shows and what the release workflow publishes as release
notes. Run `python3 scripts/changelog-md.py` after adding an entry there, and commit the result."""
import re, pathlib

root = pathlib.Path(__file__).resolve().parent.parent
src = (root / "app/src/main/java/app/ultimate/home/AboutInfo.kt").read_text()
start = src.index("val entries = listOf(")
body = src[start:]
heads = [m for m in re.finditer(r'Entry\("([0-9][^"]*)"', body)]
out = ["# Changelog", "",
       "Generated from `app/src/main/java/app/ultimate/home/AboutInfo.kt` by `scripts/changelog-md.py`; edit the entries there, not here.",
       "The newest version is first. The watch app has its own version (see `wear/build.gradle.kts`) and ships inside the phone app.", ""]
for i, m in enumerate(heads):
    block = body[m.end(): heads[i + 1].start() if i + 1 < len(heads) else len(body)]
    block = block.split("tries =")[0]
    notes = [n.replace('\\"', '"') for n in re.findall(r'"((?:[^"\\]|\\.)*)"', block)]
    out.append(f"## {m.group(1)}")
    out += [f"- {n}" for n in notes] or ["- (no notes)"]
    out.append("")
(root / "CHANGELOG.md").write_text("\n".join(out))
print(f"{len(heads)} versions written")
