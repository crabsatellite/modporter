#!/usr/bin/env python3
"""Split, assemble, and verify large Kotlin source files from a manifest.

The fragment layout is intentionally byte-preserving. A generated Kotlin source
file must assemble exactly from its ordered fragments, including line endings.
This lets large migration rules and large regression suites be maintained in
reviewable chunks while keeping the compiled source path stable.
"""

from __future__ import annotations

import argparse
import dataclasses
import re
import shutil
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = ROOT / "config/kotlin-fragments.tsv"

MEMBER_START = re.compile(
    rb"^    (?:(?:private|override|internal|public|protected)\s+)?"
    rb"(?:(?:data\s+class|enum\s+class|sealed\s+class|class|interface|object|fun)|"
    rb"(?:suspend\s+fun))\b"
)
MEMBER_NAME = re.compile(rb"\b(?:fun|class|object|interface)\s+`?([A-Za-z_$][A-Za-z0-9_$ -]*)`?")


@dataclasses.dataclass(frozen=True)
class FragmentTarget:
    id: str
    source: Path
    fragment_dir: Path
    order_file: Path
    max_lines: int


def repo_path(raw: str, label: str) -> Path:
    path = Path(raw)
    if path.is_absolute() or ".." in path.parts:
        raise SystemExit(f"{label} must be a repo-relative path without '..': {raw}")
    return (ROOT / path).resolve()


def relative(path: Path) -> str:
    try:
        return path.relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def read_manifest(path: Path) -> list[FragmentTarget]:
    if not path.exists():
        raise SystemExit(f"Missing fragment manifest: {relative(path)}")

    targets: list[FragmentTarget] = []
    seen: set[str] = set()
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        columns = raw_line.split("\t")
        if columns[0] == "id":
            continue
        if len(columns) != 5:
            raise SystemExit(f"{relative(path)}:{line_number}: expected 5 tab-separated columns")
        target_id, source_raw, dir_raw, order_raw, max_lines_raw = columns
        if target_id in seen:
            raise SystemExit(f"{relative(path)}:{line_number}: duplicate target id {target_id}")
        seen.add(target_id)
        fragment_dir = repo_path(dir_raw, "fragment_dir")
        order_path = Path(order_raw)
        if order_path.is_absolute() or ".." in order_path.parts:
            raise SystemExit(f"order_file must be relative to fragment_dir without '..': {order_raw}")
        try:
            max_lines = int(max_lines_raw)
        except ValueError as exc:
            raise SystemExit(f"{relative(path)}:{line_number}: invalid max_lines {max_lines_raw}") from exc
        if max_lines < 200:
            raise SystemExit(f"{relative(path)}:{line_number}: max_lines is too small: {max_lines}")
        targets.append(
            FragmentTarget(
                id=target_id,
                source=repo_path(source_raw, "source"),
                fragment_dir=fragment_dir,
                order_file=fragment_dir / order_path,
                max_lines=max_lines,
            )
        )
    if not targets:
        raise SystemExit(f"Fragment manifest is empty: {relative(path)}")
    return targets


def select_targets(targets: list[FragmentTarget], ids: list[str]) -> list[FragmentTarget]:
    if not ids:
        return targets
    requested = [part for value in ids for part in value.split(",") if part]
    by_id = {target.id: target for target in targets}
    missing = [target_id for target_id in requested if target_id not in by_id]
    if missing:
        raise SystemExit("Unknown fragment target id(s): " + ", ".join(missing))
    return [by_id[target_id] for target_id in requested]


def read_lines(path: Path) -> list[bytes]:
    return path.read_bytes().splitlines(keepends=True)


def boundary_start(lines: list[bytes], index: int) -> int:
    cursor = index
    while cursor > 0:
        previous = lines[cursor - 1]
        stripped = previous.strip()
        if not stripped or stripped.startswith(b"@") or previous.startswith(b"    @"):
            cursor -= 1
            continue
        break
    return cursor


def slug(text: bytes) -> str:
    match = MEMBER_NAME.search(text)
    stem = match.group(1).decode("utf-8", errors="ignore") if match else "chunk"
    words = re.sub(r"([a-z0-9])([A-Z])", r"\1-\2", stem).lower()
    words = re.sub(r"[^a-z0-9]+", "-", words).strip("-")
    return words[:72] or "chunk"


def split_points(lines: list[bytes], max_lines: int) -> list[int]:
    boundaries = [0]
    for index, line in enumerate(lines):
        if index > 0 and MEMBER_START.match(line):
            boundary = boundary_start(lines, index)
            if boundary not in boundaries:
                boundaries.append(boundary)
    boundaries.append(len(lines))
    boundaries = sorted(set(boundaries))

    points = [0]
    cursor = 0
    while cursor + max_lines < len(lines):
        candidates = [point for point in boundaries if cursor < point <= cursor + max_lines]
        if candidates:
            next_point = candidates[-1]
        else:
            # Some regression tests intentionally contain very large embedded
            # source fixtures. If a single member exceeds the configured budget,
            # split the text chunk anyway; fragments are assembled byte-for-byte
            # and are not compiled independently.
            next_point = min(cursor + max_lines, len(lines))
        if next_point <= cursor:
            next_point = min(cursor + max_lines, len(lines))
        points.append(next_point)
        cursor = next_point
    if points[-1] != len(lines):
        points.append(len(lines))
    return points


def fragment_names(lines: list[bytes], points: list[int]) -> list[str]:
    names: list[str] = []
    seen: set[str] = set()
    for ordinal, start in enumerate(points[:-1], start=1):
        end = points[ordinal]
        first_member = b""
        for line in lines[start:end]:
            if MEMBER_START.match(line):
                first_member = line
                break
        base = "header" if ordinal == 1 else slug(first_member)
        name = f"{ordinal:03d}-{base}.ktfrag"
        suffix = 2
        while name in seen:
            name = f"{ordinal:03d}-{base}-{suffix}.ktfrag"
            suffix += 1
        seen.add(name)
        names.append(name)
    return names


def split(target: FragmentTarget) -> None:
    if not target.source.exists():
        raise SystemExit(f"Missing source for {target.id}: {relative(target.source)}")
    lines = read_lines(target.source)
    points = split_points(lines, target.max_lines)
    names = fragment_names(lines, points)

    if target.fragment_dir.exists():
        shutil.rmtree(target.fragment_dir)
    target.fragment_dir.mkdir(parents=True)

    for name, start, end in zip(names, points[:-1], points[1:]):
        (target.fragment_dir / name).write_bytes(b"".join(lines[start:end]))
    target.order_file.write_text("\n".join(names) + "\n", encoding="utf-8", newline="\n")
    print(f"{target.id}: split {relative(target.source)} into {len(names)} fragments")


def ordered_fragment_paths(target: FragmentTarget) -> list[Path]:
    if not target.order_file.exists():
        raise SystemExit(f"{target.id}: missing order file {relative(target.order_file)}")
    names = [
        line.strip()
        for line in target.order_file.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.strip().startswith("#")
    ]
    if not names:
        raise SystemExit(f"{target.id}: empty order file {relative(target.order_file)}")
    paths = [target.fragment_dir / name for name in names]
    missing = [path for path in paths if not path.exists()]
    if missing:
        raise SystemExit(
            f"{target.id}: missing fragment(s): " +
            ", ".join(relative(path) for path in missing)
        )
    return paths


def assembled_bytes(target: FragmentTarget) -> bytes:
    return b"".join(path.read_bytes() for path in ordered_fragment_paths(target))


def assemble(target: FragmentTarget) -> None:
    target.source.parent.mkdir(parents=True, exist_ok=True)
    target.source.write_bytes(assembled_bytes(target))
    print(f"{target.id}: assembled {relative(target.source)}")


def check(target: FragmentTarget) -> bool:
    expected = assembled_bytes(target)
    actual = target.source.read_bytes()
    ok = expected == actual
    if ok:
        print(f"{target.id}: fragments are in sync")
    else:
        print(f"{target.id}: source differs from ordered fragments", file=sys.stderr)
    return ok


def list_targets(targets: list[FragmentTarget]) -> None:
    for target in targets:
        print(
            "\t".join(
                [
                    target.id,
                    relative(target.source),
                    relative(target.fragment_dir),
                    target.order_file.name,
                    str(target.max_lines),
                ]
            )
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("list", "split", "assemble", "check"))
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST))
    parser.add_argument("--id", action="append", default=[], help="Target id or comma-separated ids")
    args = parser.parse_args()

    manifest_arg = Path(args.manifest)
    manifest = manifest_arg.resolve() if manifest_arg.is_absolute() else repo_path(args.manifest, "manifest")
    targets = select_targets(read_manifest(manifest), args.id)

    if args.command == "list":
        list_targets(targets)
        return 0

    if args.command == "split":
        for target in targets:
            split(target)
        return 0

    if args.command == "assemble":
        for target in targets:
            assemble(target)
        return 0

    failed = [target.id for target in targets if not check(target)]
    if failed:
        print("Run: python scripts/kotlin_fragments.py assemble", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
