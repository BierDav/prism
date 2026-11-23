# The phone's hotspot

Prism runs the phone's Wi-Fi hotspot in one of two modes, switched with
Prism's Quick Settings tile ("Hotspot"):

| Mode | The hotspot | Who may join |
| --- | --- | --- |
| **Instant** (default) | Off. It comes on when the owner's MacBook asks over Bluetooth LE, and goes off again 120 s after the last client left | One client |
| **Normal** | On, and it stays on: no idle timeout, and Prism starts it again after a reboot or when it stops | Everyone with the password, as configured in Settings |

Both modes use the hotspot's own name and password from Android's Settings,
so the Mac joins a network it already knows (macOS auto-join). Instant mode
starts it on a single band, never bridged dual-band, at most 80 MHz wide:
5 GHz where it can, which finishes transfers sooner, and 2.4 GHz while the
phone's own Wi-Fi sits on a 5 GHz channel the hotspot can't share (such as a
DFS channel). One band keeps one radio, not two. The
phone's persisted hotspot configuration is never changed; each start carries
its own configuration (`TetheringRequest.setSoftApConfiguration`).

## Who does what

- **Starting and stopping** runs as the shell user (uid 2000), which holds
  `TETHER_PRIVILEGED` and `NETWORK_SETTINGS`: Prism runs a class of its own
  APK with `app_process` through its shell server (see the code in
  `android/src/at/quickme/prism/shell/`). The SoftApConfiguration setters are
  system APIs that an app can't call, so the Device Owner path of Android 16
  (`TETHERING_WIFI` with a configuration) doesn't help. The tool exits after
  each command: the tethering module keeps a request without watching the
  process that made it.
- **Listening for the Mac** is Prism's app process: a hardware-filtered
  Bluetooth LE scan (`BluetoothLeScanner.startScan` with a `PendingIntent`,
  `SCAN_MODE_LOW_POWER`), only in instant mode, while Bluetooth and location
  are on: for a request while the hotspot is off, and for a release while
  Prism's instant hotspot is on. It also runs while the phone is on Wi-Fi,
  since the Mac may not know that network and its menu asks whatever the
  path. The filter runs in the Bluetooth controller, alongside the low-power
  scan Android keeps for itself, so it costs next to nothing. Location: the scan
  doesn't disavow location (`neverForLocation` filters some advertisements),
  and Android delivers such a scan's results only while location is on.
- **Android 16** or newer on the phone: a tethering request with its own
  configuration and the public tethering callback are new there. Before, the
  tile still switches modes and nothing else happens.
- **Asking** is Prism for macOS: it advertises while the Mac has no network
  path (`NWPathMonitor` unsatisfied for 5 s), and for 3 minutes after "Ask the
  phone for its hotspot" in the menu. It stops as soon as a path is there
  again, which is the case once it has joined the hotspot.
- **Leaving** for a better Wi-Fi is the Mac too, for proven networks only.
  Prism for macOS remembers each Wi-Fi network (by name) on which it saw
  working internet (`http://captive.apple.com/hotspot-detect.html` answers
  "Success"), never the phone's hotspot (its DHCP lease carries Android's
  vendor option `ANDROID_METERED`). While on the hotspot it scans every 60 s
  (CoreWLAN, without leaving the hotspot; network names need Location
  permission for Prism, one prompt). When a proven network shows up at
  -70 dBm or better, it advertises a release for 30 s; the phone switches its
  instant hotspot off at once (not after 120 s), and macOS auto-joins the best
  known network. If the Mac has no working internet 20 s later, it asks for
  the hotspot again and skips that network for 30 minutes. A Mac has one
  Wi-Fi radio, so it can't test a network without leaving the one it's on;
  "proven" keeps such tries rare. A normal-mode hotspot is never released.

## The advertisement (v1)

The Mac advertises one 128-bit service UUID (macOS advertises nothing but
service UUIDs and a name):

```
bytes 0..3   51 52 28 f6                fixed prefix (the scan filter's mask)
bytes 4..15  HMAC-SHA256(key, "prism-hotspot/v1" || u64be(window))[0..12]
window       floor(unix seconds / 30)
```

`key` is 32 bytes, shared by the phone and the Mac: the phone's managed
setting `hotspot_key` and the Mac's `hotspotKeyFile` (fleet, sops
`sops/prism/hotspot.yaml`), both 64 hex characters. The Mac changes the UUID
at every window. The phone accepts the windows `w-2 .. w+1` around its own
clock, starts the hotspot at most once per 30 s for a request, and ignores
requests in normal mode. A replayed advertisement can do no more than switch
the hotspot on for 120 s; joining still takes the password.

A release is the same with the label `"prism-hotspot-release/v1"`. The phone
honours it only for a hotspot it started in instant mode, and at most once
per 30 s; a replayed release can do no more than switch that hotspot off,
after which the Mac asks again.

Without `hotspot_key` the phone doesn't scan; the tile still switches modes.
