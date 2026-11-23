# shared/: the code both apps share

Kotlin Multiplatform without Gradle. The Android app compiles it with its
own sources; the Mac app links it as `PrismShared.framework`, built by
Kotlin/Native in the Nix store (`default.nix`).

| Path | What |
|---|---|
| `src/commonMain/kotlin` | Both apps. Package `at.quickme.prism.shared` |
| `src/jvmMain/kotlin` | Android only (and the JVM check): `actual`s, OkHttp |
| `src/appleMain/kotlin` | The Mac only: `actual`s, Ktor's Darwin engine (NSURLSession) |
| `src/commonTest/kotlin`, `src/jvmTest/kotlin` | The checks, run on a JVM (`nix flake check`: `shared-jvm`) |
| `deps/macos-roots.txt` → `deps/macos-lock.json` | The Mac's klibs (macos_arm64), pinned by hash |
| `deps/jvm-test-roots.txt` → `deps/jvm-test-lock.json` | OkHttp and JNA for a desktop JVM, for the check |
| `at.quickme.prism.rocky` (generated, not in `src/`) | The Rocky replica's bindings (`../rocky-prism-ffi`), below |

The JVM libraries are the app's: the `shared/` block in
`android/deps/roots.txt`, resolved into `android/deps/lock.json` with
everything else on the app's class path (one version of each library in the
dex).

The libraries: kotlinx-coroutines 1.11.0, kotlinx-serialization-json 1.13.0
(the compiler plugin is kotlinc's, `lib/kotlinx-serialization-compiler-plugin.jar`),
Ktor 3.6.1 client core, content negotiation and kotlinx JSON, with OkHttp
(Android) and Darwin (Mac) as engines.

## Adding code

Put a `.kt` file under the source set it belongs to; nothing lists files.
Code for both apps goes in `src/commonMain/kotlin/at/quickme/prism/shared/`.
Where the platforms differ, declare an `expect` in commonMain and put its
`actual` in both `src/jvmMain` and `src/appleMain`; a source set of one
platform sees only commonMain and its own files. The serialization plugin
is on for all of them (`@Serializable`).

- Android code uses it like its own: the app and shared/ are one Kotlin
  module (`internal` is visible across).
- Swift sees the public API through the framework's header
  (`import PrismShared`). Keep what Swift shouldn't see `internal`, or mark
  it `@HiddenFromObjC` (as `PrismShared.httpClient()`, which would otherwise
  put all of Ktor's types into the header).

What the apps take from here for Prism (docs/prism-api.md): `Api.kt` (its
objects, with what both apps derive from them: grouping, labels, T3 links),
`PrismClient.kt` (a suspend function per call, every failure a
`PrismError`; its `PrismTransport` is the device's replica, below, or the
apps' sample modes) and `Push.kt` (the relay stream, its messages and the
keys already handled). Android calls the client through
`android/.../PrismApi.kt`, which opens the replica from the phone's
settings, and runs the stream in `PrismPush.kt`; the Mac's is
`macos/Alerts.swift`'s `PrismStore`.

A new library: add it to `deps/macos-roots.txt` and to the `shared/` block in
`android/deps/roots.txt` at the same version, then

```sh
python3 -I android/deps/resolve.py                     # the app's lock.json
python3 -I android/deps/resolve.py --native macos_arm64 \
  ../../shared/deps/macos-roots.txt ../../shared/deps/macos-lock.json
```

## The Rocky replica (rocky-prism-ffi)

The apps read and write their own replica of Prism's Rocky database
(docs/worker.md) through `rocky-prism`, the Rust crate the worker uses.
`PrismReplica.kt` is the apps' side: the placement calls `PrismClient` makes
to the server, on the local replica, returning the same `Placement`, and
`changes()` / `placementUpdates()` to redraw when the replica changes (a
local write or a sync).

`client(device)` is the whole Prism API on the replica: the `PrismClient`
the apps used over HTTP before, whose transport is rocky-prism-ffi's
`Replica.call` (reads from the replica's tables, actions as request rows the
leading worker runs; docs/prism-api.md). An enrolled device opens with
`openProvisioned(directory, provisioning, peers)`.

```kotlin
val replica = PrismReplica.openProvisioned(noBackupFilesDir.resolve("rocky").path, provisioning, peers)
val alerts = replica.client("david-pixel").alerts("open")
replica.changes(listOf("alerts")).collect { reload() }
```

```kotlin
val replica = PrismReplica.open(filesDir.resolve("prism").path, dial = true)
val placement = replica.savePlacementProfile("night", "Night", entries)
replica.placementUpdates().flow.collect { show(it) }
```

```swift
let replica = try await PrismReplica.companion.open(directory: path, dial: true, clusterMachine: "")
for try await placement in stream(replica.placementUpdates()) { … }
```

How it is put together:

| Piece | What |
|---|---|
| `../rocky-prism-ffi/src/lib.rs` | The UniFFI surface (proc macros, UniFFI 0.29.4): `open_replica`, `Replica` (placement view and edits, `changes`, `shutdown`), `Changes.next()`, `ReplicaError`. Every call is async, run on the library's own tokio runtime |
| `../uniffi-bindgen/` | gobley's bindgen (`gobley-uniffi-bindgen` 0.3.7) as a workspace binary: it reads the UniFFI metadata out of the built library and writes Kotlin Multiplatform sources and a C header |
| `../rocky-prism-ffi/uniffi.toml` | Its settings: package `at.quickme.prism.rocky`, JVM and native targets, JNA's cleaner |
| `../rocky-prism-ffi/default.nix` | `host` (the library and bindgen for this machine), `bindings` (the generated sources), `androidAbi` / `android` (the Android libraries) |
| `default.nix` (`sources`) | `src/` with the bindings added: commonMain (the `expect`s), jvmMain (JNA), appleMain (Kotlin/Native, from gobley's nativeMain) |

Structured values cross the boundary as JSON in the HTTP API's shapes (the
placement view is `GET /v1/placement`'s body, each edit takes its `PUT`'s
body), so the replica and the server share `Api.kt`'s objects while both
exist. The bindings' own types stay in `at.quickme.prism.rocky`; app code
uses `PrismReplica`.

Per platform:

- **JVM check** (`shared-jvm`): JNA loads the host's `librocky_prism_ffi`
  (`-Djna.library.path`); `ReplicaTest.kt` opens a replica in a temporary
  directory, writes a profile, reads it back, watches a change, gets a typed
  error, reopens.
- **Android**: the app compiles jvmMain (with the JNA bindings) as on the
  JVM. The APK carries `lib/arm64-v8a/librocky_prism_ffi.so` (cross-compiled
  with the NDK's clang, size-optimized with LTO, 16 KB pages) and JNA's
  `libjnidispatch.so` from its AAR, both stored uncompressed; R8 keeps JNA
  and `at.quickme.prism.rocky` (`android/proguard-rules.pro`). JNA and
  atomicfu are in `android/deps/roots.txt` (`jna@aar`: `resolve.py` takes the
  AAR next to JNA's jar for a root that asks for it).
- **Mac**: `cinterop` turns the generated header into a klib (package
  `rocky_prism_ffi.cinterop`) that the framework compiles against; the
  host's `librocky_prism_ffi.a` is merged into the framework's archive with
  `libtool`, and the module map links what Rust needs from the system
  (`iconv`, CoreFoundation), so `macos/install.sh` links one framework as
  before. `prism self-test` opens a replica from Swift.

Why gobley's bindgen without gobley's Gradle plugins: this project builds
without Gradle. The plugins only drive cargo and the bindgen and wire the
outputs into Gradle's source sets and cinterop; `default.nix` does those
steps itself. Mozilla's own Kotlin bindings are JVM-only, so the Mac would
have needed a separate Swift binding beside the framework.

Rust for Android: nixpkgs' rustc is built from source and has no standard
library for Android, and it refuses Rust's prebuilt one ("compiled by an
incompatible version of rustc"). The Android build uses Rust's prebuilt
toolchain from static.rust-lang.org instead (rustc, cargo and the host's and
the Android targets' standard libraries, fixed-output fetches; hosts:
aarch64/x86_64 Darwin and x86_64 Linux, where the NDK's clang runs). The
host and Mac builds use nixpkgs' Rust. Rocky's SQLite is the bundled one
(`LIBSQLITE3_SYS_USE_PKG_CONFIG=0`), its bindings from bindgen.

A new rocky-prism call: add it to `Replica` in `rocky-prism-ffi/src/lib.rs`
(async, run through `on_runtime`), then wrap it in `PrismReplica.kt`; the
bindings regenerate in the build. To read the generated Kotlin:
`nix build .#rocky-prism-bindings`. Opening from fleet's provisioning bundle
goes next to `open_replica` once rocky-prism has it.

## Building

```sh
nix build .#prism-shared-framework      # PrismShared.framework (Apple Silicon)
nix build .#checks.aarch64-darwin.shared-jvm   # commonMain + jvmMain + tests on a JVM
nix build .#android-apk-debug           # the app, with shared/ compiled in
nix build .#rocky-prism-ffi-android     # librocky_prism_ffi.so for arm64-v8a and x86_64
nix build .#rocky-prism-bindings        # the generated Kotlin and C header
BUILD_ONLY=1 bash macos/install.sh /tmp/prism   # the Mac app; self-test runs shared/'s checks
```

`macos/install.sh` takes the framework from next to itself (`lib.macosApp`,
which is `packages.aarch64-darwin.prism-macos-app`: `macos/` plus the
framework), from `PRISM_SHARED_FRAMEWORK`, or else builds it with nix.

### Kotlin/Native without ~/.konan or Xcode

Kotlin/Native would download LLVM and libffi into `~/.konan` on its first
run and take the macOS SDK and the linker from Xcode. `framework` instead
gives it a data directory (`KONAN_DATA_DIR`) with everything already in
place, offline (`airplaneMode`):

- LLVM and libffi: fixed-output derivations of JetBrains' archives
  (`konanDeps`; their names come from the distribution's
  `konan/konan.properties`, which the build checks).
- Xcode's parts: with `KONAN_USE_INTERNAL_SERVER=1` Kotlin/Native takes the
  sysroot and toolchain from that directory instead of from `xcrun`. The
  sysroot is Apple's SDK as nixpkgs fetches it (`apple-sdk_26.src`, with
  libc++'s stubs that nixpkgs' SDK leaves out); the toolchain is nixpkgs'
  ld64 and cctools, LLVM's dsymutil and compiler-rt. `DEVELOPER_DIR` points
  nowhere, so any call to xcrun would fail the build.

A new kotlin-native in nixpkgs may name other dependencies; the build then
stops with the name to update in `default.nix`.

## Swift and Kotlin's async code

- **suspend functions** are `async throws` in Swift: Kotlin/Native exports
  them with a completion handler, which Swift imports as async. The framework
  allows calling them from any thread
  (`objcExportSuspendFunctionLaunchThreadRestriction=none`). A Kotlin
  exception arrives as an `NSError`. Swift's task cancellation doesn't reach
  the coroutine; that's fine for a request and its answer.
- **Flows** (and anything long-running that must stop) are a `Watchable`
  (`Watch.kt`): `watch(onEach:onComplete:)` collects on a Kotlin dispatcher
  and returns a `Subscription` to cancel. `stream(_:)` in
  `macos/Shared.swift` makes that an `AsyncThrowingStream`; leaving the
  `for try await` loop, or cancelling its task, cancels the coroutine.

```swift
let probe = try await PrismShared.shared.fetchProbe(url: url)
for try await tick in stream(WatchKt.countdown(count: 3, intervalMillis: 100)) { … }
```

SKIE, which generates this kind of bridge, is a Gradle plugin, so it's out
here.

## Kotlin's types in Swift

The Mac uses shared/'s objects in its views as they are (`macos/Alerts.swift`
only adds Identifiable and a few properties in Swift's types). What to know:

- Kotlin's `Long` is `Int64`, `Int` is `Int32`; a `Double?` is a
  `KotlinDouble?` (`.doubleValue`), and a `List<Double>` is `[KotlinDouble]`.
  A `List<Double?>` comes as `[Any]` with `NSNull` for null.
- An `object` is `X.shared` (`T3Links.shared.app(…)`), a companion
  `X.companion` (`PrismClient.companion.requestId()`), top-level functions
  hang off the file's class (`ApiKt.groupAlerts(alerts:now:)`).
- A Kotlin enum is a class with a static per entry (`Severity.critical`):
  `==` and `switch` work, but a `switch` needs a `default`.
- Data classes compare with `==` (`isEqual`) and hash, so `ForEach(…, id: \.self)`
  works on them.
- `PrismShared` in Swift is the Kotlin object of that name, not the module:
  write imported names unqualified. The app's own types shadow imported ones
  of the same name, and SwiftUI has an `Alert` of its own, so the Mac calls
  shared/'s `PrismAlert` where it imports SwiftUI.
- A `PrismError` arrives as an `NSError` whose `userInfo["KotlinException"]`
  is the PrismError (`Error.prism` in `macos/Alerts.swift`).
