"""The Skills Hub's calls the core protocol has no method for (row capabilities-08, batch-4 F9, DC-P21; D26).

``official`` reproduces ``GET /api/skills/hub/official`` (``hermes_cli/web_routers/skills.py``): the official
optional skills shipped beside the core, read by ``OptionalSkillSource().list_local()``, a local scan of
``optional-skills/`` with no network. Each row's category is the identifier's (``official/<category>/<skill>``),
null when it has none. ``scan`` reproduces ``GET /api/skills/hub/scan``: the bundle is resolved from one source
(``_resolve_source_meta_and_bundle`` over ``create_source_router()``), written to the core's quarantine, scanned by
``scan_skill`` and judged by ``should_allow_install(force=False)``, and the quarantine is always removed. The phone
has no confirm or force path, so a policy that would ask reads as not allowed (D4). A failure is answered in the
route's words with any credential redacted (a fetch URL may carry a token); the Tier 1 advisory scan is not run.
Installing is ``skills.manage`` (P13). The core is importable only after ``prepare()``, so its imports sit inside
the functions.
"""

from __future__ import annotations

import json
import shutil

ORDER = {"critical": 0, "high": 1, "medium": 2, "low": 3}  # format_scan_report's order


def official(_arg: str) -> str:
    """One row per official optional skill: name, description, the identifier's category and the identifier."""
    from tools.skills_hub_official import OptionalSkillSource

    rows = []
    for meta in OptionalSkillSource().list_local():
        rest = meta.identifier.removeprefix("official/")
        rows.append({
            "name": meta.name,
            "description": meta.description or "",
            "category": rest.split("/", 1)[0] if "/" in rest else None,
            "identifier": meta.identifier,
        })
    return json.dumps({"skills": rows})


def scan(arg: str) -> str:
    """The install-time scan of ``{"identifier"}``'s bundle without installing it; ``{"error"}`` when there is no
    such bundle or the scan fails."""
    from agent.redact import redact_sensitive_text

    identifier = json.loads(arg)["identifier"]
    try:
        reply = _scan(identifier)
    except Exception as exc:  # health: allow BLE001 -- the route's boundary: any failure is answered in words
        words = redact_sensitive_text(str(exc), force=True, redact_url_credentials=True)
        return json.dumps({"error": f"Hub scan failed: {words}"})
    return json.dumps(reply if reply is not None else {"error": f"Skill not found: {identifier}"})


def _scan(identifier: str) -> dict | None:
    from hermes_cli.skills_hub import _resolve_source_meta_and_bundle
    from tools import skills_guard
    from tools.skills_hub_install import quarantine_bundle
    from tools.skills_hub_search import create_source_router

    meta, bundle, _source = _resolve_source_meta_and_bundle(identifier, create_source_router())
    if not bundle:
        return None
    source = "official" if bundle.source == "official" else (
        getattr(bundle, "identifier", "") or getattr(meta, "identifier", "") or identifier)
    quarantined = quarantine_bundle(bundle)
    try:
        result = skills_guard.scan_skill(quarantined, source=source)
    finally:
        shutil.rmtree(quarantined, ignore_errors=True)
    allowed, _reason = skills_guard.should_allow_install(result, force=False)
    findings = sorted(result.findings, key=lambda f: ORDER.get(f.severity, 4))
    return {
        "verdict": result.verdict,
        "allowed": allowed is True,
        "findings": [f"{f.severity.upper()} {f.category} {f.file}:{f.line} {f.description}" for f in findings],
        "trust": result.trust_level,
    }
