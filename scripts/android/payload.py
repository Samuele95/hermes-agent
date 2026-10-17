"""Android app runtime payload: python.org's Android CPython, the Termux-recipe wheelhouse, the core.

An APK ships native libraries only as ``jniLibs/<abi>/lib*.so``. The wheels' extensions need Termux
libraries with versioned sonames (``libssl.so.3``, ``libz.so.1``, …) that the APK would drop, and
carry a RUNPATH into the Termux prefix. :func:`vendor` copies the transitive closure under
packageable ``lib<stem>_hermes.so`` names and rewrites SONAME, NEEDED and RUNPATH with patchelf, the
way python.org ships its own dependencies as ``lib*_python.so``.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import struct
import subprocess
import tarfile
import tempfile
import zipfile
from collections.abc import Iterable
from pathlib import Path

_SONAME = re.compile(r"^lib(?P<stem>.+?)\.so(?:\.[0-9.]+)?$")


def vendored_name(soname: str) -> str:
    match = _SONAME.match(soname)
    if match is None:
        raise ValueError(f"not a shared-library soname: {soname}")
    return f"lib{match['stem']}_hermes.so"


def _patchelf(*args: str) -> str:
    return subprocess.run(["patchelf", *args], check=True, capture_output=True, text=True, encoding="utf-8",
                          timeout=120).stdout


def _needed(elf: Path) -> list[str]:
    return _patchelf("--print-needed", str(elf)).split()


def closure(elves: Iterable[Path], libdir: Path) -> dict[str, Path]:
    """Sonames reachable from *elves* that *libdir* provides, transitively, each with its file."""
    found: dict[str, Path] = {}
    pending = [name for elf in elves for name in _needed(elf)]
    while pending:
        name = pending.pop()
        if name in found or not (libdir / name).exists():
            continue  # system and interpreter libraries resolve on the device
        found[name] = (libdir / name).resolve()
        pending.extend(_needed(found[name]))
    return found


def _relink(elf: Path, mapping: dict[str, str]) -> None:
    for old in set(_needed(elf)) & mapping.keys():
        _patchelf("--replace-needed", old, mapping[old], str(elf))
    _patchelf("--remove-rpath", str(elf))


def vendor(elves: Iterable[Path], libdir: Path, jnilibs: Path) -> dict[str, str]:
    """Copy the closure of *elves* from *libdir* into *jnilibs* under packageable names and relink
    the copies and *elves* (patched in place) to those names. Returns ``{soname: packaged_name}``.
    Two different libraries whose packaged names would be the same (``libz.so.1``, ``libz.so.2``) are
    refused: one would silently replace the other."""
    elves = list(elves)
    libraries = closure(elves, libdir)
    mapping = {name: vendored_name(name) for name in libraries}
    claimed: dict[str, str] = {}
    for name in sorted(libraries):
        other = claimed.setdefault(mapping[name], name)
        if libraries[other] != libraries[name]:
            raise ValueError(f"{other} and {name} would both be packaged as {mapping[name]}")
    jnilibs.mkdir(parents=True, exist_ok=True)
    for name, source in libraries.items():
        target = jnilibs / mapping[name]
        shutil.copyfile(source, target)
        _patchelf("--set-soname", mapping[name], str(target))
        _relink(target, mapping)
    for elf in elves:
        _relink(elf, mapping)
    return mapping


# Stdlib parts an embedded runtime never imports: its own tests and the Tk / IDLE / pip bootstraps.
_STDLIB_SKIP = ("test", "idlelib", "tkinter", "turtledemo", "ensurepip", "__pycache__")
_PYTHON_LIBS = ("libpython3.14.so", "libssl_python.so", "libcrypto_python.so")


def _load_alignments(elf: Path) -> list[int]:
    """p_align of each PT_LOAD segment of a little-endian ELF64 file."""
    data = elf.read_bytes()
    phoff, = struct.unpack_from("<Q", data, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    headers = (struct.unpack_from("<I", data, phoff + i * phentsize)[0] for i in range(phnum))
    return [struct.unpack_from("<Q", data, phoff + i * phentsize + 48)[0]
            for i, kind in enumerate(headers) if kind == 1]


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def _copy_stdlib(prefix: Path, runtime: Path) -> None:
    source = prefix / "lib" / "python3.14"
    shutil.copytree(source, runtime / "lib" / "python3.14", ignore=lambda directory, names: [
        n for n in names if n == "__pycache__" or (n in _STDLIB_SKIP and Path(directory) == source)])


def _install_wheels(wheelhouse: Path, site: Path) -> None:
    site.mkdir(parents=True)
    for wheel in sorted(wheelhouse.glob("*.whl")):
        with zipfile.ZipFile(wheel) as archive:
            archive.extractall(site)
        for data in sorted(site.glob("*.data")):
            _place_wheel_data(wheel.name, data, site)


def _place_wheel_data(wheel: str, data: Path, site: Path) -> None:
    """A wheel's ``<name>.data/<scheme>/`` trees, placed as an installer would: ``purelib`` and ``platlib``
    into site-packages; ``scripts`` and ``headers`` dropped (the app runs no programs and builds nothing);
    any other scheme refused, since the runtime has no install prefix to put it in."""
    for tree in sorted(data.iterdir()):
        if tree.name in ("purelib", "platlib"):
            shutil.copytree(tree, site, dirs_exist_ok=True)
        elif tree.name not in ("scripts", "headers"):
            raise ValueError(f"{wheel}: the app's runtime has no place for its {tree.name} files")
    shutil.rmtree(data)


def _archive_subtree(repo: Path, commit: str, subtree: str, destination: Path) -> None:
    """``subtree`` of ``commit`` as the contents of ``destination`` (the snapshot leaves out ``apps/``)."""
    with tempfile.TemporaryDirectory(prefix="hermes-android-") as temp:
        archive = Path(temp) / "subtree.tar"
        subprocess.run(["git", "archive", "--format=tar", "--output", str(archive), commit, "--", subtree],
                       cwd=repo, check=True, timeout=120)
        with tarfile.open(archive) as source:
            source.extractall(temp, filter="data")
        shutil.move(Path(temp) / subtree, destination)


def _zip_tree(root: Path, archive: Path) -> None:
    # Fixed timestamps and order: the same inputs give the same bytes.
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as out:
        for path in sorted(root.rglob("*")):
            if path.is_file():
                entry = zipfile.ZipInfo(path.relative_to(root).as_posix(), date_time=(1980, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                out.writestr(entry, path.read_bytes())


def assemble(*, python_tarball: Path, python_sha256: str, wheelhouse: Path, runtime_libs: Path,
             repo: Path, ref: str, out: Path) -> dict:
    """Build ``out/jniLibs/arm64-v8a`` and ``out/assets/{runtime.zip,payload.json}`` — the layout of an
    Android source set, so the app adds ``out`` to its variant sources as is — and ``out/include``, the
    C headers the bridge compiles against."""
    from scripts.bundles.payload import INERT_SNAPSHOT_DIRS, snapshot

    actual = _sha256(python_tarball)
    if actual != python_sha256:
        raise ValueError(f"python tarball sha256 {actual} does not match the pin {python_sha256}")
    if out.exists():
        shutil.rmtree(out)
    work, assets, jnilibs = out / ".work", out / "assets", out / "jniLibs" / "arm64-v8a"
    runtime = work / "runtime"
    work.mkdir(parents=True)
    assets.mkdir()
    with tarfile.open(python_tarball) as archive:
        archive.extractall(work, filter="data")
    prefix = work / "prefix"
    _copy_stdlib(prefix, runtime)
    _install_wheels(wheelhouse, runtime / "site-packages")
    commit = subprocess.run(["git", "rev-parse", "--verify", f"{ref}^{{commit}}"], cwd=repo, check=True,
                            capture_output=True, text=True, encoding="utf-8", timeout=60).stdout.strip()
    snapshot(repo, commit, runtime / "app", exclude=INERT_SNAPSHOT_DIRS)
    _archive_subtree(repo, commit, "apps/android/python", runtime / "android")  # hermes_android

    shutil.copytree(prefix / "include" / "python3.14", out / "include" / "python3.14")  # for the JNI bridge build
    jnilibs.mkdir(parents=True)
    for name in _PYTHON_LIBS:
        shutil.copyfile(prefix / "lib" / name, jnilibs / name)
    # python.org's SQLite has no FTS5; the Termux recipe's does. Same soname, so _sqlite3 binds to it.
    sqlite = jnilibs / "libsqlite3_python.so"
    shutil.copyfile(runtime_libs / "libsqlite3.so", sqlite)
    _patchelf("--set-soname", sqlite.name, str(sqlite))
    extensions = [p for p in (runtime / "site-packages").rglob("*.so*") if p.is_file()]
    vendor([*extensions, sqlite], runtime_libs, jnilibs)
    _zip_tree(runtime, assets / "runtime.zip")

    # The id names the inputs (and this assembler), so rebuilding from them reuses the installed runtime.
    manifest = {"python_sha256": python_sha256, "core_commit": commit, "assembler_sha256": _sha256(Path(__file__)),
                "wheelhouse": {p.name: _sha256(p) for p in sorted(wheelhouse.glob("*.whl"))}}
    manifest["build_id"] = hashlib.sha256(json.dumps(manifest, sort_keys=True).encode()).hexdigest()[:16]
    # 16 KB-page devices refuse these; they load on 4 KB-page devices only.
    manifest["needs_4k_pages"] = sorted(p.name for p in jnilibs.iterdir() if min(_load_alignments(p)) < 16384)
    (assets / "payload.json").write_text(json.dumps(manifest, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    shutil.rmtree(work)
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--python-tarball", type=Path, required=True)
    parser.add_argument("--python-sha256", required=True)
    parser.add_argument("--wheelhouse", type=Path, required=True)
    parser.add_argument("--runtime-libs", type=Path, required=True)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--ref", default="HEAD")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    manifest = assemble(**vars(args))
    print(f"payload {manifest['build_id']} -> {args.out}")


if __name__ == "__main__":
    main()
