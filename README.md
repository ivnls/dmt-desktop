# dmt desktop

dear music, thanks — now on linux and windows.

a port of [dmt v1.4.0](https://github.com/imjyotiraditya/dmt/releases/tag/v1.4.0), the
tui-inspired android music player, to the desktop. same look (amber on black, plex mono,
cover art as ascii), same library, player, lyrics, jellyfin and stats; android-only plumbing
replaced with desktop equivalents.

## downloads

| system | file | notes |
| --- | --- | --- |
| debian 12+, ubuntu 22.04+ | `dmt_<ver>_amd64.deb` | `sudo apt install ./dmt_<ver>_amd64.deb` |
| fedora (tested on 45) | `dmt-<ver>-1.x86_64.rpm` | `sudo dnf install ./dmt-<ver>-1.x86_64.rpm` |
| any linux (glibc 2.35+) | `dmt-<ver>-linux-x86_64.tar.gz` | extract, run `bin/dmt` or `./install.sh` (per user) |
| windows 10/11 x64 | `dmt-<ver>-windows-x64.msi` / `.exe` | installers, built by CI on windows |
| windows 10/11 x64 | `dmt-<ver>-windows-x64-portable.zip` | extract anywhere, run `dmt.exe` |

every package bundles its own java runtime, ffmpeg and skia: nothing else to install.

## what carried over

- the android `metadata` and `lyrics` modules, unchanged (tags for mp3/flac/m4a/ogg, lrc + ttml
  parsing, writing lyrics back into files)
- all screens: home shelves, library tabs (tracks, albums, artists, folders, playlists), search,
  sources, cfg, stats, blocklist, first-run setup, mini player, full player with landscape layout,
  karaoke lyrics, queue and track info / chain sheets
- cue sheets, m3u8 playlists, jellyfin login + streaming + lyrics, lrclib on demand, replay gain,
  sleep timer, speed 0.75x–2x (pitch kept), shuffle, repeat, resume last queue, listening stats

## what changed for the desktop

| android | desktop |
| --- | --- |
| MediaStore scan + permissions | you pick music folders (setup / cfg → music folders); scans are cached |
| ExoPlayer + media3 ffmpeg decoder | ffmpeg 8.1 via javacv decodes everything, java sound plays it |
| MediaSession + notification | MPRIS on linux (media keys, gnome/kde widgets, `playerctl`), tray icon |
| "stop on app close" off | closing the window keeps playing from the tray |
| system equalizer | not available (use easyeffects / equalizer apo) |
| hilt, datastore, navigation, coil | plain constructors, a properties file, a tiny router, skia |
| MediaStore album art | embedded art via ffmpeg, then `cover.jpg` / `folder.jpg` next to the files |

### keyboard

`space` play/pause · `←/→` seek 5s (`shift` 30s) · `ctrl+←/→` or `p`/`n` previous/next ·
`s` shuffle · `r` repeat · `f`/`enter` full player · `+`/`-` volume · `1`–`5` library tabs ·
`/` search · `h` home · `ctrl+,` config · `ctrl` `+`/`-`/`0` interface size · `esc` back · media keys

### interface size

cfg → display → interface size. `auto` follows the desktop's scale (also fractional scaling on
gnome / kde, which java alone ignores) and grows with tall windows, so a maximised window on a
big screen is not a phone ui floating in empty space. fixed sizes from 75% to 200% are there
too, and `ctrl +`/`ctrl -`/`ctrl 0` change it on the fly.

### security

- **jellyfin over http**: the server address defaults to `https://`. plain `http://` is refused
  for servers outside your network, and on your local network (192.168.x, 10.x, `*.local`,
  tailscale...) it needs a second, explicit press after a warning.
- **token**: never written to `dmt.properties`. it goes to the desktop keyring (Secret Service:
  gnome keyring, kwallet, keepassxc) on linux and is encrypted with DPAPI for your user on
  windows. without a keyring it falls back to a `secrets` file readable only by you (0600).
  tokens saved by older builds are moved there on first start. the config folder and
  `dmt.properties` are owner-only too.
- **token in urls**: stream and cover requests send the token as an `X-Emby-Token` header, only
  to the server it belongs to, instead of `?api_key=` in the url.
- **ffmpeg**: every input gets a `protocol_whitelist`: local files may only use `file`, streams
  only `https,tls,tcp` (or `http,tcp` for a local http server). a crafted media file can't make
  ffmpeg open other files (concat, subfile, hls) or reach the network.

### where things live

| | linux | windows |
| --- | --- | --- |
| settings | `~/.config/dmt/dmt.properties` | `%APPDATA%\dmt\dmt.properties` |
| playlists | `~/.local/share/dmt/playlists/*.m3u8` | `%APPDATA%\dmt\playlists` |
| cache (library, covers) | `~/.cache/dmt` | `%LOCALAPPDATA%\dmt\cache` |

## build

needs jdk 21 (jpackage included). with a distro jdk the bundled runtime may need a newer glibc
than older distros have, so for release packages point `dmt.javaHome` at a portable
[temurin](https://adoptium.net) jdk.

```bash
./gradlew test                                   # 75 tests (metadata, lyrics, desktop)
./gradlew :desktop:run                           # run from source
./gradlew :desktop:linuxPackages -Pdmt.javaHome=/path/to/temurin-21   # .deb .rpm .tar.gz → desktop/build/dist
./gradlew :desktop:packageReleaseMsi :desktop:packageReleaseExe      # on windows
```

cross-building the portable windows zip from linux (needs a windows and a linux temurin of the
same version):

```bash
./gradlew :desktop:fatJar -Pdmt.target=windows-x64
packaging/windows/portable.sh <windows-temurin> <linux-temurin> 1.4.0 desktop/build/dist
```

`.github/workflows/release.yml` builds everything on push and attaches it to a release for
`v*` tags.

## português (resumo)

port do dmt v1.4.0 (android) para linux (fedora, debian) e windows. instale o `.deb`
(`sudo apt install ./dmt_*.deb`), o `.rpm` (`sudo dnf install ./dmt-*.rpm`) ou, no windows,
o `.msi`/`.exe` ou o zip portátil (`dmt.exe`). na primeira execução escolha a pasta de músicas.

## license

GPL-3.0, same as dmt. made by jyotiraditya panda; desktop port on top of it.
