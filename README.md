# Home Control

A small Android TV / Google TV app that shows your Home Assistant entities as a full-screen panel of
tiles, driven by the remote. D-pad moves, OK toggles (or runs a scene or script), Back closes.
Tiles are grouped under room headings.

- No dependencies: plain Android views, `HttpURLConnection` and `org.json`. Only permission: INTERNET.
- Nothing runs in the background. While the panel is open it reads each entity every 5 seconds from
  Home Assistant's REST API; when it closes, nothing is running.
- Sideloaded debug build, tested on a Xiaomi Mi TV Stick 4K (Google TV, Android 11).

## Requirements

- Android TV / Google TV device on your network with network (ADB) debugging on, minSdk 26 (Android 8+)
- On the computer: JDK 17, Android SDK (platform 34, build-tools 34, platform-tools), `python3`, `adb`
- A Home Assistant user for the TV (non-admin is recommended) and a long-lived access token for it

## Build and install

    export TV_HOST=<tv-ip>               # required; port defaults to 5555 (TV_PORT to change)
    # make sure JAVA_HOME and ANDROID_HOME point at your JDK 17 and Android SDK
    ./ha.sh install
    cp config.example.json config.json   # edit url, token, entities
    ./ha.sh config config.json
    ./ha.sh open

`ha.sh` runs `adb` from your PATH, or set `ADB=/path/to/adb`. To load an environment file first, set
`HA_ENV=/path/to/file.sh`. Run `./ha.sh` with no argument for the list of commands.

## config.json

    {
      "url": "http://<ha-ip>:8123",
      "token": "long-lived access token",
      "columns": 4,
      "entities": [
        { "room": "Living Room", "entities": [
            { "id": "light.left", "name": "Left Light" },
            "fan.ceiling"
        ] },
        { "room": "Bedroom", "entities": [ "light.bed_main" ] }
      ]
    }

| key | meaning |
| --- | --- |
| `url` | `http://<ha-ip>:8123`, or an `https://` address |
| `token` | long-lived access token |
| `entities` | rooms `{"room": ..., "entities": [...]}`; each entity is `"light.x"` or `{"id": "light.x", "name": "Label"}` (no name: Home Assistant's friendly_name). Entities listed outside a room show without a heading |
| `columns` | tiles per row, 2 to 6, default 4 |
| `key` | optional remote button for the built-in key service (see below) |
| `log_keys` | `true` to log key names to logcat (tag `HaPanel`) while looking for a usable button |

Lights, switches, fans, input_booleans, covers and similar toggle. Scenes and scripts run. Buttons
press. Locks and media players are not handled yet.

The file is stored in the app's private storage and written through `run-as`, which works because the
build is a debug build. `./ha.sh config` validates the JSON before pushing it.

## Opening the panel from the remote

Two ways, pick one:

1. **A launcher/remote-mapping app.** The app is a normal launchable app named "Home Control", so any
   remote-button mapper (for example tvQuick Actions) can launch it from a button.
2. **Built-in key service (optional, off by default).**

       ./ha.sh key-on
       # set "log_keys": true in config.json, push it, then:
       ./ha.sh keys          # press your remote's extra buttons; note the names that appear
       # put a name in "key", set log_keys back to false, push again

   It is an accessibility service that only consumes the one key you name; every other key passes
   through untouched. Buttons the system keeps for itself (Home, Assistant, power) never reach apps.
   Reinstalling resets it: run `./ha.sh key-on` again. Turn it off with `./ha.sh key-off`.

## Optional: Glass TV launcher card

The app also declares a module card for the [Glass TV](https://github.com/cocombee/glass-tv) launcher.
On that launcher, after you approve the prompt once, Home Control appears as a dock tile with an amber
glow. On any other launcher the declaration is ignored and the app behaves as a regular app.

## Remove

    ./ha.sh remove

## Security notes

- The token lives in `config.json` on the TV (private app storage). Use a dedicated non-admin Home
  Assistant user and revoke the token if the TV is lost.
- `http://` sends the token unencrypted on your LAN; use `https://` where you can.
- Never commit `config.json` (it is in `.gitignore`). If a token was ever committed, revoke it in Home
  Assistant.
