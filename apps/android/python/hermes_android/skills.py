"""Skills turned on or off, and archived skills restored, by the app's own bridge calls (batch-3 F11, DC-M18;
batch-4 F10, DC-P20; rows capabilities-04, capabilities-02, capabilities-06; D26, E5).

``config.set`` has no ``skills.*`` key (4002 "unknown config key", ``tui_gateway/methods_config_set.py:488-495``),
and ``skills.manage list`` drops disabled skills and carries no enabled flag, so the Skills page has its own pair.
``listing`` is the REST route's (``GET /api/skills``): ``tools.skills_tool._find_all_skills(skip_disabled=True)``
with ``hermes_cli.skills_config.get_disabled_skills`` on the global list only, no platform list (D7). ``enable`` takes
a list of names, one switch and a category's alike (DC-M18), and edits ``skills.disabled`` minus the core's
``agent.skill_utils.ESSENTIAL_SKILLS`` inside one ``_config.mutate``, never ``.env``: the core's own writer,
``save_disabled_skills``, saves by itself, outside the app's lock, so it is not called. Which skills are essential
is the core's rule and stays here, never in Kotlin (DDD). Changes reach new chats; an open one keeps the prompt it
started with. Each row also says where the skill came from (the route's set-based classification: hub, else
bundled, else external, else agent) and how often it was used (``activity_count``: views, uses and patches).
``archived`` lists ``skills/.archive/`` and ``restore`` moves a skill back as ``hermes curator restore`` does
(``restore_skill`` with the ledger actor ``user``), a CLI the phone does not have (DC-P20).

The core is importable only after ``prepare()``, so the core imports sit inside the functions.
"""

from __future__ import annotations

import json
import logging

from hermes_android import _config

logger = logging.getLogger(__name__)

# tools/skill_manager_guards.py:150-152's essential-skill sentence, with "deleted" read as "disabled" (D2): the core
# has no sentence for a refused disable, and skills_config.py states the rule ("cannot be disabled from any surface").
REFUSED = ("Skill '{name}' is essential to Hermes (the agent's own operating manual referenced by the system prompt) "
           "and cannot be disabled.")


def listing(_arg: str) -> str:
    """Every skill the core lists, in its listing order (``_sort_skills``'s key, D9), with the global list's answer."""
    from hermes_cli.config import load_config
    from hermes_cli.skills_config import get_disabled_skills
    from tools.skill_usage import (
        _external_skill_names, _read_bundled_names, _read_hub_installed_names, activity_count, load_usage)
    from tools.skills_tool import _find_all_skills

    disabled = get_disabled_skills(load_config())
    rows = sorted(_find_all_skills(skip_disabled=True), key=lambda s: (s["category"] or "", s["name"]))
    usage = load_usage()
    # Read once per listing, as GET /api/skills does: hub > bundled > external > agent.
    bundled = _read_bundled_names()
    hub = _read_hub_installed_names()
    external = _external_skill_names() - bundled - hub
    return json.dumps({"skills": [{
        "name": s["name"],
        "description": "" if s["description"] is None else str(s["description"]),
        "category": s["category"],
        "enabled": s["name"] not in disabled,
        "provenance": ("hub" if s["name"] in hub else "bundled" if s["name"] in bundled
                       else "external" if s["name"] in external else "agent"),
        "uses": activity_count(usage.get(s["name"], {})),
    } for s in rows]})


def archived(_arg: str) -> str:
    """The archive directory names under ``skills/.archive/``, sorted (the core's list)."""
    from tools.skill_usage import list_archived_skill_names

    return json.dumps({"names": list_archived_skill_names()})


def restore(arg: str) -> str:
    """Moves ``{"name"}`` back from the archive as the user and answers the list re-read; a refusal is the core's
    words, and the core moves nothing then."""
    from tools import skill_ledger, skill_usage

    token = skill_ledger.set_ledger_actor("user")
    try:
        ok, message = skill_usage.restore_skill(json.loads(arg)["name"])
    finally:
        skill_ledger.reset_ledger_actor(token)
    if not ok:
        return json.dumps({"error": message})
    _clear_caches()
    return listing("")


def _clear_caches() -> None:
    """What ``agent/learning_mutations.py`` does after a skill write, best effort: the next listing and the next
    chat's prompt see the restored skill."""
    from tools.skills_tool import clear_skills_cache

    clear_skills_cache()
    try:
        from agent.prompt_builder import clear_skills_system_prompt_cache

        clear_skills_system_prompt_cache(clear_snapshot=True)
    except Exception:  # health: allow BLE001 -- best effort, as learning_mutations does; the cache is rebuilt anyway
        logger.warning("could not clear the skills prompt cache after a restore", exc_info=True)


def enable(arg: str) -> str:
    """Turns the names on or off and answers the list re-read, never a prediction (EJ-51). Turning off only essential
    skills is refused in the core's words and writes nothing; among others they stay on (D1). Names are taken as
    given (D11)."""
    from agent.skill_utils import ESSENTIAL_SKILLS

    request = json.loads(arg)
    names = set(request["names"])
    on = request["enabled"]
    if not on and names and names <= ESSENTIAL_SKILLS:
        return json.dumps({"error": REFUSED.format(name=sorted(names)[0])})
    _config.mutate(lambda cfg: _apply(cfg, names, on))
    return listing("")


def _apply(cfg: dict, names: set[str], on: bool) -> None:
    """The edit itself, on a loaded config (no I/O): only ``skills.disabled``, and only when its set changes (D6)."""
    from agent.skill_utils import ESSENTIAL_SKILLS
    from hermes_cli.skills_config import get_disabled_skills

    was = get_disabled_skills(cfg)  # normalized, global only, already without the essential ones
    now = was - names if on else (was | names) - ESSENTIAL_SKILLS
    if now != was:
        cfg["skills"] = {**_section(cfg, "skills"), "disabled": sorted(now)}


def _section(cfg: dict, name: str) -> dict:
    section = cfg.get(name)
    return section if isinstance(section, dict) else {}
