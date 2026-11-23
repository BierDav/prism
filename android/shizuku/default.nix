# The shell starter (starter/starter.c, derived from Shizuku's) for arm64
# and, for 32-bit TVs, armeabi-v7a, built with the NDK's clang: a PIE
# executable named like a library so the package manager extracts it to
# Prism's lib/arm64 (lib/arm) folder, where the cluster runs it over adb
# (README.md). Linked for 16 KB pages.
#
# `pkgs` must allow the Android SDK's licence (android_sdk.accept_license).
{ pkgs }:
let
  ndkVersion = "29.0.14206865";
  # The NDK alone, so the APK's other tools (and the publisher) don't pull it in;
  # the same one as netbird/ uses, so it is fetched once.
  ndk =
    (pkgs.androidenv.composeAndroidPackages {
      platformVersions = [ ];
      buildToolsVersions = [ ];
      includeEmulator = false;
      includeSystemImages = false;
      includeNDK = true;
      ndkVersions = [ ndkVersion ];
    }).ndk-bundle;
  host = if pkgs.stdenv.buildPlatform.isDarwin then "darwin-x86_64" else "linux-x86_64";
  minSdk = "31";
in
pkgs.stdenvNoCC.mkDerivation {
  pname = "prism-shell-starter";
  version = "1";
  src = ./starter;
  dontConfigure = true;
  # An Android ELF: no patchelf, no host strip (it is stripped when linked).
  dontFixup = true;
  buildPhase = ''
    runHook preBuild
    toolchain=${ndk}/libexec/android-sdk/ndk-bundle/toolchains/llvm/prebuilt/${host}
    build() {
      local target=$1 abi=$2
      mkdir -p out/$abi
      $toolchain/bin/clang --target="$target"${minSdk} \
        -std=c11 -Os -Wall -Wextra -Werror -fPIE -pie \
        -ffunction-sections -fdata-sections -ffile-prefix-map=$PWD=. \
        -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
        -Wl,--gc-sections -Wl,--build-id=none -Wl,-s \
        -o out/$abi/libprism_starter.so starter.c
      # Every LOAD segment aligned to 16 KB.
      if $toolchain/bin/llvm-readelf -lW out/$abi/libprism_starter.so | awk '$1 == "LOAD" && $NF != "0x4000" { bad = 1 } END { exit !bad }'; then
        $toolchain/bin/llvm-readelf -lW out/$abi/libprism_starter.so
        echo "$abi: a LOAD segment is not 16 KB aligned" >&2
        exit 1
      fi
    }
    build aarch64-linux-android arm64-v8a
    build armv7a-linux-androideabi armeabi-v7a
    runHook postBuild
  '';
  installPhase = ''
    install -Dm755 out/arm64-v8a/libprism_starter.so $out/lib/arm64-v8a/libprism_starter.so
    install -Dm755 out/armeabi-v7a/libprism_starter.so $out/lib/armeabi-v7a/libprism_starter.so
  '';
  passthru = { inherit ndk; };
}
