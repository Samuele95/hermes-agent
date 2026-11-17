"""The toolsets whose keys are missing, and the toolsets the user can switch (row capabilities-10 with protocol-35,
batch-4 F2, DC-P25; D26, P14 D2).

``toolsets.list`` (the core's method) carries no configured flag, so this reproduces the one
``GET /api/tools/toolsets`` (``hermes_cli/web_routers/tools.py``) answers: the subscription features read once,
then ``hermes_cli.tools_config._toolset_has_keys`` for every ``_get_effective_configurable_toolsets()`` name, in
that order. The core checks presence only; no key's value is read back or answered.

``switchable`` is the second field (P14 D2): ``toolsets.list`` lists every registry toolset (~60, platform
composites included, which ``tools.configure`` answers ``unknown``) and carries no GUI label. These are the toolsets
``tools.configure`` switches for the CLI platform, as the ``hermes tools`` checklist (``_checklist_toolset_keys``)
and ``GET /api/tools/toolsets`` list them: configurable, allowed for ``cli``, not config-only, in the core's order,
each with ``gui_toolset_label`` of its title. Names and labels only. The core is importable only after
``prepare()``, so its imports sit inside the function.
"""

from __future__ import annotations

import json


def keys(_arg: str) -> str:
    """``{"needs_keys", "switchable"}``: the configurable toolsets whose required keys are missing (names only), and
    the ones the user can switch on the phone with their GUI labels."""
    from hermes_cli.config import load_config
    from hermes_cli.tools_config import (
        _CONFIG_ONLY_TOOLSETS, _get_effective_configurable_toolsets, _toolset_allowed_for_platform, _toolset_has_keys,
        get_nous_subscription_features, gui_toolset_label)

    config = load_config()
    features = get_nous_subscription_features(config)
    rows = _get_effective_configurable_toolsets()
    return json.dumps({
        "needs_keys": [name for name, _label, _description in rows
                       if not _toolset_has_keys(name, config, features=features)],
        "switchable": [{"name": name, "label": gui_toolset_label(label)} for name, label, _description in rows
                       if _toolset_allowed_for_platform(name, "cli") and name not in _CONFIG_ONLY_TOOLSETS]})
