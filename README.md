# WristTube

**YouTube on a Pixel Watch, with the sound on the watch's own speaker.** Search by voice or
keyboard, watch on the round screen or switch to sound only and let the screen sleep. No phone,
no headphones, no Google account.

> **Unofficial.** WristTube is not made by or affiliated with YouTube or Google. It reads YouTube
> the way [NewPipe](https://newpipe.net) does, through [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor),
> without an API key or a sign-in. When YouTube changes something and playback breaks, a new
> WristTube release with a newer extractor fixes it; the app updates itself (below).

| | |
|---|---|
| Watch | Pixel Watch 4 (built and tested for it; any Wear OS 4+ watch with a speaker should work) |
| Needs | Wi-Fi or LTE on the watch for video. The phone's Bluetooth link works for search. |
| Size | about 7 MB |

## Download and install

Get `WristTube-watch-v<version>.apk` from the [releases page](../../releases/latest) (check it
against `SHA256SUMS.txt` if you like) and sideload it once, with
[WatchPush](https://github.com/GPTmadeit/WatchPush) or ADB:

1. On the watch, turn on **Settings → System → About → Versions → Build number** (tap it 7 times),
   then **Settings → Developer options → ADB debugging** and **Wireless debugging**.
2. In Wireless debugging, tap **Pair new device** and, on a computer on the same Wi-Fi, run
   `adb pair <ip>:<port> <code>`, then `adb connect <ip>:<port>` with the address shown above it.
3. `adb install WristTube-watch-v<version>.apk`

After that it updates itself.

## What it does

- **The watch speaker.** Wear OS media apps normally refuse to play without Bluetooth headphones,
  and Media3 even ships a switch for that. WristTube turns it off and plays on the speaker. With
  **Settings → Watch speaker** on (the default) it stays on the speaker even when headphones are
  connected; turn it off to use headphones when they're there. The crown is the volume knob.
- **Video sized for a round screen.** Up to 240p by default (144-480p in Settings), which already
  fills a watch screen. Double-tap the video to switch between *inside the circle*, *full width*
  and *fill*. Progress runs around the edge of the screen.
- **Sound only.** The headphones button drops the video: less data, less battery, and the screen
  can sleep while it plays. Leaving the player, or the screen turning off, does the same
  automatically. Playback carries on in the background and shows in the watch's media controls.
- **Search** by voice or keyboard, with YouTube's spelling suggestion. Long-press a result to see
  more from its channel. **Up next** shows YouTube's suggestions; **Autoplay** can play them.
- **Live streams** play too.
- **History and recent searches** stay on the watch and can be cleared in Settings.

## How it works

- **Streams.** NewPipe Extractor 0.27 gets stream URLs from YouTube's visionOS client, which needs
  no "PoToken". That matters on a watch: generating a PoToken takes a web view, and Wear OS has
  none. WristTube plays H.264 video (hardware-decoded on every watch) with AAC audio in the
  video's original language.
- **No throttling.** YouTube serves one long open-ended download at about 0.14 MB/s, slower than its
  own 128 kbps audio, but serves short ranged requests at full speed (0.5-0.9 MB/s on the same
  connection, measured). [ChunkedDataSource](wear/src/main/kotlin/com/wristtube/play/ChunkedDataSource.kt)
  feeds ExoPlayer 2 MB ranges one after another, so to the player it's still one file.
- **The fast network.** A watch normally reaches the internet through its phone's Bluetooth link,
  too slow for video. While WristTube is open or playing it asks Wear OS for Wi-Fi or LTE and binds
  to it, and looks each video up over that network, because YouTube's stream URLs only work for the
  address that asked for them. If the watch changes network mid-video, the player looks the video
  up again and carries on from the same second.

## Privacy

WristTube talks to YouTube (search, video pages, the media itself, thumbnails) and to GitHub (one
update check when it opens). It has no account, no analytics and no server of its own. History
and searches stay on the watch.

## Build

```sh
export ANDROID_HOME=~/Android/Sdk
./gradlew :wear:assembleRelease
```

Release builds are signed with a key kept outside the repo (`keystore.properties`, git-ignored,
points at it). Without it the build uses the debug key, which can't update an installed release.
`tools/release.sh` builds, checks the signature and publishes a GitHub release from the matching
`CHANGELOG.md` section.

Over ADB, two intent extras skip the typing:

```sh
adb shell am start -n com.wristtube/.MainActivity --es com.wristtube.SEARCH "'lofi hip hop'"
adb shell am start -n com.wristtube/.MainActivity --es com.wristtube.PLAY <video id or URL>
adb logcat -s WristTube/Play WristTube/Chunk WristTube/Net
```

## License

GPL-3.0 (NewPipe Extractor is GPL-3.0). See [LICENSE](LICENSE).
