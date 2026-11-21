"""Tests: skills.manage search path (tui_gateway/methods_tools.py::_skills_search).

The RPC runs `unified_search` over the hub's sources and answers each hit as
`SkillHubHit {name, description}` (contracts/tools_mcp_plugins.py). Contracts:
- a search with hits answers every hit's own name and description, in the order
  `unified_search` gave, and no error — the rows are `SkillMeta`, whose text is the
  `description` field (it has no `describe()`; before this, every search with a hit
  answered 5024 "'SkillMeta' object has no attribute 'describe'");
- `command.resolve` keeps `CommandDef.describe()`, the localized slash-command text,
  so the fix must not spread to that call site.
"""

import tui_gateway.server as srv
from tui_gateway.contracts.tools_mcp_plugins import SkillsManageResult


def _call(method, params):
    return srv._methods[method](1, params)


def test_a_search_with_hits_answers_each_hits_name_and_description(monkeypatch):
    """Patch the search at the module the RPC resolves it from; no source is ever asked."""
    import tools.skills_hub_search as hub_search
    from tools.skills_hub_models import SkillMeta

    rows = [
        SkillMeta(
            name="blackbox",
            description="Delegate coding tasks to Blackbox AI",
            source="official",
            identifier="official/autonomous-ai-agents/blackbox",
            trust_level="builtin",
        ),
        SkillMeta(
            name="blackbox-notes",
            description="Keep notes about black boxes",
            source="github",
            identifier="someone/skills/blackbox-notes",
            trust_level="community",
        ),
    ]
    calls = []

    def fake_unified_search(query, sources, source_filter="all", limit=10):
        calls.append({"query": query, "sources": sources, "limit": limit})
        return rows

    monkeypatch.setattr(hub_search, "create_source_router", lambda auth=None: [])
    monkeypatch.setattr(hub_search, "unified_search", fake_unified_search)

    out = _call("skills.manage", {"action": "search", "query": "blackbox"})

    assert "error" not in out, out.get("error")
    assert out["result"] == {
        "results": [
            {"name": "blackbox", "description": "Delegate coding tasks to Blackbox AI"},
            {"name": "blackbox-notes", "description": "Keep notes about black boxes"},
        ]
    }
    SkillsManageResult.model_validate(out["result"])
    assert [c["query"] for c in calls] == ["blackbox"]
    assert calls[0]["sources"] == []


def test_command_resolve_keeps_the_localized_command_description(monkeypatch):
    """`command.resolve` rows are `CommandDef`, whose `describe()` is the localized text;
    a `.description` read there would drop the translation."""
    from hermes_cli import commands

    monkeypatch.setattr(commands, "_localized", lambda key, default: f"<{key}>")

    out = _call("command.resolve", {"name": "new"})

    assert "error" not in out, out.get("error")
    assert out["result"]["canonical"] == "new"
    assert out["result"]["description"] == "<slash.new.description>"
