# Shell access: Prism's embedded shell server

Prism runs a shell server of its own as the adb shell user (uid 2000). It is
derived from [Shizuku](https://github.com/RikkaApps/Shizuku) v13.6.0
(`NOTICE`) and speaks **Shizuku's binder interface** (API v13), so apps built
on `rikka.shizuku:api` / `rikka.shizuku:provider` can use it as they would
use Shizuku. No Shizuku app is installed: Prism plays Shizuku's manager and
asks the owner when an app wants the shell.

| Part | Where | What |
|---|---|---|
| Starter | `starter/starter.c`, built by `default.nix` | native executable for arm64 and armv7 (the TV), packaged as `lib/arm64-v8a/` and `lib/armeabi-v7a/libprism_starter.so` (uncompressed, 16 KB aligned, linked with `-z max-page-size=16384`); started over adb, it starts the server |
| Server | `../src/at/quickme/prism/shell/Server.kt` | runs in `app_process` as uid 2000 from Prism's APK (process name `prism_shell`); hands its binder to Prism and to every app that declares Shizuku's permission |
| Protocol | `Protocol.kt` | Shizuku's transaction codes and keys (`moe.shizuku.server.IShizukuService`), plus a few for Prism only |
| User services | `UserServices.kt` | Shizuku's `bindUserService`: an app's own Binder class in a process of its own as uid 2000 |
| API for Prism | `Shell.kt` | `Shell.available()`, `statusText()`, `run(argv…)`, `run(timeoutMs, argv…)`, `property(name)`, `listen/unlisten`, `openSetup(context)`, `askCluster()`, `pairCluster(activity)` |
| Apps | `ShellApps.kt`, `PermissionActivity.kt` | the owner's decisions per package; the prompt |
| Signal | `Signal.kt` | tells the cluster on the relay when the server is down |
| Pairing | `Pairing.kt` | "Pair with the cluster": opens Wireless debugging, takes the pairing code in a notification, finds the pairing port and sends both to the cluster |
| Hand-over | `ShellProvider.kt` | provider `at.quickme.prism.shell`, reachable only with `INTERACT_ACROSS_USERS_FULL` and callable only by uid 2000 |
| Screen | `ShellActivity.kt` | "Shell access", from the Phone tab's Shell row |

The server can't start itself, and Android ends it at every reboot. The
cluster's **shizuku-bootstrap** (selfhosted-platform) starts it over adb,
through NetBird, whenever Prism reports it down. Prism makes no adb
connection of its own; for pairing it only hands the cluster the pairing
port and code (below), and the cluster pairs its own key.

## Contract with the cluster

### Start

The cluster runs exactly this command, and only this (plus the check below),
passed as a single argument to `adb -s <phone>:<port> shell` (from an argv
list, not through a local shell):

```sh
sh -c 'p=$(pm path at.quickme.prism | sed -n "s/^package:\(.*\/base\.apk\)$/\1/p"); exec "${p%/base.apk}/lib/arm64/libprism_starter.so"'
```

- Prism sets `extractNativeLibs`, so the package manager extracts the
  starter to `<install dir>/lib/arm64/`. `pm path` names the current install
  dir, so the command survives updates (the dir changes with each).
- The starter must run as uid 2000. It kills an older `prism_shell`, starts
  `/system/bin/app_process -Djava.class.path=<base.apk> /system/bin
  --nice-name=prism_shell at.quickme.prism.shell.Server` in a new session (it
  outlives the adb session), waits until it has run for 2 s, prints
  `info: prism_shell pid is <pid>` and exits 0.
- Exit codes: 0 started, 3 CLASSPATH, 4 fork, 5 app_process can't start,
  6 not uid 2000, 7 Prism's APK not found, 8 the server exited at once,
  9 an old server can't be killed.
- Check: `pidof prism_shell` (exit 0 = running). The cluster counts the start
  as done on exit 0 followed by a successful `pidof`.
- Then the cluster runs `adb -s <phone>:<port> tcpip 5555`: adbd restarts and
  listens on TCP 5555 on every interface, NetBird's included, until the next
  reboot (the server, in its own session, survives adbd's restart). From then
  on the cluster tries `<NetBird IP>:5555` first and falls back to the
  wireless-debugging TLS port. The cluster's adb server keeps that
  connection and is the agents' adb as well: on the owner's dev hosts
  `phone-adb` (fleet) reaches it through `kubectl port-forward`, so the phone
  trusts the cluster's key only. Per-host
  `adb connect david-pixel.netbird.cloud:5555`, with a key per host, is no
  longer the way.
- The server exits by itself when Prism is updated or removed (its APK is
  gone); Prism then reports it down and the cluster starts the new one.

### Signal: relay topic `prism-shell`

On `https://ntfy.quickme.at`, written only by the phone (its existing relay
user, `relay_token`), read only by the cluster's `shizuku-bootstrap` user.
Body:

```json
{"v":1,"phone":"david-pixel","state":"down","reason":"boot","wifi":true,"netbird":true,
 "adb_wifi":true,"adb_port":37123,"adb_tcp":null,"app_version":1794537146,"time":1794537999}
```

| Field | Meaning |
|---|---|
| `phone` | the phone's name (`device_name`, default `david-pixel`); the cluster ignores other phones |
| `state` | `down`: Prism has no server; `up`: the server handed Prism its binder; `pair`: pair the cluster's key (below) |
| `reason` | `boot`, `updated`, `no-server` (no server within 20 s of Prism's start), `died`, `wifi` (joined a Wi-Fi network), `netbird` (NetBird connected), `port` (found or new wireless-debugging port), `front` (Prism came to the front), `keepalive`, `request` (the owner tapped "Ask the cluster now"); `started` with `up` |
| `wifi` | the phone is on a Wi-Fi network (wireless debugging needs one) |
| `netbird` | an interface has a NetBird address (100.64.0.0/10); `null` if unknown |
| `adb_wifi` | `Settings.Global adb_wifi_enabled`; `null` if Prism may not read it |
| `adb_port` | with `down` and `pair`: wireless debugging's TLS port, from `service.adb.tls.port` if readable, else from mDNS (`_adb-tls-connect._tcp`, this phone's own addresses only, as Shizuku's manager finds it); `null` if unknown (the cluster then scans), and always with `up` |
| `adb_tcp` | `5555` when `service.adb.tcp.port` reads 5555 (through the server when it runs, else SystemProperties or getprop if an app may read it), else `null` |
| `app_version` | Prism's version code |
| `time` | Unix seconds |

- **When**: `down` when the server dies (at once), when no server reached
  Prism within 20 s of its process start, on joining Wi-Fi (after 10 s), when
  NetBird connects, when the port is found or changes, when Prism comes to
  the front (at most every 2 min), on the owner's request, and every 5 min
  while down and the phone has a network (an allow-while-idle alarm); a
  `down` that can't reach the relay is retried every minute. `up` once when
  the server attaches; nothing periodic while up.
- The newest message is the current state. While down, a new message comes
  at least every 5 min (the keepalive); a `down` much older than that means
  the phone is offline.
- **Wake**: just before every `down` (and its retries), Prism sends one UDP
  datagram (5 bytes, port 9) to each cluster node: the A records of
  `telemetry.quickme.at` that are NetBird addresses (100.64.0.0/10), or, if
  DNS gives none, 100.123.47.63, 100.123.155.242 and 100.123.98.20. NetBird's
  lazy connections wake an idle tunnel only from the sending side, so this
  brings the phone↔node tunnels up for the cluster's adb connection. Nothing
  is sent while `up`.
- The message is not signed: it can only make the cluster try the fixed start
  command against the phone's NetBird IP from the cluster's own config, on a
  port 1024..65535, or pair its key there with a code the phone shows.
- Nothing goes back to the phone; it learns the outcome from its own `up`.

### Pair: `state` `pair`

Once per phone, and after a reset: the owner taps "Pair with the cluster" on
the Shell access screen, as Shizuku's manager pairs on Android 11+
(`Pairing.kt`):

1. Prism turns Wireless debugging on (`WRITE_SECURE_SETTINGS`, on Wi-Fi),
   opens Developer options at Wireless debugging
   (`:settings:fragment_args_key` `toggle_adb_wireless`) and posts a
   notification (channel "Pairing with the cluster", high importance) with a
   reply field, "Enter code".
2. The owner taps "Pair device with pairing code". Prism finds the dialog's
   pairing service by mDNS (`_adb-tls-pairing._tcp`, this phone's own
   addresses only) and alerts again ("Type the pairing code"), so the
   notification shows up over the dialog. The owner types the six digits
   into it without leaving the dialog, which would end the code.
3. Prism checks the code (six ASCII digits; spaces and dashes dropped), waits
   up to 15 s for the port if it hasn't found it yet, wakes the cluster
   nodes (as for `down`) and sends

   ```json
   {"v":1,"phone":"david-pixel","state":"pair","reason":"pair","wifi":true,"netbird":true,
    "adb_wifi":true,"adb_port":37123,"adb_tcp":null,"app_version":1794537146,
    "pair_port":40917,"pair_code":"123456","time":1794537999}
   ```

   with the header `Cache: no`: the relay passes it to the subscriber that is
   connected and keeps no copy, so it is never replayed. The code is never
   logged (Prism logs only `told the cluster: pair (pair)`) nor stored; it
   lives in the reply's Intent and in memory until it is sent.
4. The cluster (shizuku-bootstrap) takes it only from its own phone, only
   live and at most 2 minutes old, with `pair_port` 1024..65535 and six
   digits, and only once; it runs `adb pair <NetBird IP>:<pair_port>` with
   the code, then connects and starts the server as on `down`.
5. Prism shows "Paired" (screen and notification) when a server attaches
   after the code went out, or "Pairing with the cluster didn't finish" with
   "Try again" after 90 s. Without a code for 10 minutes it gives up
   quietly; Cancel on the notification ends it.

ntfy ACL (in selfhosted-platform's ntfy-auth Sealed Secret, maintained by the
owner): the phone's user `write` on `prism-shell`; the user
`shizuku-bootstrap` `read` on `prism-shell`.

### Wireless debugging after a reboot

Android turns Wireless debugging off at every boot and whenever the phone
leaves the Wi-Fi network. As Shizuku's start-on-boot does, Prism turns it back
on (`adb_wifi_enabled` = 1) when the server is down and the phone is on Wi-Fi,
with `WRITE_SECURE_SETTINGS`, which enrollment grants (`android/enroll.py`,
`pm grant`). Android re-enables it only on networks allowed once with "Always
allow on this network". Prism also sets `adb_allowed_connection_time` = 0
(Developer options › "Disable adb authorization timeout"), so the cluster's
key doesn't expire while the phone runs for weeks without a reboot.

## Shizuku's API for other apps

The server implements `moe.shizuku.server.IShizukuService` as Shizuku v13.6.0
does (Shizuku-API's `aidl/` and `server-shared/`): reported as version 13,
patch 6.

| Call | Here |
|---|---|
| `getVersion`, `getUid`, `getSELinuxContext`, `checkPermission` | as in Shizuku (13, 2000, `u:r:shell:s0`, the shell's permissions) |
| `newProcess` (`IRemoteProcess`) | `Runtime.exec` as uid 2000; streams, `waitFor`, `waitForTimeout`, `exitValue`, `destroy`, `alive`; the process is destroyed when the app's process dies |
| `transactRemote` (code 1, `ShizukuBinderWrapper`) | forwards the transaction to the given binder (a system service) as uid 2000 |
| `getSystemProperty`, `setSystemProperty` | `android.os.SystemProperties` as uid 2000 |
| `attachApplication` (v13, and the pre-13 code 14) | the app's package is checked against its uid; `bindApplication` gets uid, version, SELinux context, patch and whether it is allowed |
| `requestPermission`, `checkSelfPermission`, `shouldShowRequestPermissionRationale` | Prism's prompt; the answer goes back through `dispatchRequestPermissionResult` |
| `addUserService`, `removeUserService` | user services, as Shizuku: keyed by package and tag (or class), version codes, daemon or not, `noCreate` peeks, `remove` false only drops the connection; the process is `app_process` from Prism's APK (`UserServiceStarter`) that loads the app's class from its APK (constructor with a `Context`, or none), named `<package>:<processNameSuffix>`; Shizuku's destroy transaction on removal; removed when the app is updated or uninstalled, or when the owner revokes it |
| `exit`, `attachUserService`, `dispatchPermissionConfirmationResult`, `getFlagsForUid`, `updateFlagsForUid`, `dispatchPackageChanged` | Prism only (the manager) |
| `isHidden` | always false |
| rish, 32-bit `app_process`, users other than 0 | not offered |

**Getting the binder.** As Shizuku does, the server calls each app's
`ShizukuProvider` (authority `<package>.shizuku`, method `sendBinder`, the
binder in a `moe.shizuku.api.BinderContainer` under
`moe.shizuku.privileged.api.intent.extra.BINDER`) for every app that declares
`moe.shizuku.manager.permission.API_V23`: when the server starts (after Prism
took its binder), whenever such an app's process starts (an `IUidObserver`,
as Shizuku's `BinderSender`), and when such an app is installed or updated
(`getChangedPackages`, every 30 s of uptime). Shizuku itself is not
installed, so the permission is only declared, never granted; it serves to
find the apps.

**Permission.** Calls other than the attach and permission ones need the
owner's yes. `requestPermission` from an app without a decision makes Prism
post a notification ("<app> asks for shell access", Deny on it) whose screen
(full screen while the phone is in use, never over the lock screen) has
Allow and Deny. The answer is remembered per package in Prism's preferences
and handed to the server each time it attaches. "Shell access" lists the
apps; Revoke stops the app (force-stop, as Shizuku does) and ends its user
services; Forget lets a denied app ask again.

## The owner's steps

Once, when the cluster's shizuku-bootstrap is ready:

1. Settings › About phone › tap Build number seven times (Developer options).
2. Connect to the home Wi-Fi and NetBird. Settings › System › Developer
   options › Wireless debugging: on, and tick "Always allow on this network".
3. Pair the cluster, all on the phone: Prism › Phone tab › Shell › "Pair
   with the cluster". Settings opens at Wireless debugging: tap it, then
   "Pair device with pairing code", and type the six-digit code into Prism's
   notification (pull the shade down, or the heads-up; "Enter code"),
   leaving the dialog open. The fallback without Prism is the cluster's
   `pair` command (selfhosted-platform's shizuku-bootstrap README).
4. The cluster pairs, starts the server within a minute and turns on adb over TCP
   5555. "Pair with the cluster" says "Paired". Prism's "Shell access" screen then says "running as uid 2000" and
   "adb over TCP 5555: On"; "Test: run id" shows `uid=2000(shell)`.
5. Nothing per host: the cluster's key is the only one to approve. Agents
   use `phone-adb` (fleet; `phone-adb shell`, `logcat`, `install x.apk`,
   `status`), which goes through the cluster's adb server. If the phone asks
   to allow another computer's key, deny it.

After every reboot: nothing but unlocking the phone once on an allowed Wi-Fi
with NetBird up. Prism turns Wireless debugging on and reports down with the
port; the cluster starts the server and turns TCP 5555 on again. If it isn't
back after a few minutes: open "Shell access" and tap "Ask the cluster now";
if Wireless debugging is still off there, turn it on by hand.

On another Wi-Fi network: turn Wireless debugging on once there and tick
"Always allow on this network".

## Using it from Prism

```kotlin
// off the main thread
if (Shell.available()) {
    val r = Shell.run(30_000, "dumpsys", "batterystats", "--charged")
    if (r.ok()) parse(r.stdout)        // r.exitCode, r.stderr, r.timedOut, r.truncated
}
Shell.listen(runnable)    // on the main thread at every change; Shell.unlisten(runnable)
Shell.statusText()        // one line for the screens
Shell.openSetup(context)  // the "Shell access" screen
Shell.askCluster()        // tell the cluster now, while the server is down
Shell.pairCluster(activity) // "Pair with the cluster": Wireless debugging and the code's notification
```

`run` takes an argv (no shell; `"sh", "-c", "…"` for one), runs it in `/`
with empty stdin through Shizuku's `newProcess`, and destroys it at the
timeout (`timedOut`, `exitCode` -1). It throws `IOException` when the server
isn't running or dies meanwhile. Output is cut at 32 MiB per stream
(`truncated`). The server destroys Prism's commands when Prism's process
dies. The metrics don't need the shell: enrollment grants DUMP.
