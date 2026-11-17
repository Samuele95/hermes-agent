"""Back up and restore exactly five parts of Hermes data, never a key or a sign-in (row settings-24; batch-4 F3, F4,
F15; D49 "No secrets", D8; D26).

The core's ``hermes backup`` / ``hermes import`` zip and restore the whole home, ``.env``, ``auth.json`` and the
vault included (``hermes_cli/backup.py``: the vault "IS included in backups"), and are CLI-shaped, so the app has its
own pair over the core's helpers. One pure, total rule, :func:`part_of`, is the closed allow-list both directions
apply: ``state.db`` is sessions, ``config.yaml`` config, ``memories/`` memories, ``skills/`` skills (with the archive,
the hub lock and the usage file), ``cron/`` jobs without its lock and pid files; everything else is no part, so
``.env``, ``auth.json``, ``vault.*``, ``profiles/``, ``_external/``, ``mcp-tokens/`` and runtime files are outside by
construction, and a secret basename or ``mcp-tokens/`` inside a part is dropped too (D5). ``config.yaml`` crosses with
every key field blanked by :func:`blank_keys` (with every value of an MCP server's ``env`` map and of
``terminal.docker_env``, and every header that is not only ``${VAR}`` references), on the way out and on the way in,
so after a restore on a new phone the user signs in and re-enters keys.

``create`` writes ``TMPDIR/exports/hermes-backup-<UTC>.zip`` (replacing only the previous backup; a session export
stays) through the core's ``_write_zip_entries``, so every ``*.db`` is a SQLite backup-API snapshot with no WAL or SHM.
``restore`` refuses in words, before its first write, while a turn runs in this process or the always-on gateway holds
its runtime lock (D1; the app's ``BackupControl`` stops the bot first, P15), when the file is no zip, no Hermes backup
or holds nothing to restore, or when its settings or its session database cannot be read. Then, under the core's one
backup slot, ``config.yaml`` goes through the app's config writer (E5), every ``*.db`` is written page by page into
the open file (``_import_db_member``: the inode is kept, open handles see the restored rows) and every other member is
published atomically. Members the archive lacks are not deleted (the core's import semantics). A picked copy in
``TMPDIR/imports/`` is deleted afterwards. The core is importable only after ``prepare()``, so its imports sit inside
the functions; the core's helpers are private and may move at an upstream sync (F3). Nothing of the data is logged.

``inspect``, the third call (P15 D1), answers what a picked archive would restore (its parts, the members a restore
skips, the sessions its database holds) so the app's confirm can show it before anything is replaced. It runs the
restore's own classification, ``_classify``, so :func:`part_of` stays the one rule and no Kotlin copy of it can drift;
it writes nothing, takes no backup slot, is never refused for a running turn or gateway, and never deletes the file.
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import zipfile
from datetime import UTC, datetime
from pathlib import Path

PARTS = ("sessions", "memories", "skills", "config", "jobs")  # the contract's BackupPart, in its order
SECRET_NAMES = frozenset({".env", "auth.json", "vault.key", "vault.json.enc"})
PREFIX = "hermes-backup-"  # the core's _RUN_BACKUP_PREFIX
CONFIG = "config.yaml"

BUSY = "Hermes is still answering. Stop the reply or let it finish, then restore the backup."
GATEWAY = "The always-on gateway is running. Turn it off, then restore the backup."
NOT_ZIP = "This file is not a zip archive."
NOTHING = "This backup holds no sessions, memories, skills, settings or jobs."
BAD_CONFIG = "The backup's settings (config.yaml) could not be read."
BAD_SESSIONS = "The backup's session database could not be read."

_KEY_NAMES = frozenset({"key", "api_key", "token", "secret", "password", "password_hash", "session_key"})
_KEY_SUFFIXES = ("_api_key", "_token", "_secret", "_password")
# Maps of HTTP headers (a remote MCP server's, a provider's): any header may carry a credential, so every value but
# one that is only ${VAR} references is blanked; the header names stay.
_HEADER_MAPS = frozenset({"headers", "extra_headers", "default_headers"})


class _Unwritable(Exception):
    """A member that could not go into the archive; the archive is dropped."""


def part_of(rel: str) -> str | None:
    """The part an archive-relative path belongs to, or None: the one rule both directions use (pure, total)."""
    from hermes_cli.archive_safe import normalize_archive_parts
    from hermes_cli.backup import _import_skipped, _should_exclude

    try:
        parts = tuple(normalize_archive_parts(rel))
    except ValueError:
        return None
    part = _root_part(parts)
    if part is None or _should_exclude(Path(*parts)) or _import_skipped(rel):
        return None
    if "mcp-tokens" in parts or parts[-1] in SECRET_NAMES:
        return None
    return part


def _root_part(parts: tuple[str, ...]) -> str | None:
    if parts == ("state.db",):
        return "sessions"
    if parts == ("config.yaml",):
        return "config"
    if len(parts) >= 2 and parts[0] in ("memories", "skills"):
        return parts[0]
    if len(parts) >= 2 and parts[0] == "cron" and not parts[-1].endswith((".lock", ".pid")):
        return "jobs"
    return None


def members(root: Path) -> list[tuple[Path, str]]:
    """``(file, part)`` for every regular file of the five roots under ``root`` that :func:`part_of` keeps, sorted
    by relative path; links and non-regular files are skipped as the core's backup walk skips them."""
    from hermes_cli.backup import _is_link_path, _is_non_regular_path

    found = [root / name for name in ("state.db", "config.yaml")]
    for top in ("memories", "skills", "cron"):
        base = root / top
        if not base.is_dir() or _is_link_path(base):
            continue
        for dirpath, dirnames, filenames in os.walk(base, followlinks=False):
            dirnames[:] = [d for d in dirnames if not _is_link_path(Path(dirpath) / d)]
            found.extend(Path(dirpath) / name for name in filenames)
    kept = []
    for path in found:
        if not path.exists() or _is_non_regular_path(path):
            continue
        rel = path.relative_to(root).as_posix()
        if (part := part_of(rel)) is not None:
            kept.append((rel, path, part))
    return [(path, part) for _rel, path, part in sorted(kept)]


def _is_key_field(name: object) -> bool:
    lower = str(name).lower()
    return lower in _KEY_NAMES or lower.endswith(_KEY_SUFFIXES)


def _is_env_map(path: tuple, name: object) -> bool:
    """An env map of literal values: ``mcp_servers.<n>.env`` (``hermes mcp add --env K=V`` stores the value) and
    ``terminal.docker_env`` (exact pairs set in the sandbox)."""
    return (name == "env" and len(path) == 2 and path[0] == "mcp_servers") or (path, name) == (("terminal",),
                                                                                                  "docker_env")


def _blank_env(value):
    return {k: "" for k in value} if isinstance(value, dict) else "" if value else value


def _blank_headers(value):
    from hermes_cli.mcp_config import _header_value_is_only_env_refs

    if not isinstance(value, dict):
        return "" if value else value
    return {k: v if isinstance(v, str) and _header_value_is_only_env_refs(v) else "" for k, v in value.items()}


def blank_keys(value, _path: tuple = ()):
    """A deep copy of a YAML value where every key field's value, whatever its shape, is ``""``, as is every value
    of an MCP server's ``env`` map and every header of a header map that is not only ``${VAR}`` references (pure).
    Names of a slot or a count (``api_key_env``, ``key_env``, ``*_tokens``, ``min_secret_chars``) are not key
    fields."""
    if isinstance(value, dict):
        return {k: _blank_field(_path, k, v) for k, v in value.items()}
    if isinstance(value, list):
        return [blank_keys(item, _path + (i,)) for i, item in enumerate(value)]
    return value


def _blank_field(path: tuple, name, value):
    if _is_key_field(name):
        return ""
    if str(name).lower() in _HEADER_MAPS:
        return _blank_headers(value)
    if _is_env_map(path, name):
        return _blank_env(value)
    return blank_keys(value, path + (name,))


def create(_arg: str) -> str:
    """Writes the backup zip and answers ``{"path", "name", "bytes", "parts"}``; a failure leaves nothing behind."""
    from hermes_cli.backup import BackupInProgressError, _backup_operation_lock
    from hermes_constants import get_hermes_home

    home = get_hermes_home()
    try:
        with _backup_operation_lock(home):
            return json.dumps(_create(home))
    except BackupInProgressError as exc:
        return json.dumps({"error": str(exc)})


def _create(home: Path) -> dict:
    import hermes_yaml
    from hermes_cli.backup import _write_zip_entries
    from hermes_cli.config import read_user_config_raw

    folder = Path(os.environ["TMPDIR"]) / "exports"
    folder.mkdir(parents=True, exist_ok=True)
    for old in folder.glob(f"{PREFIX}*.zip*"):
        if old.name.endswith((".zip", ".zip.part")):
            old.unlink()
    target = folder / f"{PREFIX}{datetime.now(UTC).strftime('%Y-%m-%d-%H%M%S')}.zip"
    part = target.with_name(target.name + ".part")
    kept = members(home)

    def unreadable_db(rel: Path) -> None:
        raise _Unwritable(f"{rel} could not be copied")

    def unreadable(rel: Path, exc: Exception) -> None:
        raise _Unwritable(f"{rel}: {exc}")

    files = [(path, path.relative_to(home)) for path, _part in kept]
    try:
        with zipfile.ZipFile(part, "w", zipfile.ZIP_DEFLATED) as zf:
            if any(rel.as_posix() == CONFIG for _path, rel in files):  # never the merged load_config()
                zf.writestr(CONFIG, hermes_yaml.safe_dump(
                    blank_keys(read_user_config_raw()), sort_keys=False, width=hermes_yaml.ROUNDTRIP_YAML_WIDTH))
            _write_zip_entries(zf, [(path, rel) for path, rel in files if rel.as_posix() != CONFIG], part,
                               on_db_failure=unreadable_db, on_error=unreadable, on_progress=lambda _i: None,
                               track_bytes=False)
        os.replace(part, target)
    except (_Unwritable, OSError, ValueError, hermes_yaml.YAMLError) as exc:
        return {"error": f"The backup could not be written: {exc}"}
    finally:
        part.unlink(missing_ok=True)
    held = {p for _path, p in kept}
    return {"path": str(target), "name": target.name, "bytes": target.stat().st_size,
            "parts": [p for p in PARTS if p in held]}


def restore(arg: str) -> str:
    """Restores the five parts of ``{"path"}``'s zip and answers ``{"restored", "skipped", "sessions"}``, or a
    refusal in words with nothing written. A path inside ``TMPDIR/imports/`` is the app's picked copy: deleted."""
    path = Path(json.loads(arg)["path"])
    try:
        return json.dumps(_restore(path))
    finally:
        imports = (Path(os.environ["TMPDIR"]) / "imports").resolve()
        if path.resolve().is_relative_to(imports):
            path.unlink(missing_ok=True)


def _turn_running() -> bool:
    server = sys.modules.get("tui_gateway.server")
    if server is None:  # never imported (the :gateway role, a bare host): no turn can run in this process
        return False
    with server._sessions_lock:
        return any(s.get("running") for s in server._sessions.values())


def _restore(path: Path) -> dict:
    from gateway.status import is_gateway_runtime_lock_active

    if _turn_running():
        return {"error": BUSY}
    if is_gateway_runtime_lock_active():
        return {"error": GATEWAY}
    try:
        zf = zipfile.ZipFile(path)
    except (zipfile.BadZipFile, OSError):
        return {"error": NOT_ZIP}
    with zf:
        return _restore_from(zf)


def _restore_from(zf: zipfile.ZipFile) -> dict:
    c = _classify(zf)
    if "error" in c:
        return c
    written = _write(zf, c["mapped"], c["archived"])
    if isinstance(written, str):
        return {"error": written}
    return {"restored": [p for p in PARTS if p in written], "skipped": c["skipped"], "sessions": c["sessions"]}


def inspect(arg: str) -> str:
    """What ``{"path"}``'s zip would restore, writing nothing: ``{"parts", "skipped", "sessions"}``, or the refusal
    :func:`restore` gives the same file. No backup slot, no turn or gateway check, and the file is never deleted."""
    path = Path(json.loads(arg)["path"])
    try:
        zf = zipfile.ZipFile(path)
    except (zipfile.BadZipFile, OSError):
        return json.dumps({"error": NOT_ZIP})
    with zf:
        c = _classify(zf)
    if "error" in c:
        return json.dumps(c)
    held = {part for *_, part in c["mapped"]}
    return json.dumps({"parts": [p for p in PARTS if p in held], "skipped": c["skipped"], "sessions": c["sessions"]})


def _classify(zf: zipfile.ZipFile) -> dict:
    """The archive's members a restore takes, by :func:`part_of`, with every refusal a restore gives before its first
    write: ``{"error"}``, or ``{"mapped", "skipped", "archived", "sessions"}``."""
    from hermes_cli.archive_safe import normalize_archive_parts
    from hermes_cli.backup_restore import _detect_prefix, _validate_backup_zip

    ok, reason = _validate_backup_zip(zf)
    if not ok:
        return {"error": reason}
    prefix = _detect_prefix(zf)
    mapped, skipped = [], 0
    for info in zf.infolist():
        if info.is_dir():
            continue
        rel = info.filename[len(prefix):]
        part = part_of(rel)
        if part is None:
            skipped += 1
        else:
            mapped.append((info.filename, "/".join(normalize_archive_parts(rel)), part))
    if not mapped:
        return {"error": NOTHING}
    config = next((member for member, rel, _part in mapped if rel == CONFIG), None)
    archived = _archived_config(zf, config) if config else {}
    if archived is None:
        return {"error": BAD_CONFIG}
    database = next((member for member, _rel, part in mapped if part == "sessions"), None)
    sessions = _archived_sessions(zf, database) if database else 0
    if sessions is None:
        return {"error": BAD_SESSIONS}
    return {"mapped": mapped, "skipped": skipped, "archived": archived, "sessions": sessions}


def _archived_config(zf: zipfile.ZipFile, member: str) -> dict | None:
    import hermes_yaml

    try:
        value = hermes_yaml.safe_load(zf.read(member))
    except (hermes_yaml.YAMLError, UnicodeDecodeError, OSError, zipfile.BadZipFile, ValueError):
        return None
    return value if isinstance(value, dict) else None


def _archived_sessions(zf: zipfile.ZipFile, member: str) -> int | None:
    """The archived database's session count, read from a temporary copy that is removed again; None when it is
    no session store."""
    from hermes_cli.backup_restore import _count_session_rows

    folder = Path(os.environ["TMPDIR"]) / "imports"
    folder.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(dir=folder, prefix=".restore-", suffix=".db")
    copy = Path(name)
    try:
        with os.fdopen(fd, "wb") as out, zf.open(member) as src:
            while chunk := src.read(1 << 20):
                out.write(chunk)
        counted = _count_session_rows(copy)
    except (OSError, zipfile.BadZipFile, ValueError):
        counted = None
    finally:
        for leftover in (copy, *(copy.with_name(copy.name + s) for s in ("-wal", "-shm", "-journal"))):
            leftover.unlink(missing_ok=True)
    return None if counted is None else counted[0]


def _write(zf: zipfile.ZipFile, mapped: list[tuple[str, str, str]], archived: dict) -> set[str] | str:
    """Every mapped member into the home under the core's backup slot; the parts written, or the words of a
    failure (members written before it stay, each one atomic)."""
    import hermes_yaml
    from hermes_android import _config
    from hermes_cli import backup_restore
    from hermes_cli.backup import BackupInProgressError, _backup_operation_lock
    from hermes_constants import get_hermes_home

    home = get_hermes_home()
    mode = backup_restore._default_new_file_mode()
    written: set[str] = set()
    try:
        with _backup_operation_lock(home):
            for member, rel, part in mapped:
                if rel == CONFIG:
                    blanked = blank_keys(archived)
                    _config.mutate(lambda cfg: (cfg.clear(), cfg.update(blanked)))
                else:
                    target = home.joinpath(*rel.split("/"))
                    target.parent.mkdir(parents=True, exist_ok=True)
                    publish = (backup_restore._import_db_member if target.suffix == ".db"
                               else backup_restore._extract_member_atomically)
                    publish(zf, member, target, mode)
                written.add(part)
    except BackupInProgressError as exc:
        return str(exc)
    except (OSError, zipfile.BadZipFile, hermes_yaml.YAMLError) as exc:  # this home's own config.yaml unreadable
        return f"The backup could not be restored: {exc}"
    return written
