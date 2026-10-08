#!/usr/bin/env python3
"""The plugin's change notes: the section of CHANGELOG.md for the version gradle.properties names, as HTML.

It is `lib/main.py notes <version> --html`, with the version read from the line release-flow/prepare wrote.
It needs the packages in lib/requirements.txt.
Run in the root of the plugin's repository.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "lib"))
import main  # noqa: E402  (lib/main.py, found through the line above)

if __name__ == "__main__":
    sys.argv = [sys.argv[0], "notes", main.declared_version("gradle.properties"), "--html"]
    sys.exit(main.main())
