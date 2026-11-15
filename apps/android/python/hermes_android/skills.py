"""Skills turned on or off, read and written by the app's own bridge pair (batch-3 F11, DC-M18; D26, E5).

``config.set`` has no ``skills.*`` key (4002 "unknown config key", ``tui_gateway/methods_config_set.py:488-495``),
and ``skills.manage list`` drops disabled skills and carries no enabled flag, so the Skills page has its own pair.
``listing`` is the REST route's (``GET /api/skills``): ``tools.skills_tool._find_all_skills(skip_disabled=True)``
with ``hermes_cli.skills_config.get_disabled_skills`` on the global list only, no platform list (D7). ``enable`` takes
a list of names, one switch and a category's alike (DC-M18), and edits ``skills.disabled`` minus the core's
``agent.skill_utils.ESSENTIAL_SKILLS`` inside one ``_config.mutate``, never ``.env``: the core's own writer,
``save_disabled_skills``, saves by itself, outside the app's lock, so it is not called. Which skills are essential
is the core's rule and stays here, never in Kotlin (DDD). Changes reach new chats; an open one keeps the prompt it
started with.

The core is importable only after ``prepare()``, so the core imports sit inside the functions.
"""

from __future__ import annotations

import json

from hermes_android import _config

# tools/skill_manager_guards.py:150-152's essential-skill sentence, with "deleted" read as "disabled" (D2): the core
# has no sentence for a refused disable, and skills_config.py states the rule ("cannot be disabled from any surface").
REFUSED = ("Skill '{name}' is essential to Hermes (the agent's own operating manual referenced by the system prompt) "
           "and cannot be disabled.")


def listing(_arg: str) -> str:
    """Every skill the core lists, in its listing order (``_sort_skills``'s key, D9), with the global list's answer."""
    from hermes_cli.config import load_config
    from hermes_cli.skills_config import get_disabled_skills
    from tools.skills_tool import _find_all_skills

    disabled = get_disabled_skills(load_config())
    rows = sorted(_find_all_skills(skip_disabled=True), key=lambda s: (s["category"] or "", s["name"]))
    return json.dumps({"skills": [{
        "name": s["name"],
        "description": "" if s["description"] is None else str(s["description"]),
        "category": s["category"],
        "enabled": s["name"] not in disabled,
    } for s in rows]})


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
