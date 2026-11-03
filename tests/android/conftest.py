import sys
from pathlib import Path

import pytest

from tests.android.fake_endpoint import FakeEndpoint

# The app's Python half ships beside the core in the runtime, not as an installed package.
ANDROID_PYTHON = Path(__file__).resolve().parents[2] / "apps" / "android" / "python"
if str(ANDROID_PYTHON) not in sys.path:
    sys.path.insert(0, str(ANDROID_PYTHON))


@pytest.fixture
def servers():
    """Starts fake OpenAI-compatible servers (``FakeEndpoint`` arguments) and closes them after the test."""
    made = []

    def make(**kw):
        made.append(FakeEndpoint(**kw))
        return made[-1]

    yield make
    for s in made:
        s.close()
