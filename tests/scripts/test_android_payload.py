"""The Android payload vendors the Termux libraries the wheels need under packageable names.

An APK only ships ``lib*.so`` files from jniLibs, so ``libssl.so.3`` or ``libz.so.1`` would be
dropped silently and every extension needing them would fail to load. The payload renames each
vendored library to ``lib<stem>_hermes.so``, rewrites SONAME and NEEDED to match, and strips the
Termux RUNPATH. Real ELF files are built with the host compiler and inspected with patchelf.
"""

from __future__ import annotations

import re
import shutil
import subprocess
from pathlib import Path

import pytest

from scripts.android import payload

pytestmark = pytest.mark.skipif(
    shutil.which("cc") is None or shutil.which("patchelf") is None, reason="needs a C compiler and patchelf")

TERMUX_RUNPATH = "/data/data/com.termux/files/usr/lib"


@pytest.mark.parametrize("soname, packaged", [
    ("libssl.so.3", "libssl_hermes.so"),
    ("libbz2.so.1.0", "libbz2_hermes.so"),
    ("libc++_shared.so", "libc++_shared_hermes.so"),
    ("libandroid-support.so", "libandroid-support_hermes.so"),
])
def test_every_vendored_library_gets_a_packageable_name(soname, packaged):
    assert payload.vendored_name(soname) == packaged


def _shared(tmp: Path, name: str, soname: str, *, needs: tuple[Path, ...] = (), page: int = 16384) -> Path:
    source = tmp / f"{name}.c"
    symbol = re.sub(r"\W", "_", name)
    source.write_text(f"int {symbol}_symbol(void) {{ return 1; }}\n")
    out = tmp / name
    # --no-as-needed: the stub uses no symbol of its deps, and toolchains defaulting to --as-needed would drop them.
    subprocess.run(["cc", "-shared", "-fPIC", "-o", str(out), str(source), f"-Wl,-soname,{soname}", f"-Wl,-z,max-page-size={page}", "-Wl,--no-as-needed",
                    *(str(dep) for dep in needs), f"-Wl,-rpath,{TERMUX_RUNPATH}"], check=True)
    return out


def _elf(path: Path, flag: str) -> list[str]:
    return subprocess.check_output(["patchelf", flag, str(path)], text=True).split()


@pytest.fixture
def termux(tmp_path):
    """libext (an extension) -> libheif.so.1 -> libz.so.1, laid out like Termux runtime-libs."""
    build, libdir = tmp_path / "build", tmp_path / "runtime-libs"
    build.mkdir()
    libdir.mkdir()
    z = _shared(build, "libz.so.1", "libz.so.1")
    heif = _shared(build, "libheif.so.1", "libheif.so.1", needs=(z,))
    ext = _shared(build, "_ext.so", "_ext.so", needs=(heif,))
    for lib in (z, heif):
        shutil.copy(lib, libdir / lib.name)
    site = tmp_path / "site"
    site.mkdir()
    shutil.copy(ext, site / "_ext.so")
    return site / "_ext.so", libdir


def test_the_transitive_closure_is_vendored_under_packageable_names(termux, tmp_path):
    ext, libdir = termux
    jnilibs = tmp_path / "jniLibs"

    mapping = payload.vendor([ext], libdir, jnilibs)

    assert mapping == {"libheif.so.1": "libheif_hermes.so", "libz.so.1": "libz_hermes.so"}
    assert sorted(p.name for p in jnilibs.iterdir()) == ["libheif_hermes.so", "libz_hermes.so"]
    assert _elf(jnilibs / "libz_hermes.so", "--print-soname") == ["libz_hermes.so"]


def test_needed_entries_and_runpaths_are_rewritten(termux, tmp_path):
    ext, libdir = termux
    jnilibs = tmp_path / "jniLibs"

    payload.vendor([ext], libdir, jnilibs)

    assert "libheif_hermes.so" in _elf(ext, "--print-needed")
    assert "libz_hermes.so" in _elf(jnilibs / "libheif_hermes.so", "--print-needed")
    assert _elf(ext, "--print-rpath") == []
    assert _elf(jnilibs / "libheif_hermes.so", "--print-rpath") == []


def test_libraries_outside_the_runtime_libs_are_left_to_the_system(termux, tmp_path):
    ext, libdir = termux
    system_needed = {n for n in _elf(ext, "--print-needed") if not n.startswith("libheif")}

    mapping = payload.vendor([ext], libdir, tmp_path / "jniLibs")

    assert system_needed and not system_needed & mapping.keys()
    assert system_needed <= set(_elf(ext, "--print-needed"))


def _tarball(tmp: Path, libdir_build: Path) -> Path:
    """A python.org-shaped Android release: prefix/lib/{libpython3.14.so, python3.14/...}."""
    root = tmp / "pyorg"
    (root / "prefix/lib/python3.14/test").mkdir(parents=True)
    (root / "prefix/lib/python3.14/os.py").write_text("# stdlib\n")
    (root / "prefix/lib/python3.14/test/test_os.py").write_text("# stdlib tests are not shipped\n")
    (root / "prefix/include/python3.14").mkdir(parents=True)
    (root / "prefix/include/python3.14/Python.h").write_text("/* C API */\n")
    for name in ("libpython3.14.so", "libssl_python.so", "libcrypto_python.so", "libsqlite3_python.so"):
        shutil.copy(_shared(libdir_build, name, name), root / "prefix/lib" / name)
    tarball = tmp / "python-android.tar.gz"
    subprocess.run(["tar", "-czf", str(tarball), "-C", str(root), "prefix"], check=True)
    return tarball


@pytest.fixture
def inputs(tmp_path):
    build = tmp_path / "build"
    build.mkdir()
    tarball = _tarball(tmp_path, build)
    libdir = tmp_path / "runtime-libs"
    libdir.mkdir()
    z = _shared(build, "libz.so.1", "libz.so.1")
    shutil.copy(z, libdir / "libz.so.1")
    shutil.copy(_shared(build, "libsqlite3.so", "libsqlite3.so", needs=(z,)), libdir / "libsqlite3.so")
    ext = _shared(build, "_native.cpython-314-aarch64-linux-android.so", "_native.so", needs=(z,))
    wheelhouse = tmp_path / "wheelhouse"
    wheelhouse.mkdir()
    import zipfile
    with zipfile.ZipFile(wheelhouse / "native-1.0-cp314-cp314-android_24_arm64_v8a.whl", "w") as whl:
        whl.write(ext, "native/_native.cpython-314-aarch64-linux-android.so")
        whl.writestr("native/__init__.py", "")
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "run_agent.py").write_text("# core\n")
    (repo / "tests").mkdir()
    (repo / "tests/test_x.py").write_text("# not shipped\n")
    (repo / "apps/android/python/hermes_android").mkdir(parents=True)
    (repo / "apps/android/python/hermes_android/__init__.py").write_text("# the app's bootstrap\n")
    (repo / "apps/android/app").mkdir()
    (repo / "apps/android/app/build.gradle.kts").write_text("// not shipped\n")
    subprocess.run(["git", "init", "-q", str(repo)], check=True)
    subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
    subprocess.run(["git", "-C", str(repo), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "c"], check=True)
    sha = __import__("hashlib").sha256(tarball.read_bytes()).hexdigest()
    return dict(python_tarball=tarball, python_sha256=sha, wheelhouse=wheelhouse, runtime_libs=libdir, repo=repo, ref="HEAD")


def test_assemble_lays_out_jnilibs_and_the_runtime_zip(inputs, tmp_path):
    import zipfile
    out = tmp_path / "out"

    manifest = payload.assemble(out=out, **inputs)

    jni = out / "jniLibs/arm64-v8a"
    assert sorted(p.name for p in jni.iterdir()) == [
        "libcrypto_python.so", "libpython3.14.so", "libsqlite3_python.so", "libssl_python.so", "libz_hermes.so"]
    assert _elf(jni / "libsqlite3_python.so", "--print-soname") == ["libsqlite3_python.so"]
    assert "libz_hermes.so" in _elf(jni / "libsqlite3_python.so", "--print-needed")  # the FTS5 build replaced python.org's
    names = set(zipfile.ZipFile(out / "assets/runtime.zip").namelist())
    assert {"lib/python3.14/os.py", "site-packages/native/__init__.py", "app/run_agent.py",
            "android/hermes_android/__init__.py"} <= names
    assert not any(n.startswith(("lib/python3.14/test/", "app/tests/", "app/apps/", "android/app/")) for n in names)
    assert manifest["build_id"] and manifest == __import__("json").loads((out / "assets/payload.json").read_text())
    # The bridge compiles against the headers; they are build input, not app content.
    assert (out / "include/python3.14/Python.h").is_file() and "include/" not in str(names)


def test_the_build_id_changes_with_the_inputs(inputs, tmp_path):
    first = payload.assemble(out=tmp_path / "a", **inputs)
    (inputs["repo"] / "run_agent.py").write_text("# core v2\n")
    subprocess.run(["git", "-C", str(inputs["repo"]), "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qam", "v2"], check=True)

    second = payload.assemble(out=tmp_path / "b", **inputs)

    assert first["build_id"] != second["build_id"]


def test_a_tarball_with_the_wrong_digest_is_refused(inputs, tmp_path):
    inputs["python_sha256"] = "0" * 64

    with pytest.raises(ValueError, match="sha256"):
        payload.assemble(out=tmp_path / "out", **inputs)


def test_the_same_inputs_give_the_same_build_and_bytes(inputs, tmp_path):
    first = payload.assemble(out=tmp_path / "a", **inputs)

    second = payload.assemble(out=tmp_path / "b", **inputs)

    assert first["build_id"] == second["build_id"]
    assert (tmp_path / "a/assets/runtime.zip").read_bytes() == (tmp_path / "b/assets/runtime.zip").read_bytes()
    # Two runs inside one second would match even with wall-clock entries; none may carry one.
    import zipfile
    assert {e.date_time for e in zipfile.ZipFile(tmp_path / "a/assets/runtime.zip").infolist()} == {(1980, 1, 1, 0, 0, 0)}


def test_libraries_that_need_4k_pages_are_named_in_the_manifest(inputs, tmp_path):
    # 16 KB-page devices refuse a LOAD segment aligned below 16 KB; the payload says which libraries those are.
    libdir, build = inputs["runtime_libs"], tmp_path / "build"
    shutil.copy(_shared(build, "libold.so.2", "libold.so.2", page=4096), libdir / "libold.so.2")
    whl = next(inputs["wheelhouse"].glob("*.whl"))
    import zipfile
    with zipfile.ZipFile(whl, "a") as archive:
        archive.write(_shared(build, "_old.so", "_old.so", needs=(libdir / "libold.so.2",)), "native/_old.so")

    manifest = payload.assemble(out=tmp_path / "out", **inputs)

    assert manifest["needs_4k_pages"] == ["libold_hermes.so"]


def test_two_libraries_that_would_share_a_packaged_name_are_refused(tmp_path):
    build, libdir = tmp_path / "build", tmp_path / "runtime-libs"
    build.mkdir()
    libdir.mkdir()
    one = _shared(build, "libz.so.1", "libz.so.1")
    two = _shared(build, "libz.so.2", "libz.so.2")
    for lib in (one, two):
        shutil.copy(lib, libdir / lib.name)
    ext = _shared(build, "_ext.so", "_ext.so", needs=(one, two))

    with pytest.raises(ValueError, match=r"libz\.so\.1.*libz\.so\.2|libz\.so\.2.*libz\.so\.1"):
        payload.vendor([ext], libdir, tmp_path / "jniLibs")


def _wheel(wheelhouse: Path, name: str, files: dict[str, str]) -> None:
    import zipfile

    wheelhouse.mkdir(exist_ok=True)
    with zipfile.ZipFile(wheelhouse / f"{name}-1.0-py3-none-any.whl", "w") as whl:
        for path, text in files.items():
            whl.writestr(path, text)


def test_a_wheels_library_trees_land_in_site_packages_and_its_programs_are_dropped(tmp_path):
    _wheel(tmp_path / "wheels", "split", {"split/__init__.py": "",
                                          "split-1.0.data/purelib/split/pure.py": "PURE = 1\n",
                                          "split-1.0.data/platlib/split_native.py": "",
                                          "split-1.0.data/scripts/split-cli": "#!python\n",
                                          "split-1.0.data/headers/split.h": "/* */\n"})
    site = tmp_path / "site-packages"

    payload._install_wheels(tmp_path / "wheels", site)

    assert (site / "split" / "pure.py").read_text() == "PURE = 1\n"
    assert (site / "split_native.py").exists()
    assert not list(site.glob("*.data"))


def test_a_wheel_with_files_for_the_install_prefix_is_refused(tmp_path):
    _wheel(tmp_path / "wheels", "shared", {"shared/__init__.py": "", "shared-1.0.data/data/share/shared/x.cfg": ""})

    with pytest.raises(ValueError, match=r"shared-1\.0-py3-none-any\.whl.*data"):
        payload._install_wheels(tmp_path / "wheels", tmp_path / "site-packages")
