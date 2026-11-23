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
  carries the call's audio both ways. When the call ends it exits.
- **Mac** (`macos/PhoneCalls.swift`, `macos/CallPanel.swift`): Prism for
  macOS listens on TCP port 47470 for the phone, shows the panel, and plays
  and records the call through the Mac's speakers and microphone with
  macOS's voice processing (echo cancellation).

If the connection to the Mac breaks, the call's audio goes back to the
phone. Nothing connects while there's no call: the phone's NetBird peer
stays idle.

## Setup

- Phone: setting `call_mac`, the Mac's host name (for example
  `david-mb.netbird.cloud`), and the same `hotspot_key` the hotspot uses.
  Unset, no bridge starts.
- Mac: fleet's `fleet.prism.calls` (config `"calls": true` with
  `hotspotKeyFile`), a firewall profile that lets Prism accept connections,
  and the microphone permission, which macOS asks for at the first call on
  the Mac (MDM can't grant a microphone).

## The connection (v1)

TCP from the phone to the Mac, port 47470, inside NetBird's WireGuard
tunnel. Every message is a frame: one byte type, a 2-byte big-endian length,
then the payload.

| Type | Name | Payload |
| --- | --- | --- |
| 1 | hello | Mac → phone: a 16-byte nonce `nm` |
| 2 | auth | phone → Mac: a 16-byte nonce `np` and `HMAC(K, "phone" ‖ nm ‖ np)`; Mac → phone: `HMAC(K, "mac" ‖ np ‖ nm)` |
| 3 | message | a JSON object (below), UTF-8 |
| 4 | audio | 20 ms of PCM, signed 16-bit little-endian, mono, 16 kHz (640 bytes) |

`K = HMAC-SHA256(hotspot_key, "prism-call/v1")`, so the call link never
uses the hotspot key itself. A side that gets a wrong MAC closes the
connection. Nothing but hello and auth is accepted before both MACs check
out.

Test vector (`core/CallLink.kt`, `tests/CallLinkTest.kt`, `prism
self-test`): with `hotspot_key` 00..1f, `nm` = 16 × 0xa1 and
`np` = 16 × 0xb2:

- `K` = `770eb5e989c18d4fbd66609b94934b6e218999c822a3477313a8e726c356b864`
- the phone's MAC = `16ceaf00645894bb0710f4daccb787f733b3b4c7b58433e4d46ec61bd67362d7`
- the Mac's MAC = `967350dc16f4d14828d8320272b3a6e9446f932f9265667dbfa2750445d08321`

Phone → Mac:

```json
{"type": "call", "state": "ringing", "number": "+43…", "name": "…",
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
