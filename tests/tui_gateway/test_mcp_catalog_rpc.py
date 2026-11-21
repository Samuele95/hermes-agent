"""E2E tests for the ``mcp.catalog`` RPC's per-preset fields.

These drive the real registered gateway handler against a temp ``HERMES_HOME``
(and, for the fixture test, a temp catalog via ``HERMES_OPTIONAL_MCPS``) — no
mocks of the catalog or config layer — and assert that each row carries the
names of the preset's required ``auth.env`` specs in ``requires`` and its
transport's ``type`` in ``transport``.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from tui_gateway import server


@pytest.fixture
def hermes_home(tmp_path, monkeypatch):
    home = tmp_path / "hermes_home"
    home.mkdir()
    monkeypatch.setenv("HERMES_HOME", str(home))
    return home


def _call(method, params=None):
    handler = server._methods[method]
    return handler(1, params or {})


def _result(resp):
    assert "error" not in resp, resp.get("error")
    return resp["result"]


def _write_manifest(catalog: Path, name: str, transport: dict, auth: dict) -> None:
    import hermes_yaml as yaml

    (catalog / name).mkdir(parents=True)
    (catalog / name / "manifest.yaml").write_text(yaml.safe_dump({
        "manifest_version": 1,
        "name": name,
        "description": f"{name} fixture preset.",
        "source": "tests/tui_gateway/test_mcp_catalog_rpc.py",
        "transport": transport,
        "auth": auth,
    }, sort_keys=False), encoding="utf-8")


def test_catalog_rows_carry_each_presets_required_env_names_and_transport_type(hermes_home, tmp_path, monkeypatch):
    catalog = tmp_path / "optional-mcps"
    _write_manifest(
        catalog, "fix-remote",
        {"type": "http", "url": "https://mcp.example.test/mcp"},
        {"type": "oauth", "env": [{"name": "FIX_CLIENT_ID", "secret": False}, {"name": "FIX_CLIENT_SECRET"}]},
    )
    _write_manifest(
        catalog, "fix-local",
        {"type": "stdio", "command": "fixture-bin", "args": ["--x"]},
        {"type": "none", "env": [{"name": "FIX_TOKEN"}, {"name": "FIX_REGION", "required": False, "secret": False}]},
    )
    monkeypatch.setenv("HERMES_OPTIONAL_MCPS", str(catalog))

    result = _result(_call("mcp.catalog"))

    assert result["servers"] == [
        {"name": "fix-local", "description": "fix-local fixture preset.", "connector_slug": None,
         "installed": False, "enabled": False, "requires": ["FIX_TOKEN"], "transport": "stdio"},
        {"name": "fix-remote", "description": "fix-remote fixture preset.", "connector_slug": None,
         "installed": False, "enabled": False, "requires": ["FIX_CLIENT_ID", "FIX_CLIENT_SECRET"],
         "transport": "http"},
    ]
    from tui_gateway.contracts.tools_mcp_plugins import McpCatalogResult

    McpCatalogResult.model_validate(result)


def test_bundled_presets_answer_their_manifests_env_names_and_http(hermes_home):
    from hermes_cli.mcp_catalog import get_entry, list_catalog

    rows = _result(_call("mcp.catalog"))["servers"]
    by_name = {row["name"]: row for row in rows}

    assert [row["name"] for row in rows] == [e.name for e in list_catalog()]
    assert by_name["asana"]["requires"] == ["ASANA_CLIENT_ID", "ASANA_CLIENT_SECRET"]
    assert by_name["n8n-official"]["requires"] == ["N8N_MCP_SERVER_URL"]
    for row in rows:
        assert row["transport"] == get_entry(row["name"]).transport.type
        assert row["transport"] in {"http", "stdio"}
