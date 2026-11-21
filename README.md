# Swordash Offline Server

A local server emulator for the Android game **Swordash**. You can run it standalone with Python (Termux/PC) or embed the Java port directly into the APK so it runs out of the box without needing a separate terminal.

This is a reverse-engineering project for basic offline play. Game APKs and proprietary assets are not included.

## What works

- Local HTTP route (`127.0.0.1:12034`) and TCP game server (`127.0.0.1:2345`).
- Sproto packet packing/unpacking and raw deflate compression for Lua tables (`role_data`, `sync_list`).
- Room transitions across 296 stages parsed dynamically from `stage.txt`.
- Stage unlocking, 3-star bit-packing (`[7][4]` array, 2 bits per stage), and local JSON save persistence (`save_offline.json`).
- Skin equipping (`tag 128`).
- Embedded APK mode via a single-file Java 6/7 port (`OfflineServer.java`) that starts on app launch and stores saves in internal app storage.

## What doesn't work

Swordash relies heavily on server-side logic. Unimplemented tags currently return a generic `AUTO-OK` response so the client doesn't hang, which means the following features are non-functional:

- Equipment upgrades and crafting.
- In-stage gear drops and detailed clear rewards.
- Shop, mail, gifts, and events.

## Project structure

```text
swordash-offline/
├── offline_server.py            # Standalone Python server (Termux / PC)
├── java_build/
│   └── sd/
│       └── OfflineServer.java   # Java port for APK injection
└── data/
    ├── role_data.txt            # Base player Lua table
    ├── stage.txt                # Room layouts for 296 stages
    ├── login_capture.log        # Captured login replay packets
    └── login_s2c_unpacked.bin   # Raw S2C login packet template
```

## Setup

Both methods assume you already have a patched Swordash APK pointing its network endpoints to `127.0.0.1`.

### Option 1: Standalone Python (Termux / PC)

Place the required files in the working directory (with `stage.txt` at `out_lua/config/stage.txt`), then run:

```bash
# On Termux, acquire a wake-lock so Android doesn't freeze the server in the background
termux-wake-lock
python offline_server.py
```

Launch the game. Progress is saved to `save_offline.json`.

### Option 2: Embedded in APK (Java + Smali)

1. **Compile to DEX:** Add `OfflineServer.java` to a blank Android project (tested with AIDE; make sure `MainActivity` references `OfflineServer.start(this)` so the compiler doesn't strip it). Build the APK and extract `classes.dex`.
2. **Add assets:** Open the Swordash APK in MT Manager, create `assets/sd_offline/`, and copy `role_data.txt`, `stage.txt`, `login_capture.log`, and `login_s2c_unpacked.bin` into it.
3. **Inject DEX:** Rename the extracted `classes.dex` to the next sequential index in the APK root (e.g., `classes9.dex`) and add it.
4. **Hook `onCreate`:** Open the main launcher activity (usually `com.unity3d.player.UnityPlayerActivity`) in Dex Editor Plus and add this line right after `invoke-super` in `onCreate(Landroid/os/Bundle;)V`:

```smali
invoke-static {p0}, Lsd/OfflineServer;->start(Landroid/content/Context;)V
```

*(Adjust `Lsd/OfflineServer;` if you compiled it under a different package name.)*

5. **Sign and install:** Auto-sign the APK and install it. No external server or Termux required.

## Handled protocol tags

| Tag | Action | Notes |
| --- | --- | --- |
| 3 | Login | Injects unlocked stage, bit-packed stars, and skin into `role_data` |
| 6 | Heartbeat | Returns current Unix timestamp |
| 128 | Equip skin | Updates skin ID in save and syncs `[3][18]` |
| 201 | Enter stage | Loads the first room from `stage.txt` |
| 207 | Next room | Steps through the stage's room list |
| 202 | Clear stage | Unlocks next stage, packs 3 stars, adds coin/capsule/stone |

## Disclaimer

For educational and preservation purposes only. All rights to Swordash belong to its original developers.
