"""Skills turned on or off, and archived skills restored, through the app's own bridge calls (batch-3 F11,
DC-M18; batch-4 F10, DC-P20; rows capabilities-04, capabilities-02, capabilities-06).

``config.set`` has no ``skills.*`` key and ``skills.manage list`` drops disabled skills, so the Skills page reads
with ``skills_list`` (the REST route's listing: every skill, with the global ``skills.disabled`` answer, where it came
from and how often it was used) and writes with ``skills_enable(names, enabled)``: one ``_config.mutate`` (E5), never
``.env``, and never the core's essential skills (``agent.skill_utils.ESSENTIAL_SKILLS``), which a request to turn off
alone is refused in the core's words. ``skills_archived`` / ``skills_restore`` list ``skills/.archive/`` and move a
skill back as ``hermes curator restore`` does, as the user, refused in the core's words.
"""

from __future__ import annotations

import importlib
import json
import threading

import pytest

hermes_android = importlib.import_module("hermes_android")  # apps/android/python, put on sys.path by conftest

REFUSED = ("Skill 'hermes-agent' is essential to Hermes (the agent's own operating manual referenced by the system "
           "prompt) and cannot be disabled.")

SKILLS = (  # (relative dir, description)
    ("autonomous-ai-agents/hermes-agent", "Hermes's own manual"),
    ("autonomous-ai-agents/codex", "Delegate to Codex"),
    ("research/arxiv", "Search arXiv"),
    ("research/scholar", "Search Scholar"),
    ("plain", "A plain skill"),
)

SEEDED = """\
model:
  default: m1
skills:
  external_dirs: []
  disabled: []
"""


@pytest.fixture
def home():
    from hermes_constants import get_hermes_home

    path = get_hermes_home()  # the hermetic conftest's per-test home
    path.mkdir(parents=True, exist_ok=True)
    return path


def _write_skill(skills_dir, relative, description):
    d = skills_dir / relative
    d.mkdir(parents=True, exist_ok=True)
    name = d.name
    (d / "SKILL.md").write_text(f"---\nname: {name}\ndescription: {description}\n---\n\n# {name}\n", encoding="utf-8")


@pytest.fixture
def skills_tree(home, monkeypatch):
    from tools import skills_tool

    monkeypatch.setattr(skills_tool, "_SKILLS_CACHE", {})
    for relative, description in SKILLS:
        _write_skill(home / "skills", relative, description)
    return home


def _seed(home, text: str = SEEDED):
    config = home / "config.yaml"
    config.write_text(text, encoding="utf-8")
    return config


def _raw() -> dict:
    from hermes_cli.config import read_user_config_raw

    return read_user_config_raw()


def _listing() -> list[dict]:
    return json.loads(hermes_android.call("skills_list", ""))["skills"]


def _enable(names, enabled) -> dict:
    return json.loads(hermes_android.call("skills_enable", json.dumps({"names": names, "enabled": enabled})))


def _states(rows: list[dict]) -> dict[str, bool]:
    return {row["name"]: row["enabled"] for row in rows}


def _stamp(path):
    stat = path.stat()
    return path.read_bytes(), stat.st_ino, stat.st_mtime_ns


def test_the_list_is_every_installed_skill_with_its_category_and_state(skills_tree):
    assert _listing() == [
        {"name": "plain", "description": "A plain skill", "category": None, "enabled": True,
         "provenance": "agent", "uses": 0},
        {"name": "codex", "description": "Delegate to Codex", "category": "autonomous-ai-agents", "enabled": True,
         "provenance": "agent", "uses": 0},
        {"name": "hermes-agent", "description": "Hermes's own manual", "category": "autonomous-ai-agents",
         "enabled": True, "provenance": "agent", "uses": 0},
        {"name": "arxiv", "description": "Search arXiv", "category": "research", "enabled": True,
         "provenance": "agent", "uses": 0},
        {"name": "scholar", "description": "Search Scholar", "category": "research", "enabled": True,
         "provenance": "agent", "uses": 0},
    ]


def test_disabling_a_skill_writes_the_global_list_and_hides_it_from_the_agent(skills_tree):
    from agent.skill_utils import get_disabled_skill_names
    from tools.skills_tool import _find_all_skills

    _seed(skills_tree)

    reply = _enable(["arxiv"], False)

    assert _states(reply["skills"]) == {"plain": True, "codex": True, "hermes-agent": True, "arxiv": False,
                                        "scholar": True}
    raw = _raw()
    assert raw["skills"]["disabled"] == ["arxiv"]
    assert raw["skills"]["external_dirs"] == []
    assert raw["model"]["default"] == "m1"
    # The agent's own readers: the prompt builder's disabled set and the skills_list tool's listing.
    assert "arxiv" in get_disabled_skill_names()
    assert "arxiv" not in {s["name"] for s in _find_all_skills()}
    assert not (skills_tree / ".env").exists()


def test_re_enabling_a_skill_takes_it_out_again(skills_tree):
    _seed(skills_tree, SEEDED.replace("disabled: []", "disabled: [arxiv, scholar]"))

    reply = _enable(["arxiv"], True)

    assert _raw()["skills"]["disabled"] == ["scholar"]
    states = _states(reply["skills"])
    assert states["arxiv"] is True and states["scholar"] is False


def test_an_essential_skill_is_refused_in_the_cores_words_and_nothing_is_written(skills_tree):
    config = _seed(skills_tree)
    before = _stamp(config)

    assert _enable(["hermes-agent"], False) == {"error": REFUSED}

    assert _stamp(config) == before


def test_a_category_switch_turns_off_the_rest_and_keeps_the_essential_on(skills_tree):
    _seed(skills_tree)

    states = _states(_enable(["codex", "hermes-agent"], False)["skills"])

    assert states["hermes-agent"] is True and states["codex"] is False
    assert _raw()["skills"]["disabled"] == ["codex"]  # the file never holds hermes-agent


def test_an_essential_name_the_file_already_holds_reads_on_and_leaves_with_the_next_write(skills_tree):
    _seed(skills_tree, SEEDED.replace("disabled: []", "disabled: [hermes-agent, arxiv]"))

    assert _states(_listing())["hermes-agent"] is True

    _enable(["arxiv"], True)

    assert _raw()["skills"]["disabled"] == []  # the core's own reading (skills_config.py:40)


def test_only_the_global_list_is_read_and_written(skills_tree):
    _seed(skills_tree, SEEDED.replace("disabled: []", "platform_disabled:\n    telegram: [arxiv]"))

    assert _states(_listing())["arxiv"] is True

    _enable(["scholar"], False)

    raw = _raw()
    assert raw["skills"]["disabled"] == ["scholar"]
    assert raw["skills"]["platform_disabled"] == {"telegram": ["arxiv"]}


@pytest.mark.parametrize(("names", "enabled"), [(["arxiv"], True), ([], False)])
def test_a_switch_already_in_place_writes_nothing(skills_tree, names, enabled):
    config = _seed(skills_tree)
    before = _stamp(config)

    reply = _enable(names, enabled)

    assert [row["name"] for row in reply["skills"]] == ["plain", "codex", "hermes-agent", "arxiv", "scholar"]
    assert _stamp(config) == before


@pytest.mark.parametrize("request_", [
    {"names": "arxiv", "enabled": False},
    {"names": ["arxiv"], "enabled": False, "platform": "telegram"},
    {"names": ["arxiv"]},
])
def test_a_request_the_contract_does_not_name_is_refused_before_any_write(skills_tree, request_):
    config = _seed(skills_tree)
    before = _stamp(config)

    with pytest.raises(ValueError):
        hermes_android.call("skills_enable", json.dumps(request_))

    assert _stamp(config) == before


def test_a_write_waits_for_the_apps_config_lock(skills_tree):
    from hermes_android import _config, skills

    _seed(skills_tree)
    a_in, release = threading.Event(), threading.Event()

    def fn_a(cfg):
        a_in.set()
        release.wait(timeout=10)
        cfg.setdefault("providers", {})["a"] = {"name": "a", "base_url": "http://10.0.0.9/v1", "model": "m"}

    a = threading.Thread(target=_config.mutate, args=(fn_a,))
    a.start()
    assert a_in.wait(timeout=10)
    b = threading.Thread(target=skills.enable, args=(json.dumps({"names": ["arxiv"], "enabled": False}),))
    b.start()
    try:
        b.join(timeout=1.0)
        disabled = ((_raw() or {}).get("skills") or {}).get("disabled")
        assert b.is_alive() and not disabled, "the write landed while another app write held the span"
    finally:
        release.set()
        a.join(timeout=10)
        b.join(timeout=10)

    raw = _raw()
    assert "a" in raw["providers"]
    assert raw["skills"]["disabled"] == ["arxiv"]


# --- provenance and uses (batch-4 F10, row capabilities-02: GET /api/skills's classification) ---

def _provenance_tree(home, tmp_path):
    """The five seeded local skills, plus: arxiv bundled, codex hub-installed, scholar both (hub wins), one external
    skill under a skills.external_dirs entry, and usage counts for arxiv and plain."""
    skills = home / "skills"
    (skills / ".bundled_manifest").write_text("arxiv:abc123\nscholar:def456\n", encoding="utf-8")
    (skills / ".hub").mkdir(parents=True, exist_ok=True)
    (skills / ".hub" / "lock.json").write_text(json.dumps({"installed": {
        "codex": {"source": "github", "install_path": "autonomous-ai-agents/codex"},
        "scholar": {"source": "github", "install_path": "research/scholar"}}}), encoding="utf-8")
    external = tmp_path / "external-skills"
    _write_skill(external, "tools/mounted", "A mounted skill")
    (skills / ".usage.json").write_text(json.dumps({
        "arxiv": {"use_count": 2, "view_count": 3, "patch_count": 1},
        "plain": {"view_count": 4}}), encoding="utf-8")
    _seed(home, SEEDED.replace("external_dirs: []", f"external_dirs: [{external}]"))


def test_each_skill_says_where_it_came_from_and_how_often_it_was_used(skills_tree, tmp_path):
    _provenance_tree(skills_tree, tmp_path)

    rows = {row["name"]: row for row in _listing()}

    assert {name: row["provenance"] for name, row in rows.items()} == {
        "plain": "agent", "codex": "hub", "hermes-agent": "agent", "arxiv": "bundled", "scholar": "hub",
        "mounted": "external"}
    assert {name: row["uses"] for name, row in rows.items()} == {
        "plain": 4, "codex": 0, "hermes-agent": 0, "arxiv": 6, "scholar": 0, "mounted": 0}


def test_a_switch_answers_the_same_provenance_and_uses(skills_tree, tmp_path):
    _provenance_tree(skills_tree, tmp_path)

    rows = {row["name"]: row for row in _enable(["arxiv"], False)["skills"]}

    assert rows["arxiv"]["provenance"] == "bundled" and rows["arxiv"]["uses"] == 6
    assert rows["arxiv"]["enabled"] is False
    assert rows["mounted"]["provenance"] == "external"


# --- the archive (batch-4 F10, row capabilities-06, DC-P20: hermes curator restore, in-app) ---

def _archived() -> list[str]:
    return json.loads(hermes_android.call("skills_archived", ""))["names"]


def _restore(name: str) -> dict:
    return json.loads(hermes_android.call("skills_restore", json.dumps({"name": name})))


def _archive(name: str):
    from tools import skill_usage, skills_tool

    skill_usage.mark_agent_created(name)
    ok, msg = skill_usage.archive_skill(name)
    assert ok, msg
    skills_tool.clear_skills_cache()


def test_an_archived_skill_is_listed_and_restored_into_the_list(skills_tree):
    assert _archived() == []
    _archive("plain")

    assert _archived() == ["plain"]
    assert "plain" not in {row["name"] for row in _listing()}

    reply = _restore("plain")

    assert "plain" in {row["name"] for row in reply["skills"]}
    assert (skills_tree / "skills" / "plain" / "SKILL.md").is_file()
    assert _archived() == []


def _tree(path):
    return sorted(str(p.relative_to(path)) for p in path.rglob("*"))


def test_a_name_the_archive_lacks_is_refused_in_the_cores_words_and_moves_nothing(skills_tree):
    _archive("plain")
    before = _tree(skills_tree / "skills")

    assert _restore("nope") == {"error": "skill 'nope' not found in archive"}

    assert _tree(skills_tree / "skills") == before


def test_a_skill_now_bundled_is_refused_in_the_cores_words_and_moves_nothing(skills_tree):
    _archive("plain")
    (skills_tree / "skills" / ".bundled_manifest").write_text("plain:abc\n", encoding="utf-8")
    before = _tree(skills_tree / "skills")

    assert _restore("plain") == {
        "error": "skill 'plain' is now bundled; restore would shadow the upstream version"}

    assert _tree(skills_tree / "skills") == before
    assert _archived() == ["plain"]


def test_a_restore_runs_as_the_user_and_the_actor_is_reset_after_a_raise(skills_tree, monkeypatch):
    from tools import skill_ledger, skill_usage

    seen = []

    def fake(name):
        seen.append(skill_ledger._actor_override.get())
        raise RuntimeError("boom")

    monkeypatch.setattr(skill_usage, "restore_skill", fake)

    with pytest.raises(RuntimeError):
        _restore("plain")

    assert seen == ["user"]
    assert skill_ledger._actor_override.get() is None
