"""The app's own MCP calls into its Python half (rows capabilities-15..19; batch-5 F1, F6, F9, F12, F15, F18; D26).

The MCP pages read the curated catalog, switch a server on or off and point a remote server's header at a keystore
key through declared bridge calls, never through the core's writers that put a key in ``.env`` (D8, D50):
``mcp_catalog`` reads the bundled manifests locally (as ``GET /api/mcp/catalog``), ``mcp_set_enabled`` writes one
``enabled`` key (as ``PUT /api/mcp/servers/{name}/enabled``) and ``mcp_key_ref`` writes only a ``${VAR}`` template
under the name the core derives for that server and header, so a header can never read another key from the
process env. Every write is one ``_config.mutate`` span. The core is the oracle for the catalog and the env-key rule.
"""

from __future__ import annotations

import json
import os
import re
import socket
import threading

import pytest

TEMPLATE = re.compile(r"(Bearer )?\$\{[A-Z_][A-Z0-9_]*\}")

NOT_FOUND = "Server '{name}' not found"
PLUGIN = "Server '{name}' is provided by plugin '{plugin}' and cannot be modified"
MALFORMED = "Malformed server config"
BAD_HEADER = "A header name is letters, digits and - only."
WRONG_NAME = "The {header} header of {name} reads its key from {env_var}; nothing was changed."
NO_URL = "Server '{name}' has no URL, so it sends no headers."


@pytest.fixture
def home():
    from hermes_constants import get_hermes_home

    path = get_hermes_home()  # the hermetic conftest's per-test home
    path.mkdir(parents=True, exist_ok=True)
    return path


def _ask(function: str, request: dict | None = None) -> dict:
    import hermes_android

    return json.loads(hermes_android.call(function, json.dumps(request or {})))


def _write(home, text: str) -> bytes:
    path = home / "config.yaml"
    path.write_text(text, encoding="utf-8")
    return path.read_bytes()


def _raw() -> dict:
    from hermes_cli.config import read_user_config_raw

    return read_user_config_raw() or {}


# ---------------------------------------------------------------------------------------------------- mcp_catalog


def test_the_catalog_answers_one_entry_per_bundled_manifest(home):
    from hermes_cli.mcp_catalog import list_catalog

    entries = _ask("mcp_catalog")["entries"]  # call() holds every entry to McpCatalogEntry

    assert [e["name"] for e in entries] == [e.name for e in list_catalog()]
    assert len(entries) >= 60
    by_name = {e["name"]: e for e in entries}
    for entry in list_catalog():
        row = by_name[entry.name]
        assert row["url"] == entry.transport.url and row["transport"] == entry.transport.type
        assert row["auth"] == entry.auth.type and row["connector_slug"] == entry.connector_slug
        assert row["description"] == (entry.description or "")


def test_a_catalog_entry_names_the_env_values_it_needs_and_never_a_value(home):
    from hermes_cli.mcp_catalog import get_entry

    by_name = {e["name"]: e for e in _ask("mcp_catalog")["entries"]}
    asana = get_entry("asana")
    prompts = {v.name: v.prompt for v in asana.auth.env}

    assert by_name["asana"]["auth"] == "oauth"
    assert by_name["asana"]["env"] == [
        {"name": "ASANA_CLIENT_ID", "prompt": prompts["ASANA_CLIENT_ID"], "required": True, "secret": False},
        {"name": "ASANA_CLIENT_SECRET", "prompt": prompts["ASANA_CLIENT_SECRET"], "required": True, "secret": True}]
    assert [v["name"] for v in by_name["n8n-official"]["env"]] == ["N8N_MCP_SERVER_URL"]


def test_a_catalog_entry_carries_its_tool_defaults(home):
    from hermes_cli.mcp_catalog import get_entry

    by_name = {e["name"]: e for e in _ask("mcp_catalog")["entries"]}

    assert by_name["gamma"]["exclude"] == get_entry("gamma").tools.default_excluded and by_name["gamma"]["include"] is None
    assert by_name["motherduck"]["include"] == get_entry("motherduck").tools.default_enabled  # a default_enabled manifest
    assert by_name["motherduck"]["exclude"] is None
    assert by_name["linear"]["include"] is None and by_name["linear"]["exclude"] is None


def test_the_catalog_says_which_entries_are_configured_and_on(home):
    _write(home, "mcp_servers:\n  linear:\n    url: https://mcp.linear.app/mcp\n    enabled: false\n"
                 "  notion:\n    url: https://mcp.notion.com/mcp\n")

    by_name = {e["name"]: e for e in _ask("mcp_catalog")["entries"]}

    assert (by_name["linear"]["installed"], by_name["linear"]["enabled"]) == (True, False)
    assert (by_name["notion"]["installed"], by_name["notion"]["enabled"]) == (True, True)
    assert all((e["installed"], e["enabled"]) == (False, False) for n, e in by_name.items() if n not in {"linear", "notion"})


def test_the_catalog_answers_no_env_value(home, monkeypatch):
    (home / ".env").write_text("ASANA_CLIENT_SECRET=canary-mcp-env-1\n", encoding="utf-8")
    monkeypatch.setenv("ASANA_CLIENT_SECRET", "canary-mcp-env-1")
    import hermes_android

    assert "canary-mcp-env-1" not in hermes_android.call("mcp_catalog", "")


def test_the_catalog_needs_no_network(home, monkeypatch):
    def refuse(*_a, **_k):
        raise OSError("no network in this test")

    monkeypatch.setattr(socket.socket, "connect", refuse)

    assert len(_ask("mcp_catalog")["entries"]) >= 60


# ------------------------------------------------------------------------------------------------ mcp_set_enabled

TWO = ("model:\n  default: m1\nmcp_servers:\n"
       "  a:\n    url: https://a.example/mcp\n    headers:\n      Authorization: Bearer ${MCP_A_API_KEY}\n"
       "    tools:\n      include: [one]\n"
       "  b:\n    url: https://b.example/mcp\n    headers:\n      X-Trace: ${MCP_B_X_TRACE}\n"
       "    tools:\n      exclude: [two]\n")


def test_a_server_is_switched_off_and_on_and_nothing_else_changes(home):
    _write(home, TWO)
    before = _raw()

    assert _ask("mcp_set_enabled", {"name": "a", "enabled": False}) == {"enabled": False}

    after = _raw()
    assert after["mcp_servers"]["a"] == {**before["mcp_servers"]["a"], "enabled": False}
    assert after["mcp_servers"]["b"] == before["mcp_servers"]["b"]
    assert all(after[key] == before[key] for key in before if key != "mcp_servers")

    assert _ask("mcp_set_enabled", {"name": "a", "enabled": True}) == {"enabled": True}
    assert _raw()["mcp_servers"]["a"]["enabled"] is True
    assert not (home / ".env").exists()


def test_switching_to_the_value_already_set_writes_nothing(home):
    _write(home, TWO)
    _ask("mcp_set_enabled", {"name": "b", "enabled": False})
    before = (home / "config.yaml").stat()

    assert _ask("mcp_set_enabled", {"name": "b", "enabled": False}) == {"enabled": False}

    after = (home / "config.yaml").stat()
    assert (after.st_ino, after.st_mtime_ns) == (before.st_ino, before.st_mtime_ns)


@pytest.mark.parametrize(("config", "name", "words"), [
    (TWO, "zz", NOT_FOUND.format(name="zz")),
    ("model:\n  default: m1\n", "a", NOT_FOUND.format(name="a")),
    ("mcp_servers:\n  a: oops\n", "a", MALFORMED),
])
def test_an_unknown_or_malformed_server_is_refused_in_the_routes_words(home, config, name, words):
    before = _write(home, config)

    assert _ask("mcp_set_enabled", {"name": name, "enabled": False}) == {"error": words}
    assert (home / "config.yaml").read_bytes() == before
    assert not (home / ".env").exists()


def _plugin_owns_p(monkeypatch):
    import tui_gateway.mcp_rpc_helpers as helpers

    monkeypatch.setattr(helpers, "server_configs_with_sources",
                        lambda servers: ({**servers, "p": {"url": "https://p.example/mcp"}},
                                         {**{n: None for n in servers}, "p": "myplugin"}))


def test_a_plugins_server_is_not_switched(home, monkeypatch):
    before = _write(home, TWO)
    _plugin_owns_p(monkeypatch)

    assert _ask("mcp_set_enabled", {"name": "p", "enabled": False}) == {"error": PLUGIN.format(name="p", plugin="myplugin")}
    assert (home / "config.yaml").read_bytes() == before
    assert not (home / ".env").exists()


def _waits_for_the_config_lock(home, edit, written):
    """``edit`` runs while another app write holds the span: it must not write until that one is released, and
    both edits must survive."""
    from hermes_android import _config

    a_in, release = threading.Event(), threading.Event()

    def fn_a(cfg):
        a_in.set()
        release.wait(timeout=10)
        cfg.setdefault("providers", {})["held"] = {"name": "held", "base_url": "http://10.0.0.9/v1", "model": "m"}

    a = threading.Thread(target=_config.mutate, args=(fn_a,))
    a.start()
    assert a_in.wait(timeout=10)
    b = threading.Thread(target=edit)
    b.start()
    try:
        b.join(timeout=1.0)
        assert b.is_alive() and not written(_raw()), "the MCP write happened while another app write held the span"
    finally:
        release.set()
        a.join(timeout=10)
        b.join(timeout=10)

    raw = _raw()
    assert "held" in raw["providers"]
    assert written(raw)


def test_the_switch_waits_for_the_apps_config_lock(home):
    _write(home, TWO)

    _waits_for_the_config_lock(
        home, lambda: _ask("mcp_set_enabled", {"name": "a", "enabled": False}),
        lambda raw: (raw.get("mcp_servers") or {}).get("a", {}).get("enabled") is False)


# ---------------------------------------------------------------------------------------------------- mcp_key_ref

SRV = ("mcp_servers:\n"
       "  srv:\n    url: https://srv.example/mcp\n    headers:\n      X-Trace: ${MCP_SRV_X_TRACE}\n"
       "  other:\n    url: https://other.example/mcp\n"
       "  loc:\n    command: python3\n    args: [-m, server]\n")


def _headers() -> dict:
    return _raw()["mcp_servers"]["srv"]["headers"]


def test_authorization_is_pointed_at_the_servers_own_key_and_other_headers_stay(home):
    _write(home, SRV)

    answer = _ask("mcp_key_ref", {"name": "srv", "header": "Authorization", "env_var": "MCP_SRV_API_KEY"})

    assert answer == {"headers": ["Authorization", "X-Trace"]}
    assert _headers() == {"Authorization": "Bearer ${MCP_SRV_API_KEY}", "X-Trace": "${MCP_SRV_X_TRACE}"}
    assert not (home / ".env").exists()


def test_another_header_reads_its_own_derived_key(home):
    _write(home, SRV)

    assert _ask("mcp_key_ref", {"name": "srv", "header": "X-Api-Key", "env_var": "MCP_SRV_X_API_KEY"}) == \
        {"headers": ["X-Api-Key", "X-Trace"]}
    assert _headers()["X-Api-Key"] == "${MCP_SRV_X_API_KEY}"


def test_a_header_differing_only_in_case_is_replaced_not_duplicated(home):
    _write(home, "mcp_servers:\n  srv:\n    url: https://srv.example/mcp\n    headers:\n      authorization: Bearer old\n")

    assert _ask("mcp_key_ref", {"name": "srv", "header": "Authorization", "env_var": "MCP_SRV_API_KEY"}) == \
        {"headers": ["Authorization"]}
    assert _headers() == {"Authorization": "Bearer ${MCP_SRV_API_KEY}"}


def test_the_derived_name_is_the_cores(home):
    from hermes_cli.mcp_config import _env_key_for_server

    _write(home, "mcp_servers:\n  my.server-1:\n    url: https://m.example/mcp\n")
    derived = _env_key_for_server("my.server-1")

    assert derived == "MCP_MY_SERVER_1_API_KEY"
    assert "error" in _ask("mcp_key_ref", {"name": "my.server-1", "header": "Authorization", "env_var": "MCP_MY_SERVER_1_KEY"})
    assert _ask("mcp_key_ref", {"name": "my.server-1", "header": "Authorization", "env_var": derived}) == \
        {"headers": ["Authorization"]}


@pytest.mark.parametrize(("name", "header", "env_var", "words"), [
    ("srv", "Authorization", "OPENROUTER_API_KEY", WRONG_NAME.format(header="Authorization", name="srv", env_var="MCP_SRV_API_KEY")),
    ("srv", "Authorization", "TERMINAL_SSH_PRIVATE_KEY", WRONG_NAME.format(header="Authorization", name="srv", env_var="MCP_SRV_API_KEY")),
    ("srv", "Authorization", "TELEGRAM_BOT_TOKEN", WRONG_NAME.format(header="Authorization", name="srv", env_var="MCP_SRV_API_KEY")),
    ("srv", "Authorization", "MCP_OTHER_API_KEY", WRONG_NAME.format(header="Authorization", name="srv", env_var="MCP_SRV_API_KEY")),
    ("srv", "Authorization", "mcp_srv_api_key", WRONG_NAME.format(header="Authorization", name="srv", env_var="MCP_SRV_API_KEY")),
    ("srv", "Authorization", "MCP_SRV_API_KEY}", WRONG_NAME.format(header="Authorization", name="srv", env_var="MCP_SRV_API_KEY")),
    ("srv", "X-Api-Key", "MCP_SRV_API_KEY", WRONG_NAME.format(header="X-Api-Key", name="srv", env_var="MCP_SRV_X_API_KEY")),
    ("srv", "Bad Header", "MCP_SRV_BAD_HEADER", BAD_HEADER),
    ("srv", "X:Y", "MCP_SRV_X_Y", BAD_HEADER),
    ("srv", "", "MCP_SRV_", BAD_HEADER),
    ("zz", "Authorization", "MCP_ZZ_API_KEY", NOT_FOUND.format(name="zz")),
    ("loc", "Authorization", "MCP_LOC_API_KEY", NO_URL.format(name="loc")),
    ("p", "Authorization", "MCP_P_API_KEY", PLUGIN.format(name="p", plugin="myplugin")),
])
def test_a_header_is_never_pointed_at_another_key_or_a_server_that_sends_none(home, monkeypatch, name, header, env_var, words):
    before = _write(home, SRV)
    _plugin_owns_p(monkeypatch)

    assert _ask("mcp_key_ref", {"name": name, "header": header, "env_var": env_var}) == {"error": words}
    assert (home / "config.yaml").read_bytes() == before
    assert not (home / ".env").exists()


def test_a_malformed_server_is_refused(home):
    before = _write(home, "mcp_servers:\n  srv: oops\n")

    assert _ask("mcp_key_ref", {"name": "srv", "header": "Authorization", "env_var": "MCP_SRV_API_KEY"}) == {"error": MALFORMED}
    assert (home / "config.yaml").read_bytes() == before


def test_the_config_never_holds_a_header_value_only_templates(home, monkeypatch):
    # F15: a backup keeps only templates, so the one new writer of headers must write nothing else.
    monkeypatch.setenv("MCP_SRV_API_KEY", "canary-header-1")
    _write(home, SRV.replace("X-Trace: ${MCP_SRV_X_TRACE}", "authorization: Bearer old\n      X-Trace: ${MCP_SRV_X_TRACE}"))
    _ask("mcp_key_ref", {"name": "srv", "header": "Authorization", "env_var": "MCP_SRV_API_KEY"})
    _ask("mcp_key_ref", {"name": "srv", "header": "X-Api-Key", "env_var": "MCP_SRV_X_API_KEY"})
    _ask("mcp_key_ref", {"name": "other", "header": "Authorization", "env_var": "MCP_OTHER_API_KEY"})

    servers = _raw()["mcp_servers"]
    values = [v for cfg in servers.values() if isinstance(cfg, dict) for v in (cfg.get("headers") or {}).values()]
    assert len(values) == 4 and all(TEMPLATE.fullmatch(v) for v in values), values
    assert "canary-header-1" not in (home / "config.yaml").read_text(encoding="utf-8")
    assert not (home / ".env").exists()
    assert "canary-header-1" == os.environ["MCP_SRV_API_KEY"]  # the env is only read by the core at connect time


def test_pointing_a_header_waits_for_the_apps_config_lock(home):
    _write(home, SRV)

    _waits_for_the_config_lock(
        home, lambda: _ask("mcp_key_ref", {"name": "srv", "header": "Authorization", "env_var": "MCP_SRV_API_KEY"}),
        lambda raw: "Authorization" in (((raw.get("mcp_servers") or {}).get("srv") or {}).get("headers") or {}))


# ------------------------------------------------------------------------------------------- mcp_pip_start/poll/cancel
# F18: a Python server's package installs only through one named PyPI requirement, as a child of the app's launcher,
# one job at a time, polled by quick calls. A fake launcher stands in for pip (no pip, no network in these tests).

BAD_REQUIREMENT = "Only a package name from PyPI, optionally with ==version, can be installed."
BAD_SERVER = "A server name is letters, digits, '.', '_' or '-' (at most 64) and does not start with '.'."
NO_LAUNCHER = "Python packages cannot be installed here: this install has no Python launcher."
BUSY = "Another package is still installing. Wait for it or cancel it."
UNKNOWN_JOB = "That install is not known to the app any more."
PLANTED = {"MCP_SRV_API_KEY": "canary-pip-1", "OPENROUTER_API_KEY": "canary-pip-2", "PIP_INDEX_URL": "http://evil"}

FAKE_PIP = '''#!{python}
import json, os, sys, time
home = os.environ["HOME"]
with open(os.path.join(home, "fake-pip-seen.json"), "w") as seen:
    json.dump({{"argv": sys.argv[1:], "env": sorted(os.environ), "pid": os.getpid()}}, seen)
plan_path = os.path.join(home, "fake-pip.json")
plan = json.load(open(plan_path)) if os.path.exists(plan_path) else {{}}
print(plan.get("print", "Successfully installed"), flush=True)
time.sleep(plan.get("sleep", 0))
target = sys.argv[sys.argv.index("--target") + 1]
for rel in plan.get("files", ["pkg/__init__.py"]):
    os.makedirs(os.path.dirname(os.path.join(target, rel)), exist_ok=True)
    open(os.path.join(target, rel), "w").close()
sys.exit(plan.get("exit", 0))
'''


@pytest.fixture
def pip(home, tmp_path, monkeypatch):
    """A home whose ``bin/python3`` is a fake pip, the env the app's interpreter has, and no job left over."""
    import sys

    from hermes_android import launcher, mcp

    files, native_libs, runtime = tmp_path / "files", tmp_path / "app-lib" / "arm64", tmp_path / "runtime" / "b1"
    native_libs.mkdir(parents=True)
    runtime.mkdir(parents=True)
    (tmp_path / "tmp").mkdir()
    fake = native_libs / "libhermes_pyexec.so"
    fake.write_text(FAKE_PIP.format(python=sys.executable), encoding="utf-8")
    fake.chmod(0o755)
    assert launcher.ensure(str(files), str(native_libs), str(runtime))
    monkeypatch.setenv("HOME", str(files))
    monkeypatch.setenv("TMPDIR", str(tmp_path / "tmp"))
    monkeypatch.setenv("HERMES_HOME", str(home))
    for name, value in PLANTED.items():
        monkeypatch.setenv(name, value)
    monkeypatch.setattr(mcp, "_job", None, raising=False)
    yield files
    job = getattr(mcp, "_job", None)
    if job is not None and job.proc.poll() is None:
        job.proc.kill()
        job.proc.wait(timeout=5)


def _plan(files, **plan):
    (files / "fake-pip.json").write_text(json.dumps(plan), encoding="utf-8")


def _seen(files):
    path = files / "fake-pip-seen.json"
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def _start(server="srv", requirement="mcp-server-time==2025.4.7") -> dict:
    return _ask("mcp_pip_start", {"server": server, "requirement": requirement})


def _finished(job: str, within: float = 10.0) -> dict:
    import time

    deadline = time.monotonic() + within
    while True:
        answer = _ask("mcp_pip_poll", {"job": job})
        if answer.get("state") != "running" or time.monotonic() > deadline:
            return answer
        time.sleep(0.05)


def _packages(home):
    return home / "mcp-packages"


def test_one_named_requirement_reaches_pip_isolated_wheels_only_and_with_no_secret(pip, home):
    job = _start()["job"]
    assert _finished(job)["state"] == "done"

    seen = _seen(pip)
    assert seen["argv"] == ["-m", "pip", "install", "--isolated", "--no-input", "--disable-pip-version-check",
                            "--only-binary=:all:", "--target", str(_packages(home) / f".staging-{job}"),
                            "mcp-server-time==2025.4.7"]
    assert not set(seen["env"]) & {*PLANTED, "HERMES_HOME"}
    assert set(seen["env"]) <= {"HOME", "PATH", "TMPDIR", "LANG", "LC_CTYPE"}  # LC_CTYPE: the interpreter's own


def test_a_finished_install_is_moved_into_place_and_stays_done(pip, home):
    job = _start()["job"]

    assert _finished(job)["state"] == "done"
    assert (_packages(home) / "srv" / "pkg" / "__init__.py").is_file()
    assert not list(_packages(home).glob(".staging-*"))
    assert _ask("mcp_pip_poll", {"job": job})["state"] == "done"


def test_a_reinstall_replaces_the_old_package_whole(pip, home):
    (_packages(home) / "srv").mkdir(parents=True)
    (_packages(home) / "srv" / "old.py").write_text("", encoding="utf-8")

    assert _finished(_start()["job"])["state"] == "done"
    assert not (_packages(home) / "srv" / "old.py").exists()
    assert (_packages(home) / "srv" / "pkg" / "__init__.py").is_file()


def test_a_failed_install_keeps_the_old_package_and_redacts_its_output(pip, home):
    (_packages(home) / "srv").mkdir(parents=True)
    (_packages(home) / "srv" / "keep.py").write_text("", encoding="utf-8")
    _plan(pip, exit=1, print="ERROR: Could not find a version (https://user:hunter2@host/simple/x)")

    answer = _finished(_start()["job"])

    assert answer["state"] == "failed"
    assert "ERROR" in answer["tail"] and "hunter2" not in answer["tail"]
    assert not list(_packages(home).glob(".staging-*"))
    assert sorted(p.name for p in (_packages(home) / "srv").iterdir()) == ["keep.py"]


def test_one_job_at_a_time_and_cancel_kills_the_child(pip, home):
    import time

    _plan(pip, sleep=60)
    job = _start()["job"]
    deadline = time.monotonic() + 10
    while _seen(pip) is None and time.monotonic() < deadline:
        time.sleep(0.05)
    pid = _seen(pip)["pid"]

    assert _start(server="other") == {"error": BUSY}
    began = time.monotonic()
    assert _ask("mcp_pip_cancel", {"job": job}) == {"state": "cancelled"}
    assert time.monotonic() - began < 5
    with pytest.raises(ProcessLookupError):
        os.kill(pid, 0)
    assert not list(_packages(home).glob(".staging-*"))
    assert not (_packages(home) / "srv").exists()
    assert _ask("mcp_pip_poll", {"job": job})["state"] == "cancelled"

    _plan(pip)
    assert _finished(_start(server="other")["job"])["state"] == "done"


def test_cancelling_a_finished_job_answers_its_final_state(pip):
    job = _start()["job"]
    assert _finished(job)["state"] == "done"

    assert _ask("mcp_pip_cancel", {"job": job}) == {"state": "done"}


def test_a_staging_dir_left_by_a_killed_process_is_dropped_at_the_next_start(pip, home):
    (_packages(home) / ".staging-dead" / "half").mkdir(parents=True)

    assert _finished(_start()["job"])["state"] == "done"
    assert not (_packages(home) / ".staging-dead").exists()


@pytest.mark.parametrize("requirement", [
    "https://x/y.whl", "git+https://h/r", "./pkg", "/abs/pkg", "pkg @ https://h/p.whl", "-r req.txt",
    "--index-url=http://evil", "pkg[extra]", "pkg>=1", "pkg==1.*", 'pkg; python_version>"3"', "pkg==1.0 --pre",
    "", " pkg", "pkg\n", "pkg==", "-pkg",
])
def test_anything_but_a_name_with_an_optional_exact_pin_is_refused(pip, requirement):
    assert _start(requirement=requirement) == {"error": BAD_REQUIREMENT}
    assert _seen(pip) is None


@pytest.mark.parametrize("requirement", ["mcp-server-time", "mcp_server_fetch==2025.4.7", "Foo.Bar", "a"])
def test_a_name_with_an_optional_exact_pin_is_installed(pip, requirement):
    assert _finished(_start(requirement=requirement)["job"])["state"] == "done"
    assert _seen(pip)["argv"][-1] == requirement


@pytest.mark.parametrize("server", ["..", ".", ".hidden", "a/b", "", "a" * 65])
def test_a_server_name_that_is_no_plain_directory_name_is_refused(pip, server):
    assert _start(server=server) == {"error": BAD_SERVER}
    assert _seen(pip) is None


def test_nothing_installs_without_a_launcher(pip, tmp_path):
    from hermes_android import launcher

    empty = tmp_path / "no-launcher"
    empty.mkdir()
    assert not launcher.ensure(str(pip), str(empty), str(tmp_path / "runtime" / "b1"))

    assert _start() == {"error": NO_LAUNCHER}
    assert _seen(pip) is None


@pytest.mark.parametrize("function", ["mcp_pip_poll", "mcp_pip_cancel"])
def test_an_unknown_job_is_said_so(pip, function):
    assert _ask(function, {"job": "nope"}) == {"error": UNKNOWN_JOB}
