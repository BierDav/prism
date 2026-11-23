# Enrolling the phone

The owner's phone (`david-pixel`) runs Prism as its **Device Owner**: there is
no MDM and no second app. Prism uses the role only for itself (`android/src/…/Dpc.kt`).
It grants its own runtime permissions, makes its NetBird the always-on VPN
once (without lockdown), and installs its own updates without a tap. It sets
no policies or restrictions. It can't wipe, lock or locate the phone, and the
phone stays a normal personal phone.

Where things come from:

| What | First time | Later |
| --- | --- | --- |
| The APK | `prism-phone-enroll` over USB (david-mb) | The Prism workers (`GET /v1/android/release`), published by fleet's `prism-android-publish` on david-mm |
| Settings and secrets | `prism-phone-enroll` writes them through adb's stdin | The phone's replica (`android.settings.david-pixel`, which the workers keep from `PUT /v1/android/settings`), same publisher; `prism-phone-enroll --settings-only` still works |
| Prism's data | `rocky_provisioning` and `rocky_peers`, written with the settings | The phone's own Rocky replica, synced with the workers |
| NetBird setup key, `prism_url`, `rocky_provisioning`, `rocky_peers` | `prism-phone-enroll` only | — |

Prism's alerts, agents, questions and approvals are the phone's own replica of
Prism's Rocky database (docs/prism-api.md, "Identity and enrollment"):
`rocky_provisioning` is the phone's provisioning (`prism-worker provision
david-pixel`, a secret in sops) and `rocky_peers` the workers' contacts
(`prism-worker peer`, one per line). Each worker lists the phone in its
`PEERS` with the route `127.0.0.1:9`.

Every value comes from fleet (`hosts/prism-phone.nix` and sops). None is ever
on a command line, in logcat or in a file on the phone.
`docs/prism-api.md` ("The Android app") has the endpoints.

## Enrolling a phone

Android makes an app the Device Owner over adb only while no account is on
the phone. A phone with another Device Owner (an MDM's agent) needs a factory
reset first, because no other app can take the role over.

Before you start:

- fleet is deployed on david-mb (`prism-phone-enroll` exists) and on
  david-mm (its `prism-android-publish` has published the current
  version).
- `sops/android-mdm/netbird-setup-key.yaml` (key `setup-key`) in fleet holds
  an **unused** one-time NetBird setup key that puts the peer in the
  **Developers** group (the "Devs to David Server Nodes" policy connects it to
  the cluster nodes). In the NetBird dashboard, delete the phone's old peer.
- The phone is on **Wi-Fi**. On mobile data, large packets can be dropped
  silently (seen on the owner's carrier: nothing above about 1,100 bytes), and
  NetBird's TLS login then times out.
- If the phone will be reset: a fresh Google One backup (Settings › System ›
  Backup › Back up now). Make sure authenticator apps and chats are exported
  or backed up by their own apps.

Steps:

1. *(Reset only)* Factory reset (Settings › System › Reset options › Erase all
   data). In the setup wizard, sign in to Google and restore from the backup.
2. Remove every account (Settings › Passwords, passkeys & accounts), Google
   included. Accounts are needed only for the restore.
3. Turn on Developer options and USB debugging. Plug the phone into david-mb
   and allow it.
4. On david-mb, run `prism-phone-enroll`. It checks that no other app is the
   Device Owner and that there are no accounts, and stops before changing
   anything if either check fails. It then:
   - installs Prism (signed with the release key);
   - runs `dpm set-device-owner at.quickme.prism/.DeviceAdmin`;
   - writes the settings and checks every value arrived (by hash);
   - prints Prism's status, with the phone's device key and NetBird.
5. Wait about a minute for NetBird to join, then run
   `prism-phone-enroll --settings-only` again. It prints the NetBird IP.
   "Connected, 0 peers" means the peer is in no group a policy covers: add it
   to Developers.
6. Server side (selfhosted-platform; an agent can do it):
   - the device key goes into `manifests/alerting/prism-config.json` as
     `devices.david-pixel.key` (the workers check approvals with it);
   - the NetBird IP replaces the old one in
     `manifests/cliproxy/admin-access.yaml` and in shizuku-bootstrap's
     config (the phone's address and its policies).
7. Open Prism. Its replica opens and syncs (the Phone tab shows "replica
   open"), and the main screen shows "Owner: Prism is the Device Owner". "Check for updates" says "up to date".
8. Shell access (`android/shizuku/README.md`), once; a reset phone no longer
   trusts the cluster's adb key, and until it is paired again
   `phone-adb status` says the phone does not accept the cluster's key:
   - Developer options › Wireless debugging: on, on the home Wi-Fi, and tick
     "Always allow on this network" (Prism turns it back on after every
     boot only on networks allowed this way).
   - Prism › Phone tab › Shell › "Pair with the cluster". Settings opens
     at Wireless debugging: tap it, then "Pair device with pairing code",
     and type the six-digit code into Prism's notification ("Enter code",
     from the heads-up or the shade) while the dialog stays open. Prism
     sends the code and the pairing port to the cluster, which pairs its
     key (`android/shizuku/README.md`, "Pair"). No computer is needed; the
     cluster's `pair` command (selfhosted-platform's shizuku-bootstrap
     README) remains the fallback.
   - Within a minute the cluster starts the shell server and turns on adb
     over TCP 5555, and "Pair with the cluster" says "Paired". Phone tab › Shell › "Shell access" says "running as uid
     2000" and "adb over TCP 5555: On"; "Test: run id" shows
     `uid=2000(shell)`.
   - That is the only key to approve: agents use the cluster's adb server
     through `phone-adb` (fleet, on david-mb, david-server and david-tp:
     `phone-adb shell`, `phone-adb logcat`, `phone-adb install x.apk`,
     `phone-adb status`). Per-host `adb connect
     david-pixel.netbird.cloud:5555` is no longer the way; if the phone asks
     to allow another computer's key, deny it.
   After every reboot the owner only unlocks the phone once on an allowed
   Wi-Fi with NetBird up; the cluster does the rest (TCP 5555 again
   included). Prism keeps adb authorizations from expiring.
9. Add the accounts back. USB debugging can be turned off (Wireless
   debugging stays on).

## Rotating a setting

Change the secret in sops (or the value in `hosts/prism-phone.nix`), push
fleet and let david-mm deploy. Its publisher uploads the settings to the
workers, which keep them in the replicated `settings` table; the phone reads
them from its replica when the leader pushes `app.settings` or the replica
syncs them. With the phone on USB, `prism-phone-enroll --settings-only` does
the same without the workers, and is the only way to change
`rocky_provisioning` or `rocky_peers` (which reopen the replica).

A TV (`--serial <ip>:5555`) gets no `rocky_provisioning`: every replica
holds all of Prism's data, and a TV only updates Prism.

## Giving the role up

The main screen's "Release device owner…" ends the role for good. After
that, Prism is an ordinary app: updates need a tap, and it can be
uninstalled. Only another enrollment brings the role back.
