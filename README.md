<p align="center">
  <img src="docs/icon.png" alt="FlashForgeUI icon" width="96">
</p>

<h1 align="center">FlashForgeUI for Android</h1>

<p align="center">
  Monitor and control FlashForge 3D printers from your phone, over your local Wi-Fi.
</p>

<p align="center">
  <b>Beta.</b> Expect rough edges, and please <a href="https://github.com/Parallel-7/FlashForgeUI-Android/issues">report what you find</a>.
</p>

---

FlashForgeUI is a native Android companion to the desktop
[FlashForgeUI](https://github.com/Parallel-7/FlashForgeUI-Electron) app. It talks to your
printer directly on your home network, with no cloud, no account and no FlashForge app required.

> FlashForgeUI is a community project. It is not made or endorsed by FlashForge.

## Supported printers

| Printer | Status |
|---|---|
| Adventurer 5M Pro | ✅ Tested |
| AD5X | ✅ Tested |
| Adventurer 5M | Should work (shares the 5M Pro code path), not yet tested |
| Creator 5 / Creator 5 Pro | 🧪 Experimental. Built but never tested on a real printer |
| Adventurer 3 / Adventurer 4 | 🧪 Experimental. Status monitoring only |

If you own an untested model, your feedback is especially valuable.

## Features

- **Auto-discovery.** Finds FlashForge printers on your network automatically, and follows a
  printer if its IP address changes.
- **Live dashboard.** Temperatures, progress, layer, time remaining and the current file's thumbnail.
- **Print control.** Pause, resume and stop, with a confirmation before stopping.
- **Temperature, homing and lights.** Set nozzle, bed and (where present) chamber temperatures.
- **Camera.** Live view of the printer camera, with tap-to-fullscreen. Custom camera URLs are supported.
- **Files.** Browse the printer's files and start a print, with material matching on the AD5X.
- **Material station.** View and edit AD5X IFS slots (material and color).
- **Air filtration.** Controls and air-quality readout on the 5M Pro.
- **Multiple printers.** Connect several at once, each in its own swipeable tab.
- **Alerts.** Notifications when a print finishes, when the bed has cooled and the print is safe to
  remove, or when the printer reports an error. Optionally keeps watching in the background.
- **Spoolman.** Browse, search and edit your [Spoolman](https://github.com/Donkie/Spoolman)
  filament inventory and log usage by hand.
- **NFC spool tags.** Write NFC tags for spools and storage boxes. Scan a spool tag to jump to it,
  or to load its material and color into an AD5X material-station slot.

## Requirements

- Android 8.0 or newer.
- Phone and printer on the same local network.
- For 5M-series and newer printers: **LAN-only mode** enabled on the printer, plus its check code.
  See [Pairing your printer](https://github.com/Parallel-7/FlashForgeUI-Electron/wiki/1.-Pairing-your-printer).

## Install

1. Download the APK from the [Releases](https://github.com/Parallel-7/FlashForgeUI-Android/releases) page.
   Most phones need the **`arm64-v8a`** APK. Use `armeabi-v7a` only on older 32-bit phones.
2. Open the file on your phone and tap **Install**. Android may ask you to allow installs from
   your browser or file manager first.

## Getting started

1. Open the app and go to the **Printers** tab. It scans your network automatically.
2. Tap your printer and enter its check code.
3. Tap **Connect**. The dashboard opens with live status.

The app reconnects to your printers the next time you open it (configurable in **Settings**).

### Background alerts

To get alerts while the app is closed, turn on **Settings → Keep monitoring in background** and
allow notifications. When the app asks, also allow background activity. Without it, many phones
pause the app to save battery and alerts stop.

### Spoolman and NFC

Turn on **Settings → Spoolman**, enter your Spoolman server address (for example
`http://192.168.1.50:7912`) and tap **Test connection**. A **Spools** tab appears. If your phone
has NFC, enable **NFC tags** in the same screen to read and write spool and box tags.

## Troubleshooting

| Problem | Try this |
|---|---|
| Printer doesn't show up | Make sure the phone and printer are on the same network (not a guest network). Some routers block discovery; you can still add the printer by IP. |
| "Authentication failed" | Re-check the serial number and check code, and that LAN-only mode is on. |
| "Not in LAN mode" | Enable LAN-only mode on the printer's network settings. |
| Printer moved to a new IP | Reconnect from the Printers tab. If it can't be found, the app asks for the new address. |
| Alerts stop when the app is closed | Enable background monitoring and allow background activity (see above). |

## Privacy

Everything stays on your local network. The app has no account, no analytics and no cloud
service. Saved printers are stored on your phone and included in your normal Android backup so
they move with you to a new phone.

## Contributing

Bug reports and feedback are welcome via [Issues](https://github.com/Parallel-7/FlashForgeUI-Android/issues).
Please include your printer model, firmware version and Android version.

To build from source, see [DEVELOPING.md](DEVELOPING.md).

## License

[Apache 2.0](LICENSE). Third-party components are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
