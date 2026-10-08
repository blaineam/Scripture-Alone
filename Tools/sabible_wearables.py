#!/usr/bin/env python3
"""Prints a `.sabible` package's wearables term — `allowed` or `prohibited` — with no key and no
dependencies, for build scripts deciding whether a package may go into a watch app.

    python3 Tools/sabible_wearables.py ScriptureAlone/Resources/Packages/NASB2020.sabible

The term is `policy.wearables` in the package's plaintext, signed header (docs/encrypted-translations.md).
Absent or "allowed" is allowed — every package built before the term existed — and anything else is
prohibited, exactly as the apps read it. The signature is not checked here, and need not be: the watch
apps verify it before they obey the term, so a header edited to say "allowed" is one no watch opens.
Exits 2 when the file is not a package.
"""

import json
import struct
import sys

MAGIC = b"SABIBLE\0"


def wearables_term(policy: dict) -> str:
    """How the apps read the term: absent or "allowed" is allowed; anything else is prohibited."""
    return "allowed" if policy.get("wearables", "allowed") == "allowed" else "prohibited"


def package_policy(path: str) -> dict:
    with open(path, "rb") as handle:
        preamble = handle.read(len(MAGIC) + 6)
        if len(preamble) != len(MAGIC) + 6 or preamble[:len(MAGIC)] != MAGIC:
            raise ValueError(f"{path} is not a translation package")
        (length,) = struct.unpack(">I", preamble[len(MAGIC) + 2:])
        header = handle.read(length)
        if len(header) != length:
            raise ValueError(f"{path} is truncated")
    return json.loads(header)["policy"]


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    try:
        print(wearables_term(package_policy(sys.argv[1])))
    except (OSError, ValueError, KeyError) as error:
        print(error, file=sys.stderr)
        sys.exit(2)
