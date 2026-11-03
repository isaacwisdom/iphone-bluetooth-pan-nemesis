#!/usr/bin/env python3
import argparse
import pathlib
import re
import zipfile


def metadata(path):
    values = {}
    section = ""
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line[0] in "#;":
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1]
        elif "=" in line:
            key, value = line.split("=", 1)
            values[(section, key.strip())] = value.strip().strip('"')
    return values


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=pathlib.Path)
    parser.add_argument("output", type=pathlib.Path)
    args = parser.parse_args()
    meta = metadata(args.source / "NEMESIS.data")
    package_id = meta.get(("nemesis", "id"), "")
    version = meta.get(("nemesis", "package-version"), "")
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9._-]{0,63}", package_id):
        raise SystemExit("invalid NEMESIS package id")
    if not re.fullmatch(r"\d+(\.\d+)*", version):
        raise SystemExit("invalid NEMESIS package version")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED) as archive:
        for item in sorted(args.source.rglob("*")):
            if item.is_file():
                info = zipfile.ZipInfo(item.relative_to(args.source).as_posix(), (2026, 1, 1, 0, 0, 0))
                info.external_attr = (0o755 if item.stat().st_mode & 0o111 else 0o644) << 16
                archive.writestr(info, item.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)


if __name__ == "__main__":
    main()
