import sys
from pathlib import Path

# The app's Python half ships beside the core in the runtime, not as an installed package.
ANDROID_PYTHON = Path(__file__).resolve().parents[2] / "apps" / "android" / "python"
if str(ANDROID_PYTHON) not in sys.path:
    sys.path.insert(0, str(ANDROID_PYTHON))
