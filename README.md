# Balatro Dual Screen (Steam edition)

An Android app that runs your own copy of [Balatro](https://www.playbalatro.com/)
on a handheld — split across both screens on dual-screen devices such as the
**AYN Thor**, and as the normal game on everything else, including foldables.

It signs in to Steam, downloads the game for you, and keeps your saves and
achievements in step with Steam on PC.

> Free and unofficial. If you paid for this, ask for a refund.

![Playing a hand across both screens](screenshots/hand.png)

## Features

- **Two screens, one game.** Table, jokers and score on top; your hand,
  Play / Sort / Discard, the shop and booster packs on the touchscreen below.
  On a single screen, or with the second one off, it plays exactly like vanilla.
- **Steam sign-in by QR code.** Scan once with the Steam app on your phone. No
  password is typed on the device.
- **Download from Steam.** First-time setup fetches `Balatro.exe` from your
  Steam library — or pick a `Balatro.exe`, zipped `Balatro.app` or
  `Balatro.love` you already have.
- **Steam Cloud saves.** Saves are pulled before you play and pushed when you
  leave, so a run started on PC continues on the handheld and back. When both
  sides changed, you choose which to keep and the other is backed up.
- **Steam achievements.** Unlocks earned on the handheld are submitted to your
  Steam account.
- **Controller navigation across both screens**, following what you see rather
  than the game's internal layout.
- **Listed as a game** by launchers and Samsung's Game Booster / Gaming Hub.
- **Updates itself** from this repository's releases.

## Requirements

- Android 8.0 (API 26) or newer, arm64.
- **Balatro for PC or Mac on Steam** (or a copy you own of the PC/Mac build).
  The app contains no part of the game. The iOS, Android, Mac App Store and
  console builds cannot be used.
- Built and tested against Steam **1.0.1o**; newer PC versions get a warning
  and usually work.
- Tested on the AYN Thor and a Galaxy Z Fold 8.

## Install

1. Download the APK from [**Releases**](../../releases) (or add this repository
   to Obtainium).
2. Install it. Android asks you to allow installs from that source.
3. Open it and choose **Download from Steam**, then scan the QR code with the
   Steam app. The game is downloaded, built on the device and started — your
   cloud saves come along.
4. The app offers to reinstall itself once so it can mark itself as a game for
   Samsung's game services. Optional.

Coming from the original release? It is signed with a different key: uninstall
it first. Its local saves are lost unless they were already on Steam Cloud.

## Building from source

```bash
python3 tools/build.py --release                      # publishable APK, no game inside
python3 tools/build.py --balatro path/to/Balatro.exe  # dev APK with your game embedded
```

- `--icon <Balatro.app|Balatro.exe|GameIcon.icns|png>` gives a **personal**
  build the game's own launcher icon. Such an APK is not publishable.
- `--app-id <id>` builds beside an existing install, for testing.
- Release signing reads `BALATRO_STORE_FILE` and friends from
  `~/.gradle/gradle.properties`; without them the debug key is used.

Toolchain versions are in [`docs/android-build.md`](docs/android-build.md),
design decisions in [`docs/decisions/`](docs/decisions/).

This is not a fork of Balatro: it is an Android app, a Lua overlay that wraps
the game's own functions at load time, and a build script. The repository
contains no Balatro code or assets, and `tools/check_guards.py` keeps it that way.

## Credits

This builds on **[balatro-dualscreen](https://github.com/rosbean/balatro-dualscreen)
by rosbean**, which created the dual-screen overlay, the on-device game
assembly and the AYN Thor support. If you enjoy the dual-screen part, say thanks
to them on [Ko-fi](https://ko-fi.com/rosbean).

Also from earlier work:

- [BanjoRecomp](https://github.com/BanjoRecomp/BanjoRecomp) (GPL-3.0) — the
  dual-screen Android implementation the original was adapted from.
- [Bifrost](https://github.com/Pollux-MoonBench/Bifrost) (GPL-3.0) — the
  technique for the AYN Thor's stick LEDs.
- wmcloud from [wildermyth-android](https://github.com/hung-eggie-do-covergo/wildermyth-android)
  (MIT) — Steam sign-in, depot download and Cloud sync, built on
  [JavaSteam](https://github.com/Longi94/JavaSteam).
- [balatro-portrait-mobile](https://github.com/ShaggyLorean/balatro-portrait-mobile)
  — consulted as reference only; no code taken.

[`NOTICE.md`](NOTICE.md) records what came from where. Written with substantial
help from an AI coding assistant; every feature was tested on real hardware.

## Licence

GPL-3.0 — see [`COPYING`](COPYING).

Balatro is © LocalThunk, published by Playstack. Steam is a trademark of Valve.
This project is not affiliated with or endorsed by any of them.
