# Prism: the `prism` CLI (cli/), the worker (worker/, the server), the Mac
# app (macos/) and the Android app (android/). fleet installs
# packages.<system>.prism on every host it manages, builds lib.macosApp on the
# Macs, runs android-publish on david-mm and phone-enroll on david-mb;
# fleet-prism bumps its pin automatically when main changes. The worker runs
# from its container image (CI), which also carries `prism` for the owner's
# admin commands.
{
  description = "Prism: the owner's companion apps (macOS, Android), their server and the prism CLI machines report with";

  inputs.nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";

  outputs =
    { self, nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});

      # The cargo workspace's sources: the CLI, the worker, the schema crate
      # and the apps' bindings (rocky-prism-ffi, uniffi-bindgen), which
      # shared/ builds.
      workspaceSrc = nixpkgs.lib.fileset.toSource {
        root = ./.;
        fileset = nixpkgs.lib.fileset.unions [
          ./Cargo.toml
          ./Cargo.lock
          ./cli
          ./rocky-prism
          ./rocky-prism-ffi
          ./uniffi-bindgen
          ./worker
        ];
      };
      cargoLock = {
        lockFile = ./Cargo.lock;
        # Rocky (one git checkout for all its crates), hashed so evaluation
        # needs no git: the NixOS hosts' deploy service evaluates fleet, and
        # this CLI with it, without git on its PATH. A new Rocky rev in
        # Cargo.toml needs a new hash here and in rocky-prism-ffi/default.nix.
        outputHashes."rocky-core-0.1.0" = "sha256-EPqlnNFL0UHDmnwii+zNLQolzbIcSOx4PWyaXf6+G2E=";
      };

      # The `prism` CLI (cli/): machines report check results with it
      # (`prism run`, `prism report`), the owner pairs and lists machines.
      # It needs no Rocky, so no SQLite or bindgen.
      prism =
        pkgs:
        pkgs.rustPlatform.buildRustPackage {
          pname = "prism";
          version = self.shortRev or self.dirtyShortRev or "dev";
          src = workspaceSrc;
          inherit cargoLock;
          cargoBuildFlags = [
            "-p"
            "prism-cli"
          ];
          cargoTestFlags = [
            "-p"
            "prism-cli"
          ];
          meta = {
            description = "Report machine check results to Prism (and, on a Mac, run the Prism app's subcommands)";
            mainProgram = "prism";
          };
        };

      # The Android SDK needs its licence accepted.
      androidPkgs =
        system:
        import nixpkgs {
          inherit system;
          config.android_sdk.accept_license = true;
          config.allowUnfree = true;
        };
      # The Prism worker (worker/) and shared schema crate (rocky-prism/):
      # Prism's server, its state replicated with Rocky (github:BierDav/rocky,
      # pinned by rev in Cargo.toml and Cargo.lock) over rocky-tcp.
      worker =
        pkgs:
        pkgs.rustPlatform.buildRustPackage {
          pname = "prism-worker";
          version = self.shortRev or self.dirtyShortRev or "dev";
          src = workspaceSrc;
          # The workspace also holds the CLI and the apps' bindings
          # (rocky-prism-ffi, uniffi-bindgen); the worker needs none of them.
          cargoBuildFlags = [
            "-p"
            "prism-worker"
          ];
          cargoTestFlags = [
            "-p"
            "prism-worker"
            "-p"
            "rocky-prism"
          ];
          inherit cargoLock;
          # Rocky's SQLite (libsqlite3-sys) generates its bindings with bindgen.
          nativeBuildInputs = [ pkgs.rustPlatform.bindgenHook ];
          # Rocky needs its bundled SQLite (built with the preupdate hook);
          # nixpkgs' cargo hook would link the system one instead.
          preBuild = ''
            export LIBSQLITE3_SYS_USE_PKG_CONFIG=0
          '';
          meta.mainProgram = "prism-worker";
        };

      # The version code must grow with every build the phone installs: the
      # commit time does (seconds, fits Android's int until 2038).
      android =
        system:
        import ./android {
          pkgs = androidPkgs system;
          versionCode = self.lastModified or 1;
          versionName = "1.0-${self.shortRev or self.dirtyShortRev or "dev"}";
        };
      # The code both apps share, Kotlin Multiplatform (shared/).
      shared = system: import ./shared { pkgs = nixpkgs.legacyPackages.${system}; };
    in
    {
      lib = {
        # The Mac app's sources with PrismShared.framework (shared/) next to
        # them; `install.sh` there builds the app. Apple Silicon only.
        macosApp = "${self.packages.aarch64-darwin.prism-macos-app}";
      };

      packages = nixpkgs.lib.genAttrs systems (
        system:
        let
          cli = prism nixpkgs.legacyPackages.${system};
        in
        {
          prism = cli;
          default = cli;
          prism-worker = worker nixpkgs.legacyPackages.${system};
          android-apk = (android system).apk;
          android-apk-dpcdebug = (android system).apk-dpcdebug;
          # The same app as at.quickme.prism.debug, debuggable, to
          # install next to the real one (sign it with any debug key).
          android-apk-debug = (android system).apk-debug;
          android-publish = (android system).android-publish;
          phone-enroll = (android system).phone-enroll;
          # NetBird's client library inside the APK (android/netbird).
          netbird-android = (android system).netbird;
          # rocky-prism for the apps (rocky-prism-ffi): the host's library
          # and bindgen, the Kotlin bindings, and the Android libraries
          # (arm64-v8a, x86_64).
          rocky-prism-ffi = (shared system).ffi.host;
          rocky-prism-bindings = (shared system).ffi.bindings;
          rocky-prism-ffi-android = (import ./rocky-prism-ffi { pkgs = androidPkgs system; }).android;
          # A PNG of every Compose preview (android/previews), rendered
          # headless with layoutlib.
          android-previews = (android system).previews;
        }
        # PrismShared.framework (shared/), which macos/install.sh links into
        # Prism.app.
        // nixpkgs.lib.optionalAttrs (system == "aarch64-darwin") {
          prism-shared-framework = (shared system).framework;
          prism-macos-app = nixpkgs.legacyPackages.${system}.runCommand "prism-macos-app" { } ''
            cp -r ${./macos} $out
            chmod u+w $out
            ln -s ${(shared system).framework}/PrismShared.framework $out/PrismShared.framework
          '';
        }
      );

      overlays.default = final: _prev: { prism = prism final; };

      checks = forAllSystems (
        pkgs:
        let
          system = pkgs.stdenv.hostPlatform.system;
          previews = (android system).previews;
        in
        {
          android-core = (android system).check;
          shared-jvm = (shared system).check;
          prism = self.packages.${system}.prism;
          prism-worker = self.packages.${system}.prism-worker;
        }
        # Every Compose preview renders (a failing one fails the build),
        # where layoutlib has native libraries.
        // nixpkgs.lib.optionalAttrs (nixpkgs.lib.meta.availableOn pkgs.stdenv.hostPlatform previews) {
          android-previews = previews;
        }
      );

      formatter = forAllSystems (pkgs: pkgs.nixfmt-tree);
    };
}
