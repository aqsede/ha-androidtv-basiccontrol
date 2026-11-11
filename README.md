# Home Control (Home Assistant panel for Glass TV)

A small Android TV app. It adds a "Home Control" card to the Glass TV dock; opening it shows a
full-screen panel of your Home Assistant entities. D-pad moves, OK toggles, Back closes. Optionally one
remote button opens and closes the panel from any app.

Nothing runs in the background. While the panel is open it asks HA for each entity every 5 seconds;
when it closes, nothing is running. The only permission is INTERNET. Debug build, sideloaded.

## Build and install (Steam Deck, same toolchain as Glass TV)

    export TV_HOST=192.168.100.14        # default already set in ha.sh
    ./ha.sh install
    cp config.example.json config.json   # edit url, token, entities
    ./ha.sh config config.json
    ./ha.sh open
    adb shell input keyevent KEYCODE_HOME   # press Home: approve the "Home Control" card prompt once

## config.json

| key | meaning |
| --- | --- |
| `url` | `http://<ha-ip>:8123` or an `https://` address |
| `token` | long-lived access token of a dedicated non-admin `tv` user |
| `entities` | list of `"light.x"` or `{"id": "light.x", "name": "Bedroom"}`. Without a name, HA's friendly_name is used |
| `key` | optional remote button, e.g. `"KEYCODE_PROG_RED"` or a number |
| `log_keys` | `true` to log key names while you look for a usable button |

Lights, switches, fans, input_booleans, covers and similar toggle. Scenes and scripts run. Buttons press.
Locks and media players are not handled in v1.

## Remote button (optional)

    ./ha.sh key-on
    # set "log_keys": true, push config, then:
    ./ha.sh keys          # press the extra buttons on your remote; note the names that appear
    # put the name in "key", set log_keys back to false, push config again

Buttons the TV's system keeps for itself (Home, Assistant, power) never reach apps; pick one that shows
up in `./ha.sh keys`. Reinstalling resets the service: run `./ha.sh key-on` again.

## Remove

    ./ha.sh remove

## Security notes

- The token lives in config.json on the TV (app storage, not readable by other normal apps). Use a
  non-admin HA user, and revoke the token in HA if the TV is lost.
- `http://` sends the token unencrypted on your LAN. Use `https://` if you can.
