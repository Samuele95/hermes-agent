"""The app's export of a stored session into its cache (sessions-12, batch-3 design F8 / bet 9, DC-M13).

``session.save`` writes into the app-private home, where the user cannot reach it. Instead this writes the
file the core's own ``GET /api/sessions/{id}/export`` route streams (``hermes_cli/web_routers/sessions.py``):
the session's stored row, then every message incl. inactive ones, so a compressed history re-imports as
archived. The rows are read where they live, in keyset pages of 500 over the shared state.db handle, and
streamed to ``TMPDIR/exports/<title>-<id8>.json``; the bridge carries only the path, and the app hands it
to the share sheet. The previous export is deleted first, so no old transcript lingers. A session with no
stored row yet is refused in the app's words, and nothing is written. The core is importable only after
``prepare()``, so its imports sit inside the function. Nothing of the transcript is logged.
"""

from __future__ import annotations

import json
import os
import re
from pathlib import Path

PAGE = 500  # the route's page
NOT_SAVED = "Nothing to export yet: Hermes has not saved this conversation."


def _compact(obj) -> str:
    # The route's _compact_json without FastAPI's jsonable_encoder: the rows are plain JSON already (D2).
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def _safe(value: str) -> str:
    return re.sub(r"[^a-z0-9._-]+", "-", value.strip().lower()).strip("-")[:48]


def file_name(session_id: str, title: str | None) -> str:
    """Desktop's ``sessionExportFilename``: the title's safe form (or ``session``), then the safe id's start."""
    return f"{_safe(title or '') or 'session'}-{_safe(session_id)[:8] or 'session'}.json"


def export(arg: str) -> str:
    """Writes ``{"session_id"}``'s file and answers ``{"path", "name", "bytes"}``; a session with no stored
    row is ``{"error"}`` and leaves the previous export in place. A read that fails leaves no file behind."""
    import hermes_state_registry

    sid = json.loads(arg)["session_id"]
    db = hermes_state_registry.acquire()
    try:
        session = db.get_session(sid)  # exact: the app sends a full stored key, never a prefix (D3)
        if session is None:
            return json.dumps({"error": NOT_SAVED})
        folder = Path(os.environ["TMPDIR"]) / "exports"
        folder.mkdir(parents=True, exist_ok=True)
        for old in folder.iterdir():
            old.unlink()
        target = folder / file_name(sid, session.get("title"))
        part = target.with_name(target.name + ".part")
        try:
            with part.open("w", encoding="utf-8") as out:
                out.write(_compact(session)[:-1] + ',"messages":[')
                last_id, first = 0, True
                while last_id is not None:
                    messages = db.get_messages(sid, limit=PAGE, after_id=last_id, include_inactive=True)
                    for message in messages:
                        out.write(("" if first else ",") + _compact(message))
                        first = False
                    last_id = messages[-1].get("id") if len(messages) == PAGE else None
                out.write("]}")
            os.replace(part, target)
        finally:
            part.unlink(missing_ok=True)
    finally:
        hermes_state_registry.release_or_close(db)
    return json.dumps({"path": str(target), "name": target.name, "bytes": target.stat().st_size})
