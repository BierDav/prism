# NetBird's client library for Android (github.com/netbirdio/netbird,
# client/android, BSD-3-Clause, see NOTICE), built the way `gomobile bind`
# builds it, without gomobile's own driver: gobind writes the Java bindings
# and the JNI glue, Go links them with the NDK's clang into libgojni.so
# (arm64-v8a for the phone, armeabi-v7a for 32-bit TVs; 16 KB pages), and
# javac compiles the bindings against the
# platform. The app's Java glue is in src/at/quickme/prism/netbird/.
#
# Output: $out/classes.jar (Java package io.netbird.gomobile.android, plus
# go.Seq, which loads libgojni) and $out/lib/<abi>/libgojni.so.
{
  pkgs,
  androidJar,
  # The app's package: wireguard-go's UAPI socket would live in its cache.
  appPackage,
  minSdk,
}:
let
  version = "0.83.1";
  src = pkgs.fetchFromGitHub {
    owner = "netbirdio";
    repo = "netbird";
    tag = "v${version}";
    hash = "sha256-4KX44eX6ylXM59B1jOVoSgUytl7WSqHGs0qaX+XFngI=";
  };

  # go.mod asks for go 1.26 (toolchain go1.26.10).
  go = pkgs.go_1_26;

  # Every module the build needs, from NetBird's go.mod and go.sum, as a
  # module proxy (golang.org/x/mobile's bind and gobind are in it).
  modules =
    (pkgs.buildGo126Module {
      pname = "netbird-android-modules";
      inherit version src;
      proxyVendor = true;
      vendorHash = "sha256-iIbATQiIfazszBSi4jl5Ys/27uYwfweEMo+4BLSnRms=";
      # `go mod download` plus whatever the Android build of the library and
      # gobind would still fetch.
      modPostBuild = ''
        GOOS=android GOARCH=arm64 CGO_ENABLED=1 go list -mod=mod -deps \
          ./client/android golang.org/x/mobile/cmd/gobind \
          golang.org/x/mobile/bind/java golang.org/x/mobile/bind/seq > /dev/null
      '';
    }).goModules;

  ndkVersion = "29.0.14206865";
  ndk =
    let
      bundle =
        (pkgs.androidenv.composeAndroidPackages {
          includeNDK = true;
          ndkVersions = [ ndkVersion ];
          platformVersions = [ ];
          buildToolsVersions = [ ];
          includeEmulator = false;
          includeSystemImages = false;
        }).ndk-bundle;
    in
    "${bundle}/libexec/android-sdk/ndk-bundle";
  # The NDK's clang runs on x86_64 and arm64 Macs (universal) and x86_64 Linux.
  ndkHost = if pkgs.stdenv.buildPlatform.isDarwin then "darwin-x86_64" else "linux-x86_64";
  clang =
    triple: "${ndk}/toolchains/llvm/prebuilt/${ndkHost}/bin/${triple}${toString minSdk}-clang";
  # Each ABI: its NDK target and Go's architecture.
  abis = [
    {
      abi = "arm64-v8a";
      triple = "aarch64-linux-android";
      goarch = "GOARCH=arm64";
    }
    {
      abi = "armeabi-v7a";
      triple = "armv7a-linux-androideabi";
      goarch = "GOARCH=arm GOARM=7";
    }
  ];

  javaPackage = "io.netbird.gomobile";
  jdk = pkgs.jdk17_headless;
in
pkgs.stdenvNoCC.mkDerivation {
  pname = "netbird-android";
  inherit version src;

  nativeBuildInputs = [
    go
    jdk
    pkgs.zip
  ];

  env = {
    GOTOOLCHAIN = "local";
    GOPROXY = "file://${modules}";
    GOSUMDB = "off";
    GOFLAGS = "-mod=mod -trimpath";
  };

  buildPhase = ''
    runHook preBuild
    export GOCACHE=$TMPDIR/go-cache GOPATH=$TMPDIR/go
    go build -o $TMPDIR/bin/gobind golang.org/x/mobile/cmd/gobind

    # The Java and Go halves of the bindings.
    GOOS=android CGO_ENABLED=1 $TMPDIR/bin/gobind -lang=go,java -outdir=$TMPDIR/gen \
      -javapkg=${javaPackage} github.com/netbirdio/netbird/client/android

    # libgojni.so: the generated main package, built inside NetBird's module
    # so its go.mod and go.sum pin everything. 16 KB pages for Android 15+.
    cp -r $TMPDIR/gen/src/gobind ./gobind
  ''
  + pkgs.lib.concatMapStrings (a: ''
    mkdir -p $TMPDIR/out/lib/${a.abi}
    GOOS=android ${a.goarch} CGO_ENABLED=1 CC=${clang a.triple} CXX=${clang a.triple}++ \
      go build -buildmode=c-shared -o $TMPDIR/out/lib/${a.abi}/libgojni.so \
      -ldflags="-s -w -buildid= -checklinkname=0 \
        -extldflags=-Wl,-z,max-page-size=16384 \
        -X github.com/netbirdio/netbird/version.version=${version} \
        -X github.com/netbirdio/wireguard-go/ipc.socketDirectory=/data/data/${appPackage}/cache/wireguard" \
      ./gobind
    rm $TMPDIR/out/lib/${a.abi}/libgojni.h
  '') abis
  + ''

    # classes.jar: the generated Java, against the platform.
    mkdir $TMPDIR/classes
    javac -nowarn --release 11 -encoding UTF-8 -classpath ${androidJar} -d $TMPDIR/classes \
      $(find $TMPDIR/gen/java -name '*.java')
    (cd $TMPDIR/classes && find . -type f -exec touch -d @315532800 {} + &&
      find . -type f | LC_ALL=C sort | zip -q -X -@ $TMPDIR/out/classes.jar)
    runHook postBuild
  '';

  installPhase = ''
    runHook preInstall
    cp -r $TMPDIR/out $out
    install -Dm644 LICENSE $out/LICENSE
    runHook postInstall
  '';

  dontFixup = true;

  passthru = {
    inherit modules ndk;
  };

  meta = {
    description = "NetBird's Android client library (gomobile bindings, arm64-v8a and armeabi-v7a)";
    homepage = "https://github.com/netbirdio/netbird";
    license = pkgs.lib.licenses.bsd3;
  };
}
