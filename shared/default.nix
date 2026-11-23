# The code the Mac app (Swift) and the Android app (Kotlin) share, as Kotlin
# Multiplatform without Gradle (README.md in this directory):
#
# - src/commonMain: both platforms; src/jvmMain: Android (and the JVM check);
#   src/appleMain: the Mac. expect/actual between them, one module per
#   platform (kotlinc's -Xfragments, as Gradle's plugin passes them).
# - Android: android/default.nix compiles commonMain and jvmMain with the
#   app's sources in one kotlinc run (`kotlincArgs`), against the libraries
#   of android/deps/lock.json, whose roots.txt has a shared/ block.
# - Mac: `framework` is PrismShared.framework for macos_arm64, built by
#   Kotlin/Native (nixpkgs' kotlin-native) in the Nix store against the klibs
#   of deps/macos-lock.json. Its toolchain is Nix's too, not Xcode: the
#   dependencies Kotlin/Native would download into ~/.konan (LLVM, libffi)
#   are fixed-output derivations here, the sysroot is Apple's SDK as nixpkgs
#   fetches it, the linker is nixpkgs' ld64 (`konanData`). macos/install.sh
#   links the framework into Prism.app.
# - `check`: commonMain, jvmMain and the tests (src/commonTest, src/jvmTest)
#   on a JVM.
{ pkgs }:
let
  lib = pkgs.lib;
  kotlin = pkgs.kotlin;
  kotlinNative = pkgs.kotlin-native;
  jdk = pkgs.jdk17_headless;

  # rocky-prism for the apps (../rocky-prism-ffi): the Rust library and the
  # Kotlin bindings gobley's bindgen generates from it, package
  # at.quickme.prism.rocky.
  ffi = import ../rocky-prism-ffi { inherit pkgs; };

  # src/ with the generated bindings in their source sets: commonMain's
  # expects, jvmMain's JNA actuals (the JVM and Android), and the Kotlin/Native
  # actuals (nativeMain) in appleMain.
  ownSources = lib.fileset.toSource {
    root = ./.;
    fileset = ./src;
  };
  sources = pkgs.runCommand "prism-shared-sources" { } ''
    cp -r ${ownSources} $out
    chmod -R u+w $out
    for set in commonMain:commonMain jvmMain:jvmMain nativeMain:appleMain; do
      mkdir -p $out/src/''${set#*:}/kotlin
      cp -r ${ffi.bindings}/''${set%:*}/kotlin/. $out/src/''${set#*:}/kotlin/
    done
    chmod -R u+w $out
    # The generated code's own warnings, which -Werror would fail on.
    find $out/src -path '*/at/quickme/prism/rocky/*.kt' -exec sed -i \
      's/^@file:Suppress("RemoveRedundantBackticks")$/@file:Suppress("RemoveRedundantBackticks", "UNUSED_EXPRESSION", "UNUSED_PARAMETER", "UNUSED_VARIABLE", "NOTHING_TO_INLINE", "DEPRECATION")/' {} +
  '';

  # Kotlin's serialization compiler plugin, in kotlinc's distribution.
  serializationPlugin = "${kotlin}/lib/kotlinx-serialization-compiler-plugin.jar";

  # Writes kotlinc's arguments for one platform's module to an argument file
  # (kotlinc @FILE): commonMain as the `common` fragment, the platform's
  # source set (src/<platform>Main) and the extra sources given (the app's,
  # tests) as the platform fragment that refines it, and the serialization
  # plugin.
  #
  #   ${kotlincArgs.jvm} ARGFILE [SOURCE...]
  fragmentArgs =
    platform: plugin:
    pkgs.writeShellScript "prism-shared-${platform}-args" ''
      set -eu
      out=$1
      shift
      common=$(find ${sources}/src/commonMain -name '*.kt' | LC_ALL=C sort)
      own=$(find ${sources}/src/${platform}Main -name '*.kt' | LC_ALL=C sort)
      {
        echo -Xmulti-platform
        # The bindings' expect/actual classes (at.quickme.prism.rocky).
        echo -Xexpect-actual-classes
        echo -Xfragments=common,${platform}
        echo -Xfragment-refines=${platform}:common
        printf -- '-Xfragment-sources='
        {
          for f in $common; do echo "common:$f"; done
          for f in $own "$@"; do echo "${platform}:$f"; done
        } | paste -sd, -
        echo "-Xplugin=${plugin}"
        for f in $common $own "$@"; do echo "$f"; done
      } > "$out"
    '';
  kotlincArgs = {
    jvm = fragmentArgs "jvm" serializationPlugin;
    apple = fragmentArgs "apple" serializationPlugin;
  };

  fetchLock =
    file:
    map (d: {
      inherit (d) file coordinate;
      src = pkgs.fetchurl { inherit (d) url sha256; };
    }) (builtins.fromJSON (builtins.readFile file));

  # --- The JVM check ---

  # The app's jars (not its AARs: Android only) and OkHttp for a desktop JVM.
  jvmJars = map (d: d.src) (
    lib.filter (d: lib.hasSuffix ".jar" d.file) (
      fetchLock ../android/deps/lock.json ++ fetchLock ./deps/jvm-test-lock.json
    )
  );
  testSources = lib.fileset.toSource {
    root = ./.;
    fileset = lib.fileset.unions [
      ./src/commonTest
      ./src/jvmTest
    ];
  };
  check =
    pkgs.runCommand "prism-shared-jvm-tests"
      {
        nativeBuildInputs = [
          jdk
          kotlin
        ];
        # The test's HTTP server is on loopback.
        __darwinAllowLocalNetworking = true;
      }
      ''
        cp=${lib.concatStringsSep ":" jvmJars}
        ${kotlincArgs.jvm} args $(find ${testSources} -name '*.kt' | LC_ALL=C sort)
        kotlinc -no-stdlib -no-reflect -jvm-target 17 -Werror -classpath $cp -d classes @args
        java -Djna.library.path=${ffi.host}/lib -cp classes:$cp at.quickme.prism.shared.JvmTestKt ${pkgs.openssh}/bin/ssh-keygen
        touch $out
      '';

  # --- The Mac (Kotlin/Native, macos_arm64) ---

  # What Kotlin/Native would download on its first run, for macos_arm64 on
  # an Apple Silicon host (its konan/konan.properties names them; `framework`
  # checks they are still the ones it asks for).
  konanDeps = {
    llvm = {
      name = "llvm-21-aarch64-macos-essentials-97";
      src = pkgs.fetchzip {
        url = "https://download.jetbrains.com/kotlin/native/resources/llvm/21-aarch64-macos/llvm-21-aarch64-macos-essentials-97.tar.gz";
        hash = "sha256-u76zPrh+L5dP8EX6v26UmbdzY6XYtFd+01dkNZyKQdg=";
      };
    };
    libffi = {
      name = "libffi-3.3-1-macos-arm64";
      src = pkgs.fetchzip {
        url = "https://download.jetbrains.com/kotlin/native/libffi-3.3-1-macos-arm64.tar.gz";
        hash = "sha256-uo+XbTxVnYqJcNLkqcmzpjGNULsC/t0GolKfyD0+qXU=";
      };
    };
  };
  # Xcode's parts as Kotlin/Native names them (konan.properties); it takes
  # them from the dependency directory instead of from Xcode when
  # KONAN_USE_INTERNAL_SERVER=1.
  xcodeParts = "xcode_26.4_17E192";

  # The toolchain Kotlin/Native would take from Xcode: ld (ld64), libtool,
  # strip, lipo and friends (cctools), dsymutil (LLVM) and clang's runtime
  # library (compiler-rt), laid out as in Xcode's toolchain.
  llvmPackages = pkgs.llvmPackages_21;
  toolchain = pkgs.runCommand "prism-konan-toolchain" { } ''
    mkdir -p $out/usr/bin $out/usr/lib/clang/${lib.versions.major llvmPackages.compiler-rt.version}/lib
    ln -s ${pkgs.ld64}/bin/ld $out/usr/bin/ld
    for t in libtool strip lipo nm otool install_name_tool codesign_allocate ar ranlib; do
      ln -s ${pkgs.cctools}/bin/$t $out/usr/bin/$t
    done
    ln -s ${llvmPackages.llvm}/bin/dsymutil $out/usr/bin/dsymutil
    ln -s ${llvmPackages.compiler-rt}/lib/darwin $out/usr/lib/clang/${lib.versions.major llvmPackages.compiler-rt.version}/lib/darwin
  '';
  # Apple's macOS SDK as nixpkgs fetches it (fixed output), before nixpkgs
  # takes libc++ out of it: the framework links the system's libc++, as the
  # Swift app does.
  sdk = pkgs.apple-sdk_26.src;

  # KONAN_DATA_DIR's dependencies/, for `framework` to link into a writable
  # data directory of its own.
  konanDependencies = pkgs.runCommand "prism-konan-dependencies" { } ''
    mkdir -p $out
    ln -s ${konanDeps.llvm.src} $out/${konanDeps.llvm.name}
    ln -s ${konanDeps.libffi.src} $out/${konanDeps.libffi.name}
    ln -s ${sdk} $out/target-sysroot-${xcodeParts}-macosx
    ln -s ${toolchain} $out/target-toolchain-${xcodeParts}
    mkdir -p $out/xcode-addon-${xcodeParts}
    touch $out/xcode-addon-${xcodeParts}/.empty
    printf '%s\n' ${konanDeps.llvm.name} ${konanDeps.libffi.name} target-sysroot-${xcodeParts}-macosx \
      target-toolchain-${xcodeParts} xcode-addon-${xcodeParts} > $out/.extracted
  '';

  klibs = map (d: d.src) (fetchLock ./deps/macos-lock.json);

  # PrismShared.framework: a static Objective-C framework (the header and
  # module map Swift imports, the archive linked into the app's executable).
  # Swift calls suspend functions as async functions from any thread
  # (objcExportSuspendFunctionLaunchThreadRestriction=none).
  framework = pkgs.stdenvNoCC.mkDerivation {
    pname = "prism-shared-framework";
    version = "1";
    src = sources;
    nativeBuildInputs = [ kotlinNative ];
    buildPhase = ''
      runHook preBuild
      props=${kotlinNative}/konan/konan.properties
      for want in "llvm.macos_arm64.user=${konanDeps.llvm.name}" "libffiDir.macos_arm64 = ${konanDeps.libffi.name}" \
        "targetSysRoot.macos_arm64 = target-sysroot-${xcodeParts}-macosx"; do
        grep -qF "$want" $props || { echo "kotlin-native ${kotlinNative.version} no longer has '$want' in konan.properties: update konanDeps/xcodeParts in shared/default.nix" >&2; exit 1; }
      done

      # Kotlin/Native's data directory with its dependencies in place, offline
      # (airplaneMode) and with Xcode's parts from there, not from Xcode
      # (KONAN_USE_INTERNAL_SERVER); DEVELOPER_DIR makes any xcrun fail.
      export KONAN_DATA_DIR=$PWD/konan KONAN_USE_INTERNAL_SERVER=1 DEVELOPER_DIR=/var/empty
      mkdir -p $KONAN_DATA_DIR
      cp -r ${konanDependencies} $KONAN_DATA_DIR/dependencies
      chmod -R u+w $KONAN_DATA_DIR

      # The bindings' C side (rocky_prism_ffi.h) as a klib, package
      # rocky_prism_ffi.cinterop, which their Kotlin/Native half calls.
      printf '%s\n' 'package = rocky_prism_ffi.cinterop' 'headers = rocky_prism_ffi.h' > rocky_prism_ffi.def
      cinterop -target macos_arm64 -def rocky_prism_ffi.def \
        -compiler-option -I${ffi.bindings}/nativeInterop/cinterop/headers/rocky_prism_ffi \
        -Xkotlinc-option "-Xoverride-konan-properties=airplaneMode=true;dependencies.macos_arm64=" \
        -o rocky_prism_ffi

      ${kotlincArgs.apple} args
      konanc -target macos_arm64 -produce framework -Xstatic-framework -opt \
        -Xoverride-konan-properties='airplaneMode=true;dependencies.macos_arm64=' \
        -Xbinary=bundleId=at.quickme.prism.shared \
        -Xbinary=objcExportSuspendFunctionLaunchThreadRestriction=none \
        -Werror \
        ${lib.concatMapStrings (k: "-library ${k} ") klibs} \
        -library rocky_prism_ffi.klib \
        -o $out/PrismShared @args

      # rocky-prism-ffi's static library in the framework's archive, so the
      # app links one archive; the module map links what Rust needs from the
      # system (rustc --print native-static-libs: iconv, CoreFoundation).
      fw=$out/PrismShared.framework
      ${toolchain}/usr/bin/libtool -static -o PrismShared.a $fw/PrismShared ${ffi.host}/lib/librocky_prism_ffi.a
      mv PrismShared.a $fw/PrismShared
      substituteInPlace $fw/Modules/module.modulemap --replace-fail '    use Foundation' '    link "iconv"
          link framework "CoreFoundation"

          use Foundation'
      runHook postBuild
    '';
    dontInstall = true;
    dontFixup = true;
    meta.platforms = [ "aarch64-darwin" ];
  };
in
{
  inherit
    ffi
    sources
    kotlincArgs
    serializationPlugin
    check
    framework
    konanDependencies
    toolchain
    ;
}
