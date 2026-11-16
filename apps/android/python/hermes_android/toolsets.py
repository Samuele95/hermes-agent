"""The toolsets whose keys are missing (row capabilities-10 with protocol-35, batch-4 F2, DC-P25; D26).

``toolsets.list`` (the core's method) carries no configured flag, so this reproduces the one
``GET /api/tools/toolsets`` (``hermes_cli/web_routers/tools.py``) answers: the subscription features read once,
then ``hermes_cli.tools_config._toolset_has_keys`` for every ``_get_effective_configurable_toolsets()`` name, in
that order. The core checks presence only; no key's value is read back or answered. The core is importable only
after ``prepare()``, so its imports sit inside the function.
"""

from __future__ import annotations

import json


def keys(_arg: str) -> str:
    """``{"needs_keys"}``: the configurable toolsets whose required keys are missing, names only."""
    from hermes_cli.config import load_config
    from hermes_cli.tools_config import (
        _get_effective_configurable_toolsets, _toolset_has_keys, get_nous_subscription_features)

    config = load_config()
    features = get_nous_subscription_features(config)
    return json.dumps({"needs_keys": [name for name, _label, _description in _get_effective_configurable_toolsets()
                                      if not _toolset_has_keys(name, config, features=features)]})
