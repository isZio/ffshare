# zShrink

A private fork of [FFShare](https://github.com/caydey/ffshare): compress images, videos and
audio with FFmpeg, then save the result to your gallery or to a folder you pick.

> **This is a modified fork, not the original app.** It is based on FFShare by
> [caydey](https://github.com/caydey/ffshare), released under GPL-3.0, and stays under the same
> license. Package name: `com.ziomo.zshrink`.

## Changes compared to upstream FFShare

- Rebranded to **zShrink** (app name, package id and theme)
- **Sharing the compressed file out of the app was removed** — results are saved instead
  (automatic save with a configurable destination, or an explicit "save" action)
- Automatic save after compression, to the system gallery (`Movies|Pictures|Music/zShrink`) or
  to a folder picked via SAF
- Manual cache cleanup, and a single one-shot cleanup alarm instead of the old 12-hour
  repeating wake-up
- Hardware (MediaCodec) H.264/H.265 encoding options, with automatic software fallback
- Default encoder pinned to `libx264`: upstream let FFmpeg pick `h264_mediacodec`, which
  rejects yuv420p input on many devices and made compression fail
- Stall watchdog: a session that reports no progress for 5 minutes is cancelled, while long
  but healthy compressions are never killed by a fixed deadline
- Compression keeps running while the app is in the background (foreground service) and posts
  a notification when it finishes
- Progress bar with remaining time; result dialog shows size ratio, elapsed time and can open
  the saved file
- Offline settings guide (bundled HTML)
- Bug fixes: AAC spelling, worker-thread Toast crash, Exif copy crash, settings icon-space
  padding

## Requirements

- Android 8.0 (API 26) or newer
- `arm64-v8a` only

## Building

```bash
./gradlew assembleDebug
# or, for a minified build
./gradlew assembleRelease
```

FFmpeg comes from the maintained Maven artifact
`dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl` (same package and API as ffmpeg-kit).

## Used libraries

- ffmpeg-kit (maintained drop-in fork)
- [Timber](https://github.com/JakeWharton/timber)

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE).

Original project: FFShare by caydey — <https://github.com/caydey/ffshare> (GPL-3.0).
