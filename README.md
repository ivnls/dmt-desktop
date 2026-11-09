# tmpl

a tui-inspired music player for **linux, windows and android**.

developed by [ivnls](https://github.com/ivnls), based on
[dmt](https://github.com/imjyotiraditya/dmt) by
[jyotiraditya panda](https://github.com/imjyotiraditya) — "dear music, thanks". tmpl started
as a port of dmt v1.4.0 to the desktop and keeps its look (amber on black, plex mono, cover art
as ascii), its library, player, lyrics, jellyfin and stats.

## downloads

| system | file | notes |
| --- | --- | --- |
| android 11+ | `tmpl-<ver>-universal-release.apk` (or the one for your abi, e.g. `arm64-v8a`) | allow installs from your browser / file manager, then open the apk |
| debian 12+, ubuntu 22.04+ | `tmpl_<ver>_amd64.deb` | `sudo apt install ./tmpl_<ver>_amd64.deb` |
| fedora (tested on 45) | `tmpl-<ver>-1.x86_64.rpm` | `sudo dnf install ./tmpl-<ver>-1.x86_64.rpm` |
| any linux (glibc 2.35+) | `tmpl-<ver>-linux-x86_64.tar.gz` | extract, run `bin/tmpl` or `./install.sh` (per user) |
| windows 10/11 x64 | `tmpl-<ver>-windows-x64.msi` / `.exe` | installers, built by CI on windows |
| windows 10/11 x64 | `tmpl-<ver>-windows-x64-portable.zip` | extract anywhere, run `tmpl.exe` |

the desktop packages bundle their own java runtime, ffmpeg and skia: nothing else to install.
the android app installs as `dev.ivnls.tmpl`, so it lives next to the original dmt instead of
replacing it.

## what's in it

- the `metadata` and `lyrics` modules from dmt, shared by the android and desktop apps (tags for
  mp3/flac/m4a/ogg, lrc + ttml parsing, writing lyrics back into files)
- home shelves, library tabs (tracks, albums, artists, folders, playlists), search, sources, cfg,
  stats, blocklist, first-run setup, mini player, full player, karaoke lyrics, queue and track
  info / chain sheets
- cue sheets, m3u8 playlists, jellyfin login + streaming + lyrics, lrclib on demand, replay gain,
  sleep timer, speed 0.75x–2x (pitch kept), shuffle, repeat, resume last queue, listening stats
- desktop extras: cover stack of the next tracks in the full player, audio-reactive bars in the
  mini player, the queue beside the controls on big screens, keyboard shortcuts, interface size

## desktop vs android

| android | desktop |
| --- | --- |
| MediaStore scan + permissions | you pick music folders (setup / cfg → music folders); scans are cached |
| ExoPlayer + media3 ffmpeg decoder | ffmpeg 8.1 via javacv decodes everything, java sound plays it |
| MediaSession + notification | MPRIS on linux (media keys, gnome/kde widgets, `playerctl`), tray icon |
| "stop on app close" off | closing the window keeps playing from the tray |
| system equalizer | not available (use easyeffects / equalizer apo) |
| hilt, datastore, navigation, coil | plain constructors, a properties file, a tiny router, skia |
| MediaStore album art | embedded art via ffmpeg, then `cover.jpg` / `folder.jpg` next to the files |

### keyboard (desktop)

`space` play/pause · `←/→` seek 5s (`shift` 30s) · `ctrl+←/→` or `p`/`n` previous/next ·
`s` shuffle · `r` repeat · `f`/`enter` full player · `+`/`-` volume · `1`–`5` library tabs ·
`/` search · `h` home · `ctrl+,` config · `ctrl` `+`/`-`/`0` interface size · `esc` back · media keys

### interface size (desktop)

cfg → display → interface size. `auto` follows the desktop's scale (also fractional scaling on
gnome / kde, which java alone ignores) and grows with tall windows. fixed sizes from 75% to 200%
are there too, and `ctrl +`/`ctrl -`/`ctrl 0` change it on the fly.

### security (desktop)

- **jellyfin over http**: the server address defaults to `https://`. plain `http://` is refused
  for servers outside your network, and on your local network (192.168.x, 10.x, `*.local`,
  tailscale...) it needs a second, explicit press after a warning.
- **token**: never written to `tmpl.properties`. it goes to the desktop keyring (Secret Service:
  gnome keyring, kwallet, keepassxc) on linux and is encrypted with DPAPI for your user on
  windows. without a keyring it falls back to a `secrets` file readable only by you (0600).
- **token in urls**: stream and cover requests send the token as an `X-Emby-Token` header, only
  to the server it belongs to, instead of `?api_key=` in the url.
- **ffmpeg**: every input gets a `protocol_whitelist`: local files may only use `file`, streams
  only `https,tls,tcp` (or `http,tcp` for a local http server).

### where things live (desktop)

| | linux | windows |
| --- | --- | --- |
| settings | `~/.config/tmpl/tmpl.properties` | `%APPDATA%\tmpl\tmpl.properties` |
| playlists | `~/.local/share/tmpl/playlists/*.m3u8` | `%APPDATA%\tmpl\playlists` |
| cache (library, covers) | `~/.cache/tmpl` | `%LOCALAPPDATA%\tmpl\cache` |

builds from before the rename used `dmt` folders; they are copied over on the first start.

## build

```
.                 desktop app (compose multiplatform) + shared metadata / lyrics modules
├── desktop/
├── metadata/     shared
├── lyrics/       shared
└── android/      android app (its own gradle build, uses ../metadata and ../lyrics)
```

desktop — jdk 21 (jpackage included). for release packages point `tmpl.javaHome` at a portable
[temurin](https://adoptium.net) jdk (a distro jdk can need a newer glibc than older distros have).

```bash
./gradlew test
./gradlew :desktop:run
./gradlew :desktop:linuxPackages -Ptmpl.javaHome=/path/to/temurin-21   # .deb .rpm .tar.gz → desktop/build/dist
./gradlew :desktop:packageReleaseMsi :desktop:packageReleaseExe       # on windows
```

android — jdk 21 and the android sdk (`ANDROID_HOME` or `android/local.properties`):

```bash
cd android
./gradlew assembleRelease -PdebugSignedRelease=true    # apks → android/app/build/outputs/apk/release
```

`.github/workflows/release.yml` builds everything on push (artifacts) and publishes a release for
`v*` tags. to sign the android apk with your own key (needed so updates install over each other),
add these repository secrets: `SIGNING_KEYSTORE` (the keystore, base64), `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. without them the apk is signed with a throwaway debug
key.

## português (resumo)

tmpl é um player de música para linux (fedora, debian), windows e android, desenvolvido por
ivnls e baseado no dmt de jyotiraditya panda. instale o `.apk` no android, o `.deb`
(`sudo apt install ./tmpl_*.deb`) ou o `.rpm` (`sudo dnf install ./tmpl-*.rpm`) no linux e, no
windows, o `.msi`/`.exe` ou o zip portátil (`tmpl.exe`).

## credits & license

- **tmpl** — developed by [ivnls](https://github.com/ivnls)
- **dmt**, the app tmpl is built on — created by
  [jyotiraditya panda](https://github.com/imjyotiraditya):
  [github.com/imjyotiraditya/dmt](https://github.com/imjyotiraditya/dmt)
- dolby ac-4 on android comes from paul b mahol's decoder in librempeg, built by
  [dmt-decoder-ffmpeg](https://github.com/imjyotiraditya/dmt-decoder-ffmpeg)

GPL-3.0, same as dmt.
