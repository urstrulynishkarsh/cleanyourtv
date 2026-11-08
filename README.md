# CleanYourTV

Ad-free, Apple TV–style home screen for Android TV / Google TV, plus a one-file tool that finds your TV on Wi-Fi, removes bloatware and swaps out the ad-serving stock launcher. Everything is reversible.

## What you get

**Launcher (`app/`)** — Kotlin + Jetpack Compose
- **Top Shelf hero** that changes with whatever app you focus, with crossfading banner art
- **Dynamic backdrop**: the whole screen's colour follows the focused app (extracted with Palette)
- tvOS-style tiles: focus lifts the tile with scale, shadow and a glass shine
- Top Shelf row you curate: hold **OK** (or press **☰ Menu**) on any app → add/remove, move left/right, hide, app info
- **All Apps** grid that updates live when apps are installed or removed (shows sideloaded phone apps too)
- Clock and date, a Settings tile, and "Unhide apps"
- No ads, no recommendations, no network calls

**`scripts/tvctl.py`** — Windows / macOS / Linux, needs Python 3.8+ and `adb`
- `scan` / `connect`: finds the TV on your Wi-Fi (port 5555 scan + Android 11+ mDNS) and connects
- `debloat`: dry run by default; `--apply` removes using `pm uninstall -k --user 0`
- `home --disable-stock`: makes CleanYourTV the Home screen, and only disables Google's launcher **after** checking ours actually launched
- `restore --all` and `restore-stock` put everything back
- A protected list (Settings, Play Services, setup, keyboard, remote service…) is never touched

## Setup

### 1. TV
Settings → Device Preferences → About → click **Build** 7× → Developer options:
- **USB debugging**: ON
- **Network debugging**: ON (older TVs, port 5555), **or**
- **Wireless debugging**: ON (Android 11+). Use `tvctl.py pair <ip:port> <code>` first.

### 2. Get the APK
Every push to `main` builds it automatically: **Actions → Build launcher APK → latest run → Artifacts → `cleanyourtv-launcher`**. Unzip it.
Or open the folder in Android Studio and click Run.

### 3. Run
```bash
python scripts/tvctl.py connect                 # accept "Allow debugging?" on the TV → Always allow
python scripts/tvctl.py packages                # optional: saves packages.txt
python scripts/tvctl.py install app-release.apk
python scripts/tvctl.py home                    # press Home on the remote and choose CleanYourTV
python scripts/tvctl.py home --disable-stock    # Google TV ignores the choice? this kills the ad launcher
python scripts/tvctl.py debloat                 # see the list
python scripts/tvctl.py debloat --apply         # remove it
```
Or all at once: `python scripts/tvctl.py setup app-release.apk --apply --disable-stock`

### Undo
```bash
python scripts/tvctl.py restore-stock    # stock home screen back
python scripts/tvctl.py restore --all    # every removed app back
```
Nothing here needs root, and nothing needs a factory reset to undo.

## Debloat profiles
| Profile | Removes | Side effects |
|---|---|---|
| `safe` (default) | recommendation feeds, Play Games, Play Movies/Google TV app, YouTube Music/Kids, feedback, tutorials | none |
| `aggressive` | the above + Assistant/voice search, Chromecast built-in, Ambient mode, print spooler | mic button and casting stop working |

Brand-specific bloat (Xiaomi PatchWall, TCL, Sony, etc.) varies by TV. Run `packages`, then `debloat --extra <pkg> …`.

## Notes
- Google TV can re-enable its launcher after a big system update. Run `home --disable-stock` again if ads come back.
- Phone-only apps open, but some need a mouse. The hero line tells you which ones are phone apps.
