# The phone's hotspot

Prism runs the phone's Wi-Fi hotspot in one of two modes, switched with
Prism's Quick Settings tile ("Hotspot"):

| Mode | The hotspot | Who may join |
| --- | --- | --- |
| **Instant** (default) | Off. It comes on when the owner's MacBook asks over Bluetooth LE, and goes off again 120 s after the last client left | One client |
| **Normal** | On, and it stays on: no idle timeout, and Prism starts it again after a reboot or when it stops | Everyone with the password, as configured in Settings |

The tile is active while the hotspot is on in either mode ("Always on", or
"Instant · on" while an instant hotspot runs) and inactive while an instant
one is off ("Instant"). A tap turns it off or on: Always on goes to instant
mode (off), a running instant hotspot goes off at once, and an idle one goes
to Always on. A long press opens the hotspot page (also on the Phone tab):

- **Keep it on**: the mode; in instant mode, **Turn on now** (an instant
  hotspot, as if the Mac had asked) or **Turn off now**.
- **Connected devices**: host name, IPv4 and MAC address of each device on
  the hotspot, every 3 s while the page is open (the tethering module's
  `TetheredClient`s, which it tells only a caller with `NETWORK_SETTINGS`).
- **Network**: name, security, password, band (2.4 GHz, 5 GHz, 2.4 and
  5 GHz at once, 6 GHz, as this phone offers them) and hidden network. Saving
  writes Android's persisted hotspot configuration, as Settings does, and
  restarts a running Always-on hotspot with it; 6 GHz takes WPA3. An instant
  hotspot uses the new name and password from its next start (and still picks
  its own band).

Both modes use the hotspot's own name and password from Android's Settings,
so the Mac joins a network it already knows (macOS auto-join). Instant mode
starts it on a single band, never bridged dual-band, at most 80 MHz wide:
6 GHz where it can, else 5 GHz, which finish transfers sooner, and 2.4 GHz
while the phone's own Wi-Fi sits on a 5 GHz channel the hotspot can't share
(such as a DFS channel). One band keeps one radio, not two. Since 6 GHz takes
WPA3-only security, an instant hotspot uses WPA3 (SAE) with the same
password, whatever Settings has (WPA2 or WPA2/WPA3); the Mac joins it from
the same known network. An open hotspot stays open, without 6 GHz. Starting
never changes the phone's persisted hotspot configuration (only the page's
Save does); each start carries its own configuration
(`TetheringRequest.setSoftApConfiguration`).

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
- **Joining** is the Mac too. While it asks (also with Bluetooth off: a
  normal-mode hotspot is on anyway), it scans for the hotspot's names every
  5 s (every 30 s after 3 minutes) and joins the strongest itself (CoreWLAN),
  as the known network macOS keeps, else with a password saved in Prism's
  Wi-Fi tab (login keychain). macOS's own auto-join scans slowly while
  offline and never leaves a network it is on, even a broken one, so asking
  from the menu used to start the hotspot without the Mac moving to it. The
  names are the popout's **Wi-Fi tab** ("The phone's hotspot"); a network
  whose DHCP lease carries Android's vendor option 43 `ANDROID_METERED` is
  added there on its own. macOS's configd keeps only the DHCP options it
  asked for, never 43, so Prism reads the lease with `ipconfig getpacket`.
- **Leaving** for a better Wi-Fi is the Mac too, for reliable networks only:
  the Wi-Fi tab's "Reliable networks", which the user adds and removes. With
  "Add networks with working internet automatically" on, Prism adds each
  Wi-Fi network on which it saw working internet
  (`http://captive.apple.com/hotspot-detect.html` answers "Success"), never a
  hotspot and never one the user removed. On a hotspot (the phone's, or a
  Wi-Fi macOS marks expensive, such as another phone's) it scans every 60 s
  (CoreWLAN, without leaving the hotspot; network names need Location
  permission for Prism, one prompt). When a reliable network shows up at
  -70 dBm or better, the Mac joins it and probes it 15 s later: with working
  internet it advertises a release for 30 s, and the phone switches its
  instant hotspot off at once (not after 120 s); without, it skips that
  network for 30 minutes and asks for the hotspot again (and joins it). If
  macOS refuses to join the network, the release alone does it: the phone
  switches its hotspot off and macOS auto-joins the best known network. Two
  failed probes in a row (30 s apart) on a reliable network or another
  hotspot also ask the phone and join its hotspot. A normal-mode hotspot is
  never released.

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
