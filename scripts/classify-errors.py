#!/usr/bin/env python3
"""
Compilation Error Classifier for Forge2Neo Build Verification.

Parses javac/Gradle compilation output and classifies errors by
which pipeline pass should have handled them.

Usage: python3 classify-errors.py <build-log-file>
"""

import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

# Error classification rules
CLASSIFICATIONS = {
    "MISSING_IMPORT": {
        "patterns": [
            r"cannot find symbol.*class (\w+)",
            r"cannot access (\w+)",
        ],
        "responsible_pass": "Pass 1 (Text Replace) or Pass 2 (AST)",
        "severity": "HIGH",
    },
    "WRONG_PACKAGE": {
        "patterns": [
            r"package ([\w.]+) does not exist",
        ],
        "responsible_pass": "Pass 1 (Text Replace)",
        "severity": "HIGH",
    },
    "MISSING_METHOD": {
        "patterns": [
            r"cannot find symbol.*method (\w+)",
        ],
        "responsible_pass": "Pass 2 (AST) or Pass 3 (Structural)",
        "severity": "MEDIUM",
    },
    "TYPE_MISMATCH": {
        "patterns": [
            r"incompatible types",
            r"cannot be converted to",
            r"required: .+ found: .+",
        ],
        "responsible_pass": "Pass 3 (Structural Refactor)",
        "severity": "MEDIUM",
    },
    "OVERRIDE_ERROR": {
        "patterns": [
            r"method does not override",
            r"is not abstract and does not override",
        ],
        "responsible_pass": "Pass 3 (Structural Refactor)",
        "severity": "MEDIUM",
    },
    "BUILD_SYSTEM": {
        "patterns": [
            r"Could not resolve.*net\.minecraftforge",
            r"Plugin .* was not found",
            r"Could not find net\.minecraftforge",
        ],
        "responsible_pass": "Pass 4 (Build System)",
        "severity": "CRITICAL",
    },
    "ANNOTATION_ERROR": {
        "patterns": [
            r"annotation type not applicable",
            r"cannot find symbol.*@\w+",
        ],
        "responsible_pass": "Pass 2 (AST Transform)",
        "severity": "LOW",
    },
}


def classify_line(line: str) -> tuple[str, str] | None:
    """Classify a single error line. Returns (category, detail) or None."""
    for category, info in CLASSIFICATIONS.items():
        for pattern in info["patterns"]:
            match = re.search(pattern, line)
            if match:
                detail = match.group(0)
                return category, detail
    return None


def parse_build_log(log_path: str) -> dict:
    """Parse a Gradle/javac build log and classify all errors."""
    results = {
        "total_errors": 0,
        "total_warnings": 0,
        "categories": Counter(),
        "details": defaultdict(list),
        "files_with_errors": set(),
        "unclassified": [],
    }

    with open(log_path, "r", encoding="utf-8", errors="replace") as f:
        lines = f.readlines()

    current_file = None
    for line in lines:
        line = line.strip()

        # Track current file
        file_match = re.match(r"(.+\.java):(\d+):", line)
        if file_match:
            current_file = file_match.group(1)

        # Count errors and warnings
        if ": error:" in line:
            results["total_errors"] += 1
            if current_file:
                results["files_with_errors"].add(current_file)

            classification = classify_line(line)
            if classification:
                cat, detail = classification
                results["categories"][cat] += 1
                results["details"][cat].append({
                    "file": current_file or "unknown",
                    "line": line,
                    "detail": detail,
                })
            else:
                results["unclassified"].append(line)

        elif ": warning:" in line:
            results["total_warnings"] += 1

    return results


def print_report(results: dict, log_path: str):
    """Print a human-readable error classification report."""
    print("=" * 60)
    print("  Forge2Neo Compilation Error Classification Report")
    print("=" * 60)
    print(f"  Log file: {log_path}")
    print(f"  Total errors: {results['total_errors']}")
    print(f"  Total warnings: {results['total_warnings']}")
    print(f"  Files with errors: {len(results['files_with_errors'])}")
    print("-" * 60)

    if results["total_errors"] == 0:
        print("\n  No compilation errors found!")
        return

    print("\n  Error Classification:")
    print("-" * 60)

    for category in sorted(results["categories"], key=results["categories"].get, reverse=True):
        count = results["categories"][category]
        info = CLASSIFICATIONS[category]
        pct = count / results["total_errors"] * 100
        print(f"\n  {category}: {count} ({pct:.1f}%)")
        print(f"    Responsible: {info['responsible_pass']}")
        print(f"    Severity: {info['severity']}")

        # Show first 3 examples
        for detail in results["details"][category][:3]:
            print(f"    Example: {detail['detail']}")
            print(f"      File: {detail['file']}")

    if results["unclassified"]:
        print(f"\n  UNCLASSIFIED: {len(results['unclassified'])}")
        for line in results["unclassified"][:5]:
            print(f"    {line[:100]}")

    # Summary recommendation
    print("\n" + "=" * 60)
    print("  Recommendations:")
    print("=" * 60)

    if results["categories"].get("WRONG_PACKAGE", 0) > 0:
        print("  - Add missing package rename rules to text-replacements.json")
    if results["categories"].get("MISSING_IMPORT", 0) > 0:
        print("  - Add missing class rename rules to class-renames.json")
    if results["categories"].get("MISSING_METHOD", 0) > 0:
        print("  - Add method mapping rules to method-renames.json")
    if results["categories"].get("TYPE_MISMATCH", 0) > 0:
        print("  - Review structural refactor patterns for type changes")
    if results["categories"].get("BUILD_SYSTEM", 0) > 0:
        print("  - Fix BuildSystemPass to handle this Gradle configuration")


def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <build-log-file>")
        sys.exit(1)

    log_path = sys.argv[1]
    if not Path(log_path).exists():
        print(f"Error: File not found: {log_path}")
        sys.exit(1)

    results = parse_build_log(log_path)
    print_report(results, log_path)


if __name__ == "__main__":
    main()
