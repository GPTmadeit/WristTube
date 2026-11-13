**WristTube** is YouTube for the Pixel Watch, with the sound on the watch's own speaker. Search by voice or keyboard, watch on the round screen, or switch to sound only and let the screen sleep. No phone, headphones or Google account needed.

> Unofficial: not made by or affiliated with YouTube or Google. It reads YouTube through [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor), without an API key or a sign-in.

### Download

| File | What it is |
|---|---|
| `WristTube-watch-v{{VERSION}}.apk` ({{SIZE}}) | The watch app. Install it once (below); after that it updates itself. |
| `SHA256SUMS.txt` | Checksums for the file above. |

### Installing for the first time

1. On the watch: **Settings → System → About → Versions**, tap **Build number** 7 times. Then **Settings → Developer options**: turn on **ADB debugging** and **Wireless debugging**.
2. In **Wireless debugging**, tap **Pair new device**. On a computer on the same Wi-Fi: `adb pair <ip>:<port> <code>`, then `adb connect <ip>:<port>` with the address shown on the Wireless debugging screen.
3. `adb install WristTube-watch-v{{VERSION}}.apk`

Or use [WatchPush](https://github.com/GPTmadeit/WatchPush) from your phone.

### Updating

Installed copies update themselves: **Settings → Updates** on the watch downloads the new release, checks it was signed by the same key as the installed app, and hands it to the system installer. The home screen also shows when an update is out.

### Changes in {{VERSION}}

{{CHANGES}}

### Credits and license

Stream extraction by [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0); playback by [AndroidX Media3](https://developer.android.com/media/media3). WristTube is GPL-3.0.
