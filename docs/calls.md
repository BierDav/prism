# Phone calls on the Mac

The phone's calls, cellular ones included, can ring, be answered and be
talked on at the owner's MacBook, and move between the Mac and the phone in
the middle of a call. The Mac shows a call panel with the caller, and
buttons to answer, decline, hang up, mute, and move the call to the Mac or
back to the phone.

## How it works

Android 13 added call audio interception for exactly this, the API behind
Google's call streaming (Cross-device services' "Call casting"):

- `AudioManager.getCallDownlinkExtractionAudioRecord(format)`: an
  `AudioRecord` with what the other party says (the downlink),
- `AudioManager.getCallUplinkInjectionAudioTrack(format)`: an `AudioTrack`
  whose audio the other party hears (the uplink),

for a cellular call while `AudioManager.isPstnCallAudioInterceptable()`. Both
take `CALL_AUDIO_INTERCEPTION` (`signature|privileged|role`). Google's role
for it, `SYSTEM_CALL_STREAMING`, grants it together with a
`CallStreamingService`, which Telecom binds only when a VoIP app asks to
stream its own call (`CallControl.startCallStreaming`); cellular calls never
go that way. These are system APIs, which a normal app's process may not
call (Android's hidden-API restrictions), so Prism doesn't take the role.
It runs the call bridge as the shell user instead, which holds
`CALL_AUDIO_INTERCEPTION` with everything else a call needs
(`READ_PHONE_STATE`, `READ_CALL_LOG`, `READ_CONTACTS`, `ANSWER_PHONE_CALLS`,
`MODIFY_PHONE_STATE`), like the hotspot tool (docs/hotspot.md).

- **Phone, Prism's process**: `Calls.kt` follows the call state
  (`TelephonyCallback`, `READ_PHONE_STATE`). When a call starts ringing or
  is placed, and the setting `call_mac` names a Mac, it starts the bridge
  through the shell server and waits for it.
- **Phone, the bridge** (`shell/CallBridge.kt`, `app_process` as uid 2000):
  lives as long as the call. It connects to the Mac, tells it the call's
  state, number and contact name, answers, declines and ends the call when
  the Mac says so (`TelecomManager`), and while the call is on the Mac it
  carries the call's audio both ways, over Bluetooth when it can (below).
  When the call ends it exits.
- **Mac** (`macos/PhoneCalls.swift`, `macos/CallPanel.swift`): Prism for
  macOS scans for the phone's Bluetooth advertisement and listens on TCP
  port 47470, shows the panel, and plays
  and records the call through the Mac's speakers and microphone with
  macOS's voice processing (echo cancellation).

If the connection to the Mac breaks, the call's audio goes back to the
phone. Nothing connects while there's no call: the phone neither advertises
nor connects, and its NetBird peer stays idle.

## Setup

- Phone: setting `call_mac`, the Mac's host name (for example
  `david-mb.netbird.cloud`), and the same `hotspot_key` the hotspot uses.
  Unset, no bridge starts.
- Mac: fleet's `fleet.prism.calls` (config `"calls": true` with
  `hotspotKeyFile`), a firewall profile that lets Prism accept connections,
  and the microphone permission, which macOS asks for at the first call on
  the Mac (MDM can't grant a microphone).

## The connection (v2)

The phone keeps one link to the Mac for the call, over the best transport
it has, and moves the call to a better one when it comes up:

1. **Bluetooth LE** (preferred): an L2CAP channel between the phone and the
   Mac. It needs no network at all, so it works when the phone's mobile data
   doesn't during the call (a SIM without data, a data switch to a SIM out of
   quota, 2G) and wherever the Mac is within Bluetooth range.
2. **TCP**, port 47470: the Mac's NetBird address (`call_mac`) and, at the
   same time, the phone's hotspot clients and its Wi-Fi and hotspot
   neighbours, directly. The first to pass the handshake wins.

The bridge advertises for Bluetooth and tries TCP at once; while a Bluetooth
link is up it makes no TCP attempts. A Bluetooth link that passes the
handshake replaces a TCP one (the Mac keeps the newest authenticated
connection). If the Bluetooth link breaks, the phone advertises again and
goes back to TCP. Audio moves with the link; the call stays where it is.

### Bluetooth

While the call lasts and no Bluetooth link is up, the phone listens on an
LE L2CAP channel (`listenUsingInsecureL2capChannel`, a PSM Android
assigns) and advertises it: a legacy, connectable advertisement without a
name, with

- one 128-bit service UUID, made like the hotspot's (docs/hotspot.md) but
  with the call key `K` (below; the bridge never sees `hotspot_key`) and
  the label `"prism-call-ble/v1"`: bytes 0..3 `51 52 28 f6`, bytes 4..15
  `HMAC-SHA256(K, "prism-call-ble/v1" ‖ u64be(window))[0..12]`,
  `window = floor(unix seconds / 30)`, new at every window;
- manufacturer data, company `0xffff`: the PSM, u16 little-endian.

The Mac scans for the UUIDs of the windows `w-1 .. w+1` around its clock,
connects to the phone, opens the L2CAP channel to the PSM, and from there
it's the same connection as over TCP: hello, auth, then encrypted frames.
The channel is not encrypted by Bluetooth (no pairing): the link's own
encryption below covers it, as it does TCP.

### Frames

Every message is a frame: one byte type, a 2-byte big-endian length, then
the payload.

| Type | Name | Payload |
| --- | --- | --- |
| 1 | hello | Mac → phone: a 16-byte nonce `nm` |
| 2 | auth | phone → Mac: a 16-byte nonce `np` and `HMAC(K, "phone" ‖ nm ‖ np)`; Mac → phone: `HMAC(K, "mac" ‖ np ‖ nm)` |
| 3 | message | a JSON object (below), UTF-8 |
| 4 | audio | 20 ms of PCM, signed 16-bit little-endian, mono, 16 kHz (640 bytes) |
| 5 | audio | the same 20 ms as IMA ADPCM (164 bytes, below) |

`K = HMAC-SHA256(hotspot_key, "prism-call/v2")`, so the call link never
uses the hotspot key itself. A side that gets a wrong MAC closes the
connection. Nothing but hello and auth is accepted before both MACs check
out.

After the handshake every frame (types 3, 4 and 5) is encrypted with
AES-256-GCM, a key for each direction:

- phone → Mac: `HMAC(K, "phone->mac" ‖ nm ‖ np)`,
- Mac → phone: `HMAC(K, "mac->phone" ‖ nm ‖ np)`,
- nonce: 4 zero bytes, then the frame's number in that direction as u64
  big-endian, from 0 for the first frame after the handshake,
- additional data: the type byte,

and the payload on the wire is the ciphertext followed by the 16-byte tag
(the length counts both). A frame that doesn't decrypt closes the
connection.

Audio is type 5 over Bluetooth (64 kbit/s each way, which an LE link
carries comfortably) and type 4 over TCP; both sides accept either on any
link. An ADPCM frame is a 4-byte header, the encoder's state before the
frame's first sample (predictor i16 little-endian, step index u8, a zero
byte), then 160 bytes of 4-bit codes for the 320 samples, the even sample
in the low nibble. The encoder is the standard IMA one with its 89 steps
and the index table `-1 -1 -1 -1 2 4 6 8`; for each sample:
`diff = s - predictor`, sign bit 8 if negative, then bits 4, 2, 1 against
`step`, `step/2`, `step/4`, with `delta = step/8` plus each step taken;
`predictor ± delta` clamped to 16 bits; `index + table[code & 7]` clamped
to 0..88. The decoder computes the same `delta` from the code. Each frame
carries its state, so a lost one costs only its own 20 ms.

Test vectors (`core/CallLink.kt`, `tests/CallLinkTest.kt`, `prism
self-test`): with `hotspot_key` 00..1f, `nm` = 16 × 0xa1 and
`np` = 16 × 0xb2:

- `K` = `851c91dae3b20db650e63a523064dbf9e605f57b3c7f0e1125b239ed9c0e47f7`
- the phone's MAC = `6d2840f6c2b6cb1bb98a9d9eea1e3580a827eadceea38372c3463c54a60ea3ef`
- the Mac's MAC = `0b26ccd7c0ed51269b5108d93ec61edf7ff01d2a29849ea842944800e3b263a7`
- phone → Mac key = `3c7eca885cb1f3c6ab0dcadbd210d4b07f7bb61e4469ca63319807b53023a43c`
- Mac → phone key = `e217c291c386519a354b1495f052bfa3ee077d449a732034e729b15ed389e0a1`
- Mac → phone frame 0, type 3, `{"type":"answer"}`: `1460883d130b6181c25fdaf6e9590c9ab3944501a65af93501ef0fa7e62e2e8b85`
- phone → Mac frame 1, the same: `82c2303e6af04e6dbef747805e1be4ecdecd0470cdb1f26aac9213a56f64603c11`
- the Bluetooth service UUID of window 59999999: `515228f6-c9ae-4dcc-2ab1-bb010abf3a07`

ADPCM: the samples `round(8000 · sin(2π · 440 · i / 16000))`, `i` = 0..639,
encoded from predictor 0, index 0 as two frames:

- frame 0 starts `0000000070777777378898aa`, SHA-256
  `e17b4bcf8c19563fdf44bfc80c0d852b8051669c7df1d8fa9b57eb7510953e96`
- frame 1 starts `5be132002163433443333212`, SHA-256
  `48e4a988cd7299c7a31c0549af4343cad970723df63ee885bb4a3a3fa3b84010`
- frame 1 decoded (320 × i16 little-endian), SHA-256
  `a1a915d51880d90d3115543ac3943552faf742a4a52d73a4f528513dbbb9cee6`

Phone → Mac:

```json
{"type": "call", "state": "ringing", "number": "+…", "name": "…",
 "audio": "phone", "since": 1795465104000}
```

`state` is `ringing`, `active` or `ended`; `number` and `name` may be
missing (an outgoing call has no number, a caller may have none in the
contacts); `audio` is `mac` while the call's audio is on the Mac; `since` is
when the call became active (Unix milliseconds). It's sent on connecting and
on every change; `error` (text) says why a command failed.

Mac → phone:

| Message | Does |
| --- | --- |
| `{"type": "answer"}` | answers a ringing call and moves it to the Mac |
| `{"type": "decline"}` | declines a ringing call |
| `{"type": "hangup"}` | ends the call |
| `{"type": "audio", "to": "mac"}` / `"phone"` | moves the active call's audio |

Audio frames flow both ways while `audio` is `mac`; the Mac sends silence
while it's muted. A side that falls more than 200 ms behind drops audio
until it has caught up.
