# Phone metrics

Prism on the phone pushes these as Prometheus remote write to the Prism
workers, `https://prism.quickme.at/v1/telemetry/write` (setting `prism_url`
plus the path; an explicit `telemetry_url` overrides it), with basic auth
user `prism_phone` (`telemetry_user`) and `telemetry_password`. The worker
checks that credential against its own copy and forwards the body unchanged
to ClickHouse's remote write handler in the cluster
(`worker/src/telemetry.rs`). They end up in ClickHouse
`observability.metrics` next to the cluster's and the dev hosts'.

The phone used to push to `https://telemetry.quickme.at/write`, which is
reachable only over NetBird. NetBird idles a peer after 15 minutes without
data, so a push every 15 minutes kept the phone's tunnel active for good,
and WireGuard's keepalives woke the modem every 25 s. Every series has `job="prism/phone"` and
`instance="<device name>"` (managed config `device_name`, default
`david-pixel`).

**When:** battery readings every 5 minutes, taken while the phone is awake
anyway for the push connection. Everything is pushed every 15 minutes
(Android's shortest periodic job) in one request, with each reading at its
own timestamp, but while the phone is unplugged with the screen off at most
every hour: the job then only takes its reading, as each push wakes the
radio. Readings wait on the phone (up to a day) between pushes and while
there is no network.

**Where from:**
- `BatteryManager`, `PowerManager` and `SystemClock` for the battery readings.
- `dumpsys batterystats --checkin` for the device and per-app counters,
  through the shell server (uid 2000). An app's own dumpsys gets no output on
  Android 17 even with DUMP granted (`pm grant`, `android/enroll.py`), so from
  Prism's reinstall as at.quickme.prism on 2026-11-20 the stats were missing
  while `phone_metrics_batterystats_available` still said 1; without the
  shell server they are missing (available 0, lines 0). Not `-c`: that one
  prints the whole battery history first (megabytes; Android 14+ keeps it
  across charges). `--checkin` prints the period Android saved at the last
  reset first, once, so after a charge the push reads it twice and keeps the
  newer. If `--checkin` gives no stats, it falls back to `-c` with a 60 s
  limit (`phone_metrics_batterystats_fallback` 1).
- `/proc/<pid>/stat` of every process, read as the shell user through the
  shell server (only it sees them all), and `/proc/self/task/*/stat` for
  Prism's own threads.

**Counters restart at every full charge.** That is how Android keeps battery
stats ("since last charge"). Use `increase()`/`rate()`, which handle resets,
and see `phone_batterystats_reset_timestamp_seconds` for when the current
period began.

## Battery readings (gauges, every 5 minutes)

| Metric | Meaning |
|---|---|
| `phone_battery_level_ratio` | Charge level, 0–1 |
| `phone_battery_current_amperes` | Current as the device reports it; Pixels report negative while discharging |
| `phone_battery_voltage_volts` | Battery voltage |
| `phone_battery_temperature_celsius` | Battery temperature |
| `phone_battery_charge_ampere_hours` | Charge counter (remaining charge) |
| `phone_battery_plugged` | 1 while on a charger |
| `phone_battery_charging` | 1 while charging |
| `phone_screen_on` | 1 while the screen is on (interactive) |
| `phone_power_save_mode` | 1 in Battery Saver |
| `phone_device_idle` | 1 in Doze |
| `phone_awake_seconds_total` | `SystemClock.uptimeMillis`: stops while the CPU sleeps (resets at boot) |
| `phone_since_boot_seconds_total` | `SystemClock.elapsedRealtime`: runs on in sleep (resets at boot) |
| `phone_battery_current_average_amperes` | The fuel gauge's average current, where the device has one |

A reading is only taken while the CPU is awake, so `phone_battery_current_amperes`
is the awake current, not the sleep current. The share of time the CPU was
awake is `rate(phone_awake_seconds_total[1h]) / rate(phone_since_boot_seconds_total[1h])`;
near 1 with the screen off means something keeps the phone from sleeping.

## Device, since the last charge (every 15 minutes)

| Metric | Labels | Meaning |
|---|---|---|
| `phone_batterystats_reset_timestamp_seconds` | | When the stats were last reset (full charge) |
| `phone_battery_capacity_estimated_ampere_hours` | | Android's estimate of the battery's capacity |
| `phone_on_battery_seconds_total` | | Time on battery |
| `phone_on_battery_screen_off_seconds_total` | | Time on battery with the screen off |
| `phone_screen_on_seconds_total` | | Screen-on time |
| `phone_interactive_seconds_total` | | Interactive time |
| `phone_mobile_radio_active_seconds_total` | | Mobile radio active |
| `phone_wifi_on_seconds_total`, `phone_wifi_running_seconds_total` | | Wi-Fi on / running |
| `phone_wakelock_seconds_total` | `type` = `partial`, `full` | Wakelock time, all apps |
| `phone_device_idle_seconds_total` | `mode` = `deep`, `light` | Time in Doze |
| `phone_power_save_seconds_total` | | Time in Battery Saver |
| `phone_mobile_signal_seconds_total` | `level` = `none`, `poor`, `moderate`, `good`, `great` | Time per mobile signal strength |
| `phone_data_connection_seconds_total` | `type` (`lte`, `nr`, …; only the ones that occurred) | Time per mobile data connection type |
| `phone_kernel_wakelock_seconds_total`, `phone_kernel_wakelocks_total` | `name` | Kernel wakelocks (wakeup sources), top 20 by time |
| `phone_wakeup_reason_total`, `phone_wakeup_reason_seconds_total` | `reason` | Why the SoC woke from sleep (an interrupt: modem, Wi-Fi, an alarm …), top 20 by count |
| `phone_wakelock_name_seconds_total`, `phone_wakelock_name_total` | `package`, `name` | App wakelocks by name (partial), top 20 by time |
| `phone_job_seconds_total`, `phone_jobs_total` | `package`, `name` | Scheduled jobs, top 20 by time |
| `phone_wakeup_alarm_name_total` | `package`, `name` | Alarms that woke the device, top 20 by count |

## Per app, since the last charge (every 15 minutes)

Each scrape includes only the top 25 apps by estimated charge, then by
wakelock, CPU, radio and network use, keyed by `package`. A uid shared by
several packages shows as `first.package+N`, and system uids get names like
`android.system`. The charge of every other app is in
`phone_app_estimated_charge_ampere_hours_total{package="other"}`.

| Metric | Labels | Meaning |
|---|---|---|
| `phone_app_estimated_charge_ampere_hours_total` | `package` | Android's estimated charge used (its "battery usage" figure) |
| `phone_app_cpu_seconds_total` | `package`, `mode` = `user`, `system` | CPU time |
| `phone_app_wakelock_seconds_total` | `package`, `type` = `partial`, `full`, `window` | Wakelock time (pooled, as Android attributes it) |
| `phone_app_wakelocks_total` | `package`, `type` = `partial` | Wakelocks acquired |
| `phone_app_wakeup_alarms_total` | `package` | Alarms that woke the device |
| `phone_app_radio_wakeups_total` | `package`, `network` = `mobile`, `wifi` | Times its traffic woke the CPU |
| `phone_app_mobile_radio_active_seconds_total` | `package` | Mobile radio active time attributed to it |
| `phone_app_wifi_running_seconds_total` | `package` | Wi-Fi running time attributed to it |
| `phone_app_wifi_scan_seconds_total` | `package` | Wi-Fi scanning |
| `phone_app_network_bytes_total` | `package`, `network` = `mobile`, `wifi`, `direction` = `rx`, `tx` | Bytes transferred |
| `phone_app_foreground_seconds_total` | `package` | Time with an activity in the foreground |
| `phone_app_foreground_service_seconds_total` | `package` | Time running a foreground service |
| `phone_app_process_state_seconds_total` | `package`, `state` = `top`, `foreground_service`, `foreground`, `background`, `top_sleeping`, `heavy_weight`, `cached` | Time per process state |
| `phone_apps_tracked` | | How many apps this push included |

## CPU per process and per thread (every 15 minutes)

Counted from when Prism's process started (so they restart with it) and
summed over the processes or threads of the same name, so a process that
restarts keeps counting. Only the top ones by total go out, without an
"other" (which would go down whenever the top changed).

| Metric | Labels | Meaning |
|---|---|---|
| `phone_process_cpu_seconds_total` | `process` | CPU time of every process of that name (an app's is its package, maybe `:service`), top 30; needs the shell server |
| `phone_prism_thread_cpu_seconds_total` | `thread` | CPU time of Prism's own threads by name, numbers as `N` (`tokio-runtime-w`: Rocky; `DefaultDispatch`: coroutines …), top 20 |
| `phone_prism_cpu_seconds_total` | | Prism's whole process, ended threads included (resets when it restarts) |
| `phone_prism_replica_open` | | 1 while the Rocky replica is open and syncing (PrismApi: screen on, charging, or shortly after a push or a call) |
| `phone_prism_replica_opens_total`, `phone_prism_replica_closes_total` | | How often it opened and closed since Prism's process started |

## Background policy

| Metric | Labels | Meaning |
|---|---|---|
| `phone_app_background_allowed` | `package` | 1 while the app may run in the background, 0 while restricted (`BackgroundPolicy.kt`, settings `background_restricted` and `background_charging_only`) |

## Hotspot

The phone's hotspot (`Hotspot.kt`, `docs/hotspot.md`), as it is at push time.

| Metric | Labels | Meaning |
|---|---|---|
| `phone_hotspot_mode` | `mode` = `instant`, `normal` | 1 for the current mode, 0 for the other |
| `phone_hotspot_on` | | 1 while Wi-Fi tethering runs (Android 16 and newer; 0 before) |
| `phone_hotspot_starts_total` | `reason` = `ble` (the Mac asked), `normal` (normal mode started it) | Starts Prism made, counted since Prism's data was cleared |
| `phone_hotspot_ble_scanning` | | 1 while the Bluetooth LE scan for the Mac's request is registered (instant mode, `hotspot_key` set, Bluetooth and location on, not on Wi-Fi, hotspot off) |

## Prism itself

| Metric | Meaning |
|---|---|
| `phone_metrics_push_failures_total` | Pushes that failed since Prism's data was cleared |
| `phone_metrics_batterystats_available` | 1 when batterystats gave its stats (not just some output) |
| `phone_metrics_batterystats_lines` | Lines the read gave (0 or a few: something is wrong) |
| `phone_metrics_batterystats_duration_seconds` | How long reading batterystats took |
| `phone_metrics_batterystats_fallback` | 1 when `--checkin` gave nothing and `-c` was read instead |
| `phone_metrics_shell_available` | 1 while the shell server runs (per-process CPU and the background policy need it) |
| `phone_metrics_collect_duration_seconds` | How long collecting took: the push's own cost |
| `phone_prism_build_info` | 1, with `component="app"` and `version` = Prism's version name and code |

## Example queries

```promql
# Who drained the battery in the last 24 h (mAh)
topk(10, increase(phone_app_estimated_charge_ampere_hours_total{instance="david-pixel"}[24h]) * 1000)

# Partial wakelock time per hour, by app
topk(10, rate(phone_app_wakelock_seconds_total{type="partial"}[1h]) * 3600)

# Share of time awake (1 = never slept)
rate(phone_awake_seconds_total{instance="david-pixel"}[1h]) / rate(phone_since_boot_seconds_total{instance="david-pixel"}[1h])

# What wakes the phone, per hour
topk(10, rate(phone_wakeup_reason_total{instance="david-pixel"}[1h]) * 3600)

# Which processes use the CPU, in CPU seconds per hour
topk(10, rate(phone_process_cpu_seconds_total{instance="david-pixel"}[1h]) * 3600)

# Discharge rate in % per hour
-deriv(phone_battery_level_ratio[1h]) * 100 * 3600
```

ClickHouse (`observability.metrics`) holds the same series under the same
metric names and labels.
