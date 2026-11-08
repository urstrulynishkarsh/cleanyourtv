#!/usr/bin/env python3
"""
tvctl.py — find, connect, debloat and re-skin an Android / Google TV over Wi-Fi ADB.

Works on Windows, macOS and Linux. Needs Python 3.8+ and `adb` (Android platform-tools)
on PATH, or set ADB=/path/to/adb.

Quick start:
    python scripts/tvctl.py connect            # scan Wi-Fi, connect, authorize on TV
    python scripts/tvctl.py packages           # save packages.txt (send this for a tailored list)
    python scripts/tvctl.py install app.apk    # install the CleanYourTV launcher
    python scripts/tvctl.py home               # make it the Home screen
    python scripts/tvctl.py debloat            # dry run: shows what would be removed
    python scripts/tvctl.py debloat --apply    # actually remove (reversible with `restore`)

Every removal uses `pm uninstall -k --user 0` (or `pm disable-user`), which is reversible
without a factory reset: `python scripts/tvctl.py restore --all`.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import ipaddress
import json
import os
import shutil
import socket
import subprocess
import sys
import re
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STATE_FILE = ROOT / ".tvctl.json"
REMOVED_LOG = ROOT / "removed_packages.txt"

LAUNCHER_PKG = "com.cleanyourtv.launcher"
LAUNCHER_ACTIVITY = f"{LAUNCHER_PKG}/.MainActivity"

# Stock home screens (these are what serve the "sponsored" rows / ads on Google TV)
STOCK_LAUNCHERS = [
    "com.google.android.apps.tv.launcherx",   # Google TV home
    "com.google.android.tvlauncher",          # Android TV home (Android 8+)
    "com.google.android.leanbacklauncher",    # Older Android TV home
]

# Never touched, whatever the profile — removing these can bootloop or brick setup/input.
NEVER_TOUCH = {
    "android", "com.android.systemui", "com.android.settings", "com.android.tv.settings",
    "com.android.vending", "com.google.android.gms", "com.google.android.gsf",
    "com.android.providers.settings", "com.android.providers.media", "com.android.providers.downloads",
    "com.android.packageinstaller", "com.google.android.packageinstaller",
    "com.google.android.tungsten.setupwraith", "com.google.android.tv.remote.service",
    "com.google.android.leanback.ime", "com.google.android.inputmethod.latin",
    "com.android.tv", "com.android.shell", "com.android.phone", "com.android.bluetooth",
    "com.google.android.webview", "com.android.webview", "com.google.android.tvsetup",
    LAUNCHER_PKG,
}

# Removal profiles. "aggressive" includes everything in "safe".
PROFILES = {
    "safe": {
        "com.google.android.tvrecommendations": "Home-screen recommendation feeds",
        "com.google.android.play.games": "Play Games",
        "com.google.android.videos": "Google TV / Play Movies app",
        "com.google.android.music": "Play Music (dead service)",
        "com.google.android.youtube.tvmusic": "YouTube Music for TV",
        "com.google.android.youtube.tvkids": "YouTube Kids for TV",
        "com.google.android.feedback": "Google feedback reporter",
        "com.google.android.tvtutorials": "TV tutorials",
        "com.google.android.leanbacklauncher.partnercustomizer": "OEM/sponsored rows injected into the stock home",
    },
    "aggressive": {
        "com.google.android.katniss": "Google Assistant / voice search (mic button stops working)",
        "com.google.android.apps.mediashell": "Chromecast built-in (casting stops working)",
        "com.google.android.backdrop": "Ambient mode screensaver",
        "com.android.printspooler": "Print spooler",
    },
}


# ───────────────────────── helpers ─────────────────────────

def die(msg: str) -> None:
    print(f"\n✗ {msg}", file=sys.stderr)
    sys.exit(1)


def adb_bin() -> str:
    path = os.environ.get("ADB") or shutil.which("adb")
    if not path:
        die("adb not found. Install Android platform-tools:\n"
            "  Windows: winget install Google.PlatformTools\n"
            "  macOS:   brew install android-platform-tools\n"
            "  Linux:   sudo apt install adb\n"
            "or set ADB=/full/path/to/adb")
    return path


def load_state() -> dict:
    try:
        return json.loads(STATE_FILE.read_text())
    except (OSError, ValueError):
        return {}


def save_state(**kv) -> None:
    state = load_state()
    state.update(kv)
    STATE_FILE.write_text(json.dumps(state, indent=2))


def adb(*args: str, serial: str | None = None, check: bool = False, timeout: int = 60) -> str:
    cmd = [adb_bin()]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        if check:
            die(f"timed out: {' '.join(cmd)}")
        return ""
    out = (res.stdout + res.stderr).strip()
    if check and res.returncode != 0:
        die(f"{' '.join(args)} failed:\n{out}")
    return out


def serial_or_die() -> str:
    serial = load_state().get("serial")
    if not serial:
        die("No TV remembered yet. Run: python scripts/tvctl.py connect")
    state = adb("get-state", serial=serial).strip()
    if state != "device":
        # Wi-Fi ADB drops after TV sleep — try reconnecting once.
        adb("connect", serial, timeout=15)
        state = adb("get-state", serial=serial).strip()
    if state != "device":
        die(f"TV {serial} not reachable (state: {state or 'offline'}).\n"
            "Make sure the TV is on and run: python scripts/tvctl.py connect")
    return serial


def shell(serial: str, command: str, timeout: int = 60) -> str:
    return adb("shell", command, serial=serial, timeout=timeout)


def installed_packages(serial: str) -> set[str]:
    out = shell(serial, "pm list packages")
    return {line.split(":", 1)[1].strip() for line in out.splitlines() if line.startswith("package:")}


def log_removal(action: str, pkg: str) -> None:
    with REMOVED_LOG.open("a", encoding="utf-8") as f:
        f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')}\t{action}\t{pkg}\n")


# ───────────────────────── discovery ─────────────────────────

def local_ipv4() -> str | None:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packet is sent; just picks the LAN interface
        return s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()


def scan_lan(port: int = 5555) -> list[str]:
    me = local_ipv4()
    if not me or me.startswith("127."):
        die("Couldn't detect your Wi-Fi IP. Pass the TV's IP directly: tvctl.py connect 192.168.x.x")
    net = ipaddress.ip_network(f"{me}/24", strict=False)
    print(f"→ Scanning {net} for ADB (port {port}) …")

    def probe(host: str) -> str | None:
        try:
            with socket.create_connection((host, port), timeout=0.5):
                return host
        except OSError:
            return None

    hosts = [str(h) for h in net.hosts() if str(h) != me]
    with concurrent.futures.ThreadPoolExecutor(max_workers=128) as pool:
        found = [h for h in pool.map(probe, hosts) if h]

    # Android 11+ "Wireless debugging" advertises over mDNS on a random port
    mdns = adb("mdns", "services", timeout=10)
    for line in mdns.splitlines():
        parts = line.split()
        if len(parts) >= 3 and "_adb-tls-connect" in line:
            found.append(parts[-1])  # ip:port
    return sorted(set(found))


# ───────────────────────── commands ─────────────────────────

def cmd_scan(_args) -> None:
    found = scan_lan()
    if not found:
        print("No TVs with ADB found. On the TV: Settings → Device Preferences → Developer options →\n"
              "turn on 'USB debugging' and 'Network debugging' (or 'Wireless debugging').")
        return
    print("Found:")
    for h in found:
        print(f"  • {h}")


def cmd_pair(args) -> None:
    print(adb("pair", args.target, args.code, timeout=30))


def cmd_connect(args) -> None:
    target = args.ip
    if not target:
        found = scan_lan()
        if not found:
            die("No TV found on this network. Check developer options on the TV, "
                "or pass its IP: tvctl.py connect 192.168.1.50")
        if len(found) == 1:
            target = found[0]
        else:
            for i, h in enumerate(found, 1):
                print(f"  {i}. {h}")
            pick = input("Which one is the TV? [1]: ").strip() or "1"
            target = found[int(pick) - 1]
    if ":" not in target:
        target += ":5555"

    print(f"→ Connecting to {target} …")
    print(adb("connect", target, timeout=20))
    print("  If the TV shows 'Allow USB debugging?', tick 'Always allow' and press OK.")

    for _ in range(40):
        state = adb("get-state", serial=target).strip()
        if state == "device":
            break
        time.sleep(1.5)
    else:
        die("TV didn't authorize in time. Accept the prompt on the TV and run connect again.")

    save_state(serial=target)
    print(f"✓ Connected to {target}")
    cmd_info(args)


def cmd_info(_args) -> None:
    s = serial_or_die()
    props = {
        "Brand": "ro.product.brand",
        "Model": "ro.product.model",
        "Android": "ro.build.version.release",
        "SDK": "ro.build.version.sdk",
    }
    for name, prop in props.items():
        print(f"  {name:8} {shell(s, f'getprop {prop}')}")
    home = shell(s, "cmd package resolve-activity --brief -c android.intent.category.HOME "
                    "-a android.intent.action.MAIN").splitlines()
    print(f"  {'Home':8} {home[-1] if home else '?'}")


def cmd_packages(_args) -> None:
    s = serial_or_die()
    all_pkgs = sorted(installed_packages(s))
    user = shell(s, "pm list packages -3")
    disabled = shell(s, "pm list packages -d")
    out = ROOT / "packages.txt"
    out.write_text(
        "# ALL PACKAGES\n" + "\n".join(all_pkgs) +
        "\n\n# USER-INSTALLED (-3)\n" + user +
        "\n\n# DISABLED (-d)\n" + disabled + "\n",
        encoding="utf-8",
    )
    print(f"✓ {len(all_pkgs)} packages saved to {out}")


def cmd_debloat(args) -> None:
    s = serial_or_die()
    plan = dict(PROFILES["safe"])
    if args.profile == "aggressive":
        plan.update(PROFILES["aggressive"])
    for extra in args.extra or []:
        plan[extra] = "added on command line"

    installed = installed_packages(s)
    targets = {p: why for p, why in plan.items() if p in installed and p not in NEVER_TOUCH}
    skipped = [p for p in (args.extra or []) if p in NEVER_TOUCH]
    if skipped:
        print("⚠ Refusing to touch protected packages: " + ", ".join(skipped))

    if not targets:
        print("Nothing from this profile is installed. Run `tvctl.py packages` and share packages.txt "
              "to build a list for your TV.")
        return

    print(f"Profile '{args.profile}' — {len(targets)} package(s):")
    for p, why in targets.items():
        print(f"  • {p:48} {why}")

    if not args.apply:
        print("\nDry run only. Re-run with --apply to remove them (reversible with `restore`).")
        return

    for p in targets:
        out = shell(s, f"pm uninstall -k --user 0 {p}")
        ok = "Success" in out
        print(f"  {'✓' if ok else '✗'} {p} {'' if ok else out}")
        if ok:
            log_removal("uninstalled", p)
    print(f"\nLog: {REMOVED_LOG}")


def cmd_restore(args) -> None:
    s = serial_or_die()
    pkgs = list(args.packages or [])
    if args.all:
        if not REMOVED_LOG.exists():
            die("No removed_packages.txt found — nothing to restore.")
        for line in REMOVED_LOG.read_text(encoding="utf-8").splitlines():
            parts = line.split("\t")
            if len(parts) == 3 and parts[2] not in pkgs:
                pkgs.append(parts[2])
    if not pkgs:
        die("Give package names or --all")
    for p in pkgs:
        a = shell(s, f"cmd package install-existing {p}")
        b = shell(s, f"pm enable {p}")
        ok = "installed" in a.lower() or "enabled" in b.lower()
        print(f"  {'✓' if ok else '•'} {p}")


def cmd_install(args) -> None:
    s = serial_or_die()
    apk = Path(args.apk)
    if not apk.exists():
        die(f"{apk} not found")
    print(f"→ Installing {apk.name} …")
    print(adb("install", "-r", str(apk), serial=s, timeout=300))


def github_repo() -> str:
    """owner/repo from this checkout's git remote (or TVCTL_REPO)."""
    if os.environ.get("TVCTL_REPO"):
        return os.environ["TVCTL_REPO"]
    try:
        url = subprocess.run(["git", "-C", str(ROOT), "remote", "get-url", "origin"],
                             capture_output=True, text=True).stdout.strip()
    except OSError:
        url = ""
    m = re.search(r"github\.com[:/]([^/]+)/([^/]+?)(?:\.git)?$", url)
    if not m:
        die("Can't tell which GitHub repo to download from. Set TVCTL_REPO=owner/cleanyourtv")
    return f"{m.group(1)}/{m.group(2)}"


def cmd_update(args) -> None:
    """Download the latest launcher APK from GitHub Releases and install it."""
    repo = github_repo()
    url = f"https://github.com/{repo}/releases/latest/download/cleanyourtv-launcher.apk"
    dest = ROOT / "cleanyourtv-launcher.apk"
    print(f"→ Downloading {url}")
    try:
        with urllib.request.urlopen(url, timeout=120) as resp, dest.open("wb") as f:
            shutil.copyfileobj(resp, f)
    except urllib.error.HTTPError as e:
        die(f"Download failed ({e.code}). Has a build finished since the release step was added?\n"
            f"Check https://github.com/{repo}/releases")
    except urllib.error.URLError as e:
        die(f"Download failed: {e.reason}")
    print(f"✓ Saved {dest.name} ({dest.stat().st_size / 1e6:.1f} MB)")
    cmd_install(argparse.Namespace(apk=str(dest)))
    if args.launch:
        s = serial_or_die()
        print(shell(s, f"am start -n {LAUNCHER_ACTIVITY}"))


def cmd_home(args) -> None:
    s = serial_or_die()
    if LAUNCHER_PKG not in installed_packages(s):
        die("CleanYourTV launcher isn't installed yet. Run: tvctl.py install <apk>")

    out = shell(s, f"cmd package set-home-activity {LAUNCHER_ACTIVITY}")
    print(f"→ set-home-activity: {out or 'ok'}")

    if args.disable_stock:
        # Safety: only disable the stock home once ours is installed AND launches.
        shell(s, f"am start -n {LAUNCHER_ACTIVITY}")
        time.sleep(2)
        top = shell(s, "dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity'")
        if LAUNCHER_PKG not in top:
            die("CleanYourTV didn't come to the front, so the stock launcher was NOT disabled.\n"
                f"Foreground: {top}")
        installed = installed_packages(s)
        for p in STOCK_LAUNCHERS:
            if p in installed:
                res = shell(s, f"pm disable-user --user 0 {p}")
                print(f"  {'✓' if 'disabled' in res.lower() else '✗'} {p} {res}")
                if "disabled" in res.lower():
                    log_removal("disabled", p)
        print("\nStock home (and its ad rows) is off. Undo any time: tvctl.py restore-stock")
    else:
        print("Press the Home button on your remote. If the TV asks, pick 'CleanYourTV' → Always.\n"
              "Google TV often ignores this; then run: tvctl.py home --disable-stock")


def cmd_restore_stock(_args) -> None:
    s = serial_or_die()
    for p in STOCK_LAUNCHERS:
        out = shell(s, f"pm enable {p}")
        if "enabled" in out.lower():
            print(f"  ✓ {p} re-enabled")
    print("Stock home is back. Press Home on the remote.")


def cmd_setup(args) -> None:
    """One-shot: connect → install → home → debloat (dry run unless --apply)."""
    if not load_state().get("serial") or args.ip:
        cmd_connect(argparse.Namespace(ip=args.ip))
    cmd_install(argparse.Namespace(apk=args.apk))
    cmd_home(argparse.Namespace(disable_stock=args.disable_stock))
    cmd_debloat(argparse.Namespace(profile="safe", apply=args.apply, extra=None))


def main() -> None:
    ap = argparse.ArgumentParser(prog="tvctl", description="Clean up and re-skin an Android TV over Wi-Fi ADB.")
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("scan", help="find TVs with ADB on your Wi-Fi").set_defaults(fn=cmd_scan)

    p = sub.add_parser("pair", help="Android 11+ wireless-debugging pairing")
    p.add_argument("target", help="ip:port shown under 'Pair device with pairing code'")
    p.add_argument("code", help="6-digit pairing code")
    p.set_defaults(fn=cmd_pair)

    p = sub.add_parser("connect", help="scan (or use IP) and connect")
    p.add_argument("ip", nargs="?", help="TV IP, e.g. 192.168.1.50 or 192.168.1.50:5555")
    p.set_defaults(fn=cmd_connect)

    sub.add_parser("info", help="model, Android version, current Home app").set_defaults(fn=cmd_info)
    sub.add_parser("packages", help="save packages.txt").set_defaults(fn=cmd_packages)

    p = sub.add_parser("debloat", help="remove bloat (dry run unless --apply)")
    p.add_argument("--profile", choices=PROFILES.keys(), default="safe")
    p.add_argument("--apply", action="store_true")
    p.add_argument("--extra", nargs="*", help="extra package names to remove")
    p.set_defaults(fn=cmd_debloat)

    p = sub.add_parser("restore", help="bring back removed packages")
    p.add_argument("packages", nargs="*")
    p.add_argument("--all", action="store_true", help="restore everything in removed_packages.txt")
    p.set_defaults(fn=cmd_restore)

    p = sub.add_parser("install", help="install an APK")
    p.add_argument("apk")
    p.set_defaults(fn=cmd_install)

    p = sub.add_parser("update", help="download the latest launcher from GitHub Releases and install it")
    p.add_argument("--launch", action="store_true", help="open it on the TV afterwards")
    p.set_defaults(fn=cmd_update)

    p = sub.add_parser("home", help="make CleanYourTV the Home screen")
    p.add_argument("--disable-stock", action="store_true", help="also disable the stock (ad-serving) launcher")
    p.set_defaults(fn=cmd_home)

    sub.add_parser("restore-stock", help="re-enable the stock launcher").set_defaults(fn=cmd_restore_stock)

    p = sub.add_parser("setup", help="connect + install + home + debloat in one go")
    p.add_argument("apk")
    p.add_argument("--ip")
    p.add_argument("--apply", action="store_true", help="actually debloat (default: dry run)")
    p.add_argument("--disable-stock", action="store_true")
    p.set_defaults(fn=cmd_setup)

    args = ap.parse_args()
    adb_bin()  # fail fast with install help before scanning
    me = local_ipv4() or ""
    if me.startswith("172.20.10."):
        print("ℹ You're on an iPhone Personal Hotspot (172.20.10.x). The TV must be on the same hotspot.")
    args.fn(args)


if __name__ == "__main__":
    main()
