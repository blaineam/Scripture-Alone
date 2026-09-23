#!/usr/bin/env python3
"""check-16kb-pages.py — fail when any native library in an AAB/APK is not 16 KB page aligned.

    scripts/check-16kb-pages.py android/app/build/outputs/bundle/release/app-release.aab [more.aab …]

Google Play refuses updates for apps targeting Android 15+ whose packaged `.so` files have ELF LOAD
segments aligned to less than 16 KB (0x4000). Release notes and dependency changelogs are not proof;
this reads every `lib/**/*.so` inside the built bundle and checks each PT_LOAD segment's p_align.
No NDK needed — the ELF program headers are parsed directly.

Only 64-bit ABIs (arm64-v8a, x86_64) must be aligned: 16 KB-page devices run 64-bit only, and
Play's check ignores 32-bit libraries. A 4 KB 32-bit library (ML Kit's armeabi-v7a/x86 OCR
pipeline, today) is printed as a note, never a failure.

Exit 0 = every library aligned (or none shipped); 1 = at least one 4 KB library; 2 = bad input.
"""
import struct
import sys
import zipfile

PT_LOAD = 1
MIN_ALIGN = 0x4000


def load_aligns(data: bytes):
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    is64 = data[4] == 2
    end = "<" if data[5] == 1 else ">"
    if is64:
        phoff, = struct.unpack_from(end + "Q", data, 0x20)
        phentsize, phnum = struct.unpack_from(end + "HH", data, 0x36)
    else:
        phoff, = struct.unpack_from(end + "I", data, 0x1C)
        phentsize, phnum = struct.unpack_from(end + "HH", data, 0x2A)
    out = []
    for i in range(phnum):
        off = phoff + i * phentsize
        p_type, = struct.unpack_from(end + "I", data, off)
        if p_type != PT_LOAD:
            continue
        if is64:
            p_align, = struct.unpack_from(end + "Q", data, off + 0x30)
        else:
            p_align, = struct.unpack_from(end + "I", data, off + 0x1C)
        out.append(p_align)
    return out


def main(paths):
    if not paths:
        print(__doc__)
        return 2
    bad, notes, seen = [], [], 0
    for path in paths:
        try:
            z = zipfile.ZipFile(path)
        except (OSError, zipfile.BadZipFile) as e:
            print(f"✗ {path}: {e}")
            return 2
        for name in z.namelist():
            if not name.endswith(".so") or "/lib/" not in f"/{name}":
                continue
            seen += 1
            try:
                aligns = load_aligns(z.read(name))
            except (ValueError, struct.error) as e:
                bad.append(f"{path}!{name}: unreadable ELF ({e})")
                continue
            low = [a for a in aligns if a < MIN_ALIGN]
            if low:
                is64 = "/arm64-v8a/" in name or "/x86_64/" in name
                (bad if is64 else notes).append(f"{path}!{name}: LOAD align {', '.join(hex(a) for a in low)} < 0x4000")
    for line in notes:
        print(f"· {line} (32-bit — not subject to the 16 KB rule)")
    for line in bad:
        print(f"✗ {line}")
    if bad:
        print(f"{len(bad)} of {seen} native librar{'y' if seen == 1 else 'ies'} not 16 KB aligned — Play will refuse this bundle.")
        return 1
    print(f"✓ {seen} native librar{'y' if seen == 1 else 'ies'} checked; every 64-bit one is 16 KB aligned.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
