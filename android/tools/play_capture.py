#!/usr/bin/env python3
"""Google Play listing screenshots: drives the debug build on an emulator, one locale at a time.

    python3 android/tools/play_capture.py <serial> <phone|tablet-7in|tablet-10in> <out_dir> [locale ...]
    python3 android/tools/play_capture.py <serial> wear <out_dir> [locale ...]

<out_dir>/<Play locale>/<form factor>/NN-<scene>.png, raw (no frame, no caption), as the listing has
always been. Locales are the Play listing's (en-US de-DE es-ES fr-FR it-IT ja-JP ko-KR pt-BR zh-CN);
none given means all nine. Each locale gets:

  * the app in that language (a per-app locale: `cmd locale set-app-locales`), read in that locale's
    own Bible (BSB for English — the listing's English Bible — then LUT1912, RVR1909, LSG, RIV1927,
    BUNGO, KRV, BLIVRE, CUVS, which the debug APK carries locally);
  * the invented demo library of play_seed.py in that language (never a real reader's data), with
    that locale's sermon slide (docs/appstore-screenshots/sample-slide.<locale>.jpg) on the note;
  * a clean status bar (System UI demo mode, 9:41, full Wi-Fi and battery, no notifications).

The emulator must be running the right size already (see android/play-assets/README.md): the phone
AVD at `wm size 1080x2160`, the tablet AVD native (2560x1600) for 10-inch and `wm size 1200x1920`
for 7-inch. adb must be root (`adb root`, a google_apis image) for the phone and tablets, so the
seed database can be put in place; the Wear image isn't rootable, so the watch is seeded through
`run-as` (a debug build).

Taps are coordinates, measured on the English UI for each profile: every locale's layout is the
same, only its words differ. Check a sample of every locale's set after a run anyway.
"""
import datetime
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
PKG = "com.blainemiller.scripturealone"

# Play locale -> (app locale, play_seed language, Bible, sample-slide suffix)
LOCALES = {
    "en-US": ("en-US", "en", "BSB", ""),
    "de-DE": ("de-DE", "de", "LUT1912", ".de-DE"),
    "es-ES": ("es-ES", "es", "RVR1909", ".es-ES"),
    "fr-FR": ("fr-FR", "fr", "LSG", ".fr-FR"),
    "it-IT": ("it-IT", "it", "RIV1927", ".it"),
    "ja-JP": ("ja-JP", "ja", "BUNGO", ".ja"),
    "ko-KR": ("ko-KR", "ko", "KRV", ".ko"),
    "pt-BR": ("pt-BR", "pt-BR", "BLIVRE", ".pt-BR"),
    "zh-CN": ("zh-CN", "zh-Hans", "CUVS", ".zh-Hans"),
}


class Device:
    def __init__(self, serial: str):
        self.serial = serial

    def adb(self, *args: str, check: bool = True, capture: bool = True) -> str:
        # A loaded host can leave the emulator's system services briefly unreachable: retry.
        for attempt in range(6):
            try:
                r = subprocess.run(["adb", "-s", self.serial, *args], capture_output=capture, text=True, timeout=120)
            except subprocess.TimeoutExpired:
                raise RuntimeError(f"adb {' '.join(args)} hung: is the emulator frozen? (run it with -no-window)")
            if r.returncode == 0 or not check:
                return (r.stdout or "").strip()
            time.sleep(5 * (attempt + 1))
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr or r.stdout}")

    def sh(self, cmd: str, check: bool = True) -> str:
        return self.adb("shell", cmd, check=check)

    def frame(self) -> bytes:
        return subprocess.run(["adb", "-s", self.serial, "exec-out", "screencap"], capture_output=True).stdout

    def focus_ok(self) -> bool:
        """Whether the app's own window has focus: no "isn't responding" dialog, chooser or launcher."""
        focus = self.sh("dumpsys window | grep mCurrentFocus", check=False)
        return not any(bad in focus for bad in ("Not Responding", "launcher", "Launcher", "ChooserActivity", "intentresolver"))

    def settle(self, timeout: float = 15.0) -> None:
        """Waits until two screens a moment apart are identical: launches and sheets have finished."""
        import hashlib
        end = time.time() + timeout
        last = None
        while time.time() < end:
            h = hashlib.md5(self.frame()).digest()
            if h == last:
                return
            last = h
            time.sleep(2.0)

    def tap(self, x: int, y: int, wait: float = 1.2) -> None:
        self.sh(f"input tap {x} {y}")
        time.sleep(wait)
        self.settle()

    def swipe(self, x1: int, y1: int, x2: int, y2: int, ms: int = 400, wait: float = 1.2) -> None:
        self.sh(f"input swipe {x1} {y1} {x2} {y2} {ms}")
        time.sleep(wait)

    def tap_text(self, text: str, fallback: tuple[int, int] | None, wait: float = 1.2) -> None:
        """Taps the on-screen element showing exactly [text] (a uiautomator dump), else [fallback], if any."""
        import re
        import html
        for _ in range(4):
            self.sh("uiautomator dump /sdcard/ui.xml", check=False)
            dump = self.adb("exec-out", "cat", "/sdcard/ui.xml", check=False)
            for node in re.findall(r"<node [^>]*>", dump):
                t = re.search(r' text="([^"]*)"', node)
                if t and html.unescape(t.group(1)) == text:
                    b = [int(v) for v in re.findall(r"\d+", re.search(r'bounds="([^"]*)"', node).group(1))]
                    self.tap((b[0] + b[2]) // 2, (b[1] + b[3]) // 2, wait)
                    return
            time.sleep(2)
        print(f"   ({text!r} not found; tapping {fallback})")
        if fallback:
            self.tap(*fallback, wait=wait)

    def back(self, wait: float = 1.0) -> None:
        self.sh("input keyevent BACK")
        time.sleep(wait)

    def screencap(self, path: str) -> None:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        data = subprocess.run(["adb", "-s", self.serial, "exec-out", "screencap", "-p"], capture_output=True).stdout
        with open(path, "wb") as f:
            f.write(data)
        # Flatten to RGB (Play refuses alpha).
        from PIL import Image
        Image.open(path).convert("RGB").save(path, optimize=True)
        print("  ", os.path.relpath(path))

    def demo_status_bar(self) -> None:
        self.sh("settings put global sysui_demo_allowed 1")
        # Exit first: entering over a stale demo state can leave a second Wi-Fi icon.
        for c in ["exit", "enter", "clock -e hhmm 0941", "battery -e level 100 -e plugged false",
                  "network -e wifi show -e level 4 -e fully true", "network -e mobile hide",
                  "notifications -e visible false"]:
            self.sh(f"am broadcast -a com.android.systemui.demo -e command {c}")
            time.sleep(0.5)


# ---- Phone and tablets ----

def open_link(d: Device, url: str, wait: float = 4.0) -> None:
    d.sh(f"am force-stop {PKG}")
    d.sh(f"am start -a android.intent.action.VIEW -d '{url}' {PKG}")
    time.sleep(wait + 3)
    d.settle(25)


def prepare_phone(d: Device, locale: str, work: str) -> None:
    app_locale, seed_lang, _, slide_suffix = LOCALES[locale]
    d.sh(f"am force-stop {PKG}")
    d.sh(f"cmd locale set-app-locales {PKG} --locales {app_locale}")
    files = f"/data/data/{PKG}/files"
    # A first launch makes the app's directories (once: no `pm clear` between locales, which makes
    # the emulator redo a first launch's work each time); then the demo library replaces the store.
    if d.sh(f"ls {files}/userdata.sqlite", check=False) != f"{files}/userdata.sqlite":
        d.sh(f"am start -n {PKG}/.MainActivity")
        for _ in range(120):
            time.sleep(5)
            if d.sh(f"ls {files}/userdata.sqlite", check=False) == f"{files}/userdata.sqlite":
                break
        time.sleep(5)
    d.sh(f"am force-stop {PKG}")
    # Forget the last locale's reading position, translation, recents and remembered tabs.
    d.sh(f"rm -rf {files}/datastore /data/data/{PKG}/shared_prefs/study.xml /data/data/{PKG}/shared_prefs/rating.xml")
    seed = os.path.join(work, f"seed-{seed_lang}.sqlite")
    slide = os.path.join(ROOT, "docs", "appstore-screenshots", f"sample-slide{slide_suffix}.jpg")
    subprocess.run([sys.executable, os.path.join(HERE, "play_seed.py"), seed, slide, "--locale", seed_lang],
                   check=True, capture_output=True)
    owner = d.sh(f"stat -c %U /data/data/{PKG}")
    d.sh(f"rm -f {files}/userdata.sqlite-wal {files}/userdata.sqlite-shm")
    d.adb("push", seed, f"{files}/userdata.sqlite")
    d.sh(f"chown {owner}:{owner} {files}/userdata.sqlite && chmod 600 {files}/userdata.sqlite")
    d.demo_status_bar()


RES = os.path.join(HERE, "..", "app", "src", "main", "res")
RES_DIRS = {"en-US": "values", "de-DE": "values-de", "es-ES": "values-es", "fr-FR": "values-fr", "it-IT": "values-it",
            "ja-JP": "values-ja", "ko-KR": "values-ko", "pt-BR": "values-b+pt+BR", "zh-CN": "values-zh-rCN"}


def res_string(locale: str, name: str) -> str:
    """The app's own string [name] in [locale], as it appears on screen."""
    import re
    import html
    xml = open(os.path.join(RES, RES_DIRS[locale], "strings.xml"), encoding="utf-8").read()
    m = re.search(rf'<string name="{name}"[^>]*>(.*?)</string>', xml, re.S)
    return html.unescape(m.group(1)).replace("\\'", "'").replace('\\"', '"') if m else ""


def link(ref: str, bible: str) -> str:
    return f"scripturealone://open?ref={ref}&translation={bible}"


# Coordinates per profile, measured on the English UI.
PROFILES = {
    "phone": dict(
        notes=(121, 220), study=(247, 220), goto=(480, 220),
        see_all=(1000, 808), first_topic=(500, 660),
        study_tab_context=(913, 1503), study_tab_refs=(167, 1503), study_tab_commentary=(415, 1503),
        study_expand=((540, 1210), (540, 300)),
        notes_tab_highlights=(540, 508), first_note=(500, 800), note_scroll=((540, 1700), (540, 925)),
        share=(951, 1834), share_image=(790, 1324), share_template_dawn=(461, 1480),
    ),
}

PROFILES["tablet-10in"] = dict(
    notes=(92, 124), study=(188, 124), goto=(366, 124),
    see_all=(1745, 572), first_topic=(500, 480),
    study_tab_refs=(1920, 266), study_tab_context=(2440, 266), context_map=(2093, 366),
    notes_tab_highlights=(900, 344), first_note=(700, 600), note_scroll=((900, 1400), (900, 700)),
    share=(1721, 1336), share_image=(1780, 900), share_template_dawn=(0, 0),
)

PROFILES["tablet-7in"] = dict(
    notes=(92, 124), study=(188, 124), goto=(366, 124),
    see_all=(1140, 568), first_topic=(500, 480),
    study_tab_context=(1026, 1296), study_tab_refs=(175, 1296),
    study_expand=((600, 1075), (600, 250)),
    notes_tab_highlights=(600, 344), first_note=(600, 600), note_scroll=((600, 1600), (600, 880)),
    share=(1041, 1656), share_image=(1100, 1300), share_template_dawn=(0, 0),
)

TABLET7_SCENES = ["reader", "topics", "study-context", "highlights", "topic", "sermon-slide", "study-references",
                  "share-image"]

TABLET10_SCENES = ["study-references", "topics", "highlights", "study-context", "topic", "journeys-map",
                   "notes", "share-image"]


def tablet_scene(d: Device, p: dict, scene: str, bible: str, locale: str) -> None:
    """The 10-inch tablet: Study sits beside the page, so most scenes keep it open."""
    def study(tab: str) -> None:
        d.tap(*p["study"], wait=2)
        d.tap(*p[tab], wait=2)
    if scene == "study-references":
        open_link(d, link("John.10.11", bible))
        study("study_tab_refs")
    elif scene == "study-context":
        open_link(d, link("John.10.11", bible))
        study("study_tab_context")
    elif scene in ("topics", "topic"):
        open_link(d, link("John.10.11", bible))
        study("study_tab_context")
        d.tap(*p["goto"], wait=2)
        d.tap(*p["see_all"], wait=2)
        if scene == "topic":
            d.tap(*p["first_topic"], wait=2.5)
    elif scene == "highlights":
        open_link(d, link("John.10.11", bible))
        study("study_tab_refs")
        d.tap(*p["notes"], wait=2)
        d.tap(*p["notes_tab_highlights"], wait=2)
    elif scene == "notes":
        open_link(d, link("John.10.11", bible))
        study("study_tab_context")
        d.tap(*p["notes"], wait=2)
    elif scene == "journeys-map":
        open_link(d, link("Acts.13.4", bible))
        study("study_tab_context")
        d.tap(*p["context_map"], wait=10)  # the map flies to the journey: let it land
        d.settle()
    elif scene == "sermon-slide":
        open_link(d, link("John.10.11", bible))
        study("study_tab_refs")
        d.tap(*p["notes"], wait=2)
        d.tap(*p["first_note"], wait=2)
        (a, b) = p["note_scroll"]
        d.swipe(*a, *b, ms=1500, wait=2)
        d.settle()
    elif scene == "share-image":
        open_link(d, link("John.3.16", bible))
        d.tap(*p["share"], wait=1.5)
        d.tap_text(res_string(locale, "reader_share_image"), p["share_image"], wait=3)
        d.tap_text(res_string(locale, "share_template_dawn"), None, wait=2)
    else:
        raise ValueError(scene)


PHONE_SCENES = ["reader", "topics", "topic", "study-context", "highlights", "notes", "sermon-slide", "share-image"]


def phone_scene(d: Device, p: dict, scene: str, bible: str, locale: str) -> None:
    if scene == "reader":
        open_link(d, link("John.10.11", bible))
    elif scene == "topics":
        open_link(d, link("John.10.11", bible))
        d.tap(*p["goto"], wait=2)
        d.tap(*p["see_all"], wait=2)
    elif scene == "topic":
        open_link(d, link("John.10.11", bible))
        d.tap(*p["goto"], wait=2)
        d.tap(*p["see_all"], wait=2)
        d.tap(*p["first_topic"], wait=2.5)
    elif scene == "study-context":
        open_link(d, link("John.10.11", bible))
        d.tap(*p["study"], wait=2)
        d.tap(*p["study_tab_context"], wait=2)
        (a, b) = p["study_expand"]
        d.swipe(*a, *b, ms=500, wait=2.5)
        d.settle()
    elif scene == "highlights":
        open_link(d, link("John.10.11", bible))
        d.tap(*p["notes"], wait=2)
        d.tap(*p["notes_tab_highlights"], wait=2)
    elif scene == "study-references":
        open_link(d, link("John.10.11", bible))
        d.tap(*p["study"], wait=2)
        d.tap(*p["study_tab_refs"], wait=2)
    elif scene == "notes":
        open_link(d, link("John.10.11", bible))
        d.tap(*p["notes"], wait=2)
    elif scene == "sermon-slide":
        open_link(d, link("John.10.11", bible))
        d.tap(*p["notes"], wait=2)
        d.tap(*p["first_note"], wait=2)
        (a, b) = p["note_scroll"]
        d.swipe(*a, *b, ms=1500, wait=2)
        d.settle()
    elif scene == "share-image":
        open_link(d, link("John.3.16", bible))
        d.tap(*p["share"], wait=1.5)
        d.tap_text(res_string(locale, "reader_share_image"), p["share_image"], wait=3)
        if p["share_template_dawn"] != (0, 0):
            d.tap(*p["share_template_dawn"], wait=2)
        else:
            d.tap_text(res_string(locale, "share_template_dawn"), None, wait=2)
    else:
        raise ValueError(scene)


def run_phone(d: Device, profile: str, out: str, locales: list[str], scenes: list[str], work: str) -> None:
    p = PROFILES[profile]
    for locale in locales:
        print(f"[{locale}] {profile}")
        prepare_phone(d, locale, work)
        bible = LOCALES[locale][2]
        for scene in scenes:
            i = SCENES_FOR[profile].index(scene) + 1  # numbered by the full set, so SCENES= redoes one in place
            for attempt in range(3):
                (tablet_scene if profile == "tablet-10in" else phone_scene)(d, p, scene, bible, locale)
                if d.focus_ok():
                    break
                print(f"   {scene}: the app lost focus (an ANR dialog?); again")
                d.sh("input keyevent BACK", check=False)
                d.sh(f"am force-stop {PKG}")
                time.sleep(5)
            d.screencap(os.path.join(out, locale, profile, f"{i:02d}-{scene}.png"))
        d.sh(f"am force-stop {PKG}")
        # The widget snapshot the phone would send its watch: the Wear set is seeded with it.
        d.adb("pull", f"/data/data/{PKG}/files/widgets/VerseSnapshot.json",
              os.path.join(work, f"snapshot-{locale}.json"), check=False)


# ---- Wear OS ----
#
# The watch reads what the phone sends it: the widget snapshot (favorites, highlights, notes, in the
# phone's translation) and, for a locale Bible, its compact watch edition. With one emulator at a
# time there is no phone to pair, so both are put where the Data Layer would leave them: the
# snapshot a phone run pulled (snapshot-<locale>.json in the work directory) and the edition
# Tools/build_companion_data.py --watch-edition <ID> builds (the same format; editions/<ID>-Watch.sqlite
# in the work directory), through `run-as` on the debug build.

WEAR_SCENES = [
    ("today", None),
    ("verse", "ref=43010011-43010015"),
    ("highlights", "route=highlights"),
    ("favorites", "route=favorites"),
    ("notes", "route=notes"),
    ("note", "note"),
]


def prepare_wear(d: Device, locale: str, work: str) -> dict:
    import json
    app_locale, _, bible, _ = LOCALES[locale]
    d.sh(f"am force-stop {PKG}")
    d.sh(f"pm clear {PKG}")
    d.sh(f"cmd locale set-app-locales {PKG} --locales {app_locale}")
    d.sh(f"am start -n {PKG}/.wear.MainActivity")
    time.sleep(8)
    d.sh(f"am force-stop {PKG}")
    snapshot = os.path.join(work, f"snapshot-{locale}.json")
    if not os.path.exists(snapshot):
        raise SystemExit(f"no {snapshot}: run the phone set for {locale} first")
    d.adb("push", snapshot, "/data/local/tmp/VerseSnapshot.json")
    d.sh(f"run-as {PKG} cp /data/local/tmp/VerseSnapshot.json files/VerseSnapshot.json")
    choice = bible if bible != "BSB" else "BSB"
    if bible not in ("ASV", "BSB", "KJV"):
        edition = os.path.join(work, "editions", f"{bible}-Watch.sqlite")
        d.adb("push", edition, f"/data/local/tmp/{bible}-Watch.sqlite")
        d.sh(f"run-as {PKG} mkdir -p files/editions")
        d.sh(f"run-as {PKG} cp /data/local/tmp/{bible}-Watch.sqlite files/editions/{bible}-Watch.sqlite")
    # The translation the watch reads, as the phone's report of it would leave it (WatchBible.Keys).
    prefs = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
             f'    <string name="watch.translation.phone">{choice}</string>\n'
             '    <string name="watch.translation.phoneAt">1790000000.0</string>\n</map>\n')
    local = os.path.join(work, "watch.xml")
    with open(local, "w") as f:
        f.write(prefs)
    d.adb("push", local, "/data/local/tmp/watch.xml")
    d.sh(f"run-as {PKG} mkdir -p shared_prefs")
    d.sh(f"run-as {PKG} cp /data/local/tmp/watch.xml shared_prefs/watch.xml")
    return json.load(open(snapshot))


def run_wear(d: Device, out: str, locales: list[str], work: str) -> None:
    for locale in locales:
        print(f"[{locale}] wear")
        snap = prepare_wear(d, locale, work)
        notes = sorted((it for it in snap.get("items", []) if it.get("kind") == "note"),
                       key=lambda it: it["date"], reverse=True)
        for i, (scene, arg) in enumerate(WEAR_SCENES, 1):
            d.sh(f"am force-stop {PKG}")
            extra = ""
            if arg == "note":
                if not notes:
                    continue
                import urllib.parse
                n = notes[0]
                epoch = int(datetime.datetime.fromisoformat(n["date"].replace("Z", "+00:00")).timestamp())
                note_id = urllib.parse.quote(f"note:{n['range']}:{epoch}", safe="-_.!~*'()")
                extra = f"-e route note/{note_id}"
            elif arg:
                k, v = arg.split("=", 1)
                extra = f"-e {k} {v}"
            # The Wear image has no System UI demo mode: set the clock itself to 9:41 instead.
            d.sh("settings put global auto_time 0")
            d.sh(f"cmd alarm set-time {int(datetime.datetime(2026, 9, 24, 9, 41).timestamp() * 1000)}")
            d.sh(f"am start -n {PKG}/.wear.MainActivity {extra}")
            time.sleep(6)
            path = os.path.join(out, locale, "wear", f"{i:02d}-{scene}.png")
            d.screencap(path)
            # The listing's Wear size, as before (play_graphics.py shots resized the same way).
            from PIL import Image
            Image.open(path).convert("RGB").resize((384, 384), Image.LANCZOS).save(path, optimize=True)
        d.sh(f"am force-stop {PKG}")
        d.sh("settings put global auto_time 1")


def main() -> int:
    serial, profile, out = sys.argv[1], sys.argv[2], sys.argv[3]
    locales = sys.argv[4:] or list(LOCALES)
    scenes_env = os.environ.get("SCENES")
    work = os.environ.get("TMPDIR", "/tmp")
    d = Device(serial)
    if profile == "wear":
        run_wear(d, out, locales, work)
    elif profile in PROFILES:
        scenes = scenes_env.split() if scenes_env else SCENES_FOR[profile]
        run_phone(d, profile, out, locales, scenes, work)
    else:
        raise SystemExit(f"unknown profile {profile}")
    return 0


SCENES_FOR = {"phone": PHONE_SCENES, "tablet-7in": TABLET7_SCENES, "tablet-10in": TABLET10_SCENES}

if __name__ == "__main__":
    sys.exit(main())
