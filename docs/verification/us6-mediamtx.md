# US6 MediaMTX delay and viewer verification

## Result

On 2026-08-13, one controlled API 36 emulator run passed the production publication path:

`StreamSessionController` -> `DelayedAccessUnitQueue` -> `RtmpStreamPublisher` -> pinned
MediaMTX v1.15.5 -> RTMP `ffprobe` reader and WebRTC browser reader.

This is a synthetic, video-only transport verification. It does not represent a TikTok account,
TikTok ingest, a physical phone, an internet route, or source-to-browser glass-to-glass latency.

## Reproduction

The ignored MediaMTX cache is populated and verified by:

```sh
tools/mediamtx/fetch-pinned-mediamtx.sh
```

The exact one-shot verification command was:

```sh
ANDROID_SERIAL=emulator-5554 \
LIVESHIELD_RTMP_API36_INTEGRATION=true \
ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
tools/mediamtx/run-api36-rtmp-integration.sh
```

The runner requires an API 36 emulator, uses `10.0.2.2` only for emulator-to-host RTMP, starts
the checksum-verified local MediaMTX binary, runs exactly
`com.liveshield.transport.RtmpApi36IntegrationTest`, and cleans the test package and every
test-owned process within bounded deadlines.

Pinned relay archive:

- Release: MediaMTX v1.15.5, macOS arm64, MIT license.
- Archive size: 23,200,334 bytes.
- Archive SHA-256: `116150e6900ed2ae845cf5113fab8007ad639a36d80c1a09c56e091ddcc5b907`.
- Executable SHA-256: `77e8f24ce5fea5f0b8e69727cc5f5ded5cd09645096ec8c28532ae96c6be6e4a`.

## Configured versus observed queue delay

The fixture used the public production `StreamSessionController`, not the lower-level publisher.
Each copied sanitized H.264 unit received a presentation timestamp from the same Android monotonic
clock used by the production queue. Delay was measured after a successful publisher handoff as:

`release monotonic time - access-unit presentation time`.

| Quantity | Result |
|---|---:|
| Configured delay | 2,000.000 ms |
| Released units | 76 |
| Minimum observed release delay | 2,000.401 ms |
| Mean observed release delay | 2,010.364 ms |
| Maximum observed release delay | 2,028.577 ms |
| Minimum scheduling overhead above configuration | 0.401 ms |
| Mean scheduling overhead above configuration | 10.364 ms |
| Maximum scheduling overhead above configuration | 28.577 ms |

All 76 observed releases met or exceeded the configured two-second delay. The run reports
count/minimum/mean/maximum, not a per-unit percentile distribution. This measurement ends at the
publisher handoff; it is not a claim about viewer presentation time.

## RTMP track and packet evidence

The API 36 instrumentation passed 1/1 in 18.418 seconds. MediaMTX independently reported the
publisher and reader on `liveshield` with one H.264 track. `ffprobe` observed:

| Assertion | Result |
|---|---:|
| Streams | 1 |
| Video codec | H.264 |
| Video packets sampled | 15 |
| Packets on stream 0 | 15/15 |
| Audio tracks or audio packets | 0 |

This proves that the sampled relay output was video-only. It does not prove behavior of an external
platform after it receives the stream.

## Browser viewer evidence

A controlled headless Google Chrome session opened
`http://127.0.0.1:8889/liveshield` through Playwright while publication was active. The page returned
successfully, its video element played, and the captured browser state was:

| Browser observation | Result |
|---|---:|
| `readyState` | 4 (`HAVE_ENOUGH_DATA`) |
| `currentTime` | 0.221 s |
| Decoded dimensions | 160 x 90 |
| Paused | false |

MediaMTX separately logged that the WebRTC peer connection was established and was reading one
H.264 track. Browser `currentTime` only proves playback advanced; the fixture does not embed a
visible or decodable wall-clock marker, so it cannot support a source-to-browser latency claim.

## Evidence integrity and cleanup

The run output was retained locally under
`transport/build/reports/rtmp-api36/run.D9H6ud/`. That build directory is intentionally ignored and
may be cleaned, so the durable evidence below records the exact file hashes and material results.

| File | SHA-256 |
|---|---|
| `delay-logcat.txt` | `24790761658a31030fa86524b6b3081c05a60371b5376d5965770f751172c8f0` |
| `ffprobe.txt` | `d87ee1647fcc447c177ee121e157971636c960aa61233b5a627a022519166c47` |
| `install.txt` | `781a31c82430c97897af612ece5e0ca2205e301cb5ff121ce2660541ea5182c7` |
| `instrumentation.txt` | `516c7ee3312ae15bff1d0c396f7121dd4d59eaddf66d9ff58e485e2a3cf090c5` |
| `mediamtx.log` | `12e7c9235c4c245cd0171a0cd96b98dcf1a6d9a00851932bf5c7c5f1adb55735` |
| `viewer.json` | `fa2f010391228af498fcb478761c2d27bcb280101bcbed32dbba484b318559fa` |

After the runner exited, port 1935 had no listener, no MediaMTX, browser, instrumentation, or
orchestrator process remained, and `com.liveshield.transport.test` was absent from the emulator.
