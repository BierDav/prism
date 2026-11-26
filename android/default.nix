# Prism for Android as an unsigned, aligned APK, built without Gradle: aapt2
# (resources, manifest), kotlinc with the Compose compiler plugin and javac
# (against android.jar and the AndroidX libraries), R8 (shrinks and dexes),
# zipalign. The libraries are the closure in deps/lock.json (deps/resolve.py
# writes it), fetched by hash, so the build never touches the network.
# Signing happens where the release key is (android-publish and phone-enroll,
# below), never in the Nix store.
#
# `variant` builds the same app under another application ID, side by side
# with the real one (`apk-debug` is at.quickme.prism.debug): the code
# is the same, only the manifest's package, label and provider authorities
# change.
#
# `pkgs` must allow the Android SDK's licence (android_sdk.accept_license).
{
  pkgs,
  versionCode,
  versionName,
}:
let
  lib = pkgs.lib;
  # The platform the code compiles against (android.jar): 37.1, as Google's
  # A2UI renderer (deps/roots.txt) asks (its AARs' minCompileSdk 37.1). The
  # app still targets 36: a compile SDK changes no behaviour, a target does.
  platform = "37.1";
  targetSdk = "36";
  buildTools = "37.0.0";
  minSdk = "31";
  sdk =
    (pkgs.androidenv.composeAndroidPackages {
      platformVersions = [ platform ];
      buildToolsVersions = [ buildTools ];
      includeEmulator = false;
      includeSystemImages = false;
      includeNDK = false;
    }).androidsdk;
  root = "${sdk}/libexec/android-sdk";
  tools = "${root}/build-tools/${buildTools}";
  androidJar = "${root}/platforms/android-${platform}/android.jar";
  jdk = pkgs.jdk17_headless;
  # Kotlin's compiler; its distribution carries the Compose compiler plugin.
  kotlin = pkgs.kotlin;
  # The native libraries' ABIs: arm64-v8a for the phone, armeabi-v7a for
  # 32-bit TVs (e.g. a 64-bit SoC with a 32-bit userspace).
  abis = [
    "arm64-v8a"
    "armeabi-v7a"
  ];
  # Shell access: the native starter (shizuku/), stored uncompressed and
  # 16 KB aligned under lib/<abi>.
  shellStarter = import ./shizuku { inherit pkgs; };
  # NetBird's client library (netbird/): classes.jar and
  # lib/<abi>/libgojni.so, stored the same way.
  netbird = import ./netbird {
    inherit pkgs androidJar;
    appPackage = "at.quickme.prism";
    minSdk = 31;
  };

  # The code shared with the Mac app (../shared, Kotlin Multiplatform): its
  # commonMain and jvmMain compile with the app's sources, as one module
  # (shared.kotlincArgs.jvm), with the serialization plugin; its libraries
  # are in deps/lock.json (roots.txt, the shared/ block).
  shared = import ../shared { inherit pkgs; };
  # shared/'s Rocky replica: rocky-prism-ffi for each ABI (lib/<abi>/
  # librocky_prism_ffi.so) and JNA's native half from its AAR (deps/lock.json),
  # which the generated bindings load, stored like the other libraries.
  rockyPrism = pkgs.symlinkJoin {
    name = "rocky-prism-ffi-apk";
    paths = map shared.ffi.androidAbi abis;
  };

  # --- The libraries (deps/lock.json) ---

  locked = builtins.fromJSON (builtins.readFile ./deps/lock.json);
  fetched = map (d: {
    inherit (d) file;
    src = pkgs.fetchurl {
      inherit (d) url sha256;
    };
  }) locked;

  # The native libraries inside the AARs (jni/<abi>/*.so), for each ABI:
  # JNA's, which the Rocky bindings load, and AndroidX's own, such as
  # androidx.graphics:graphics-path's, which Compose loads to draw shapes.
  aarNatives =
    let
      aars = lib.filter (d: lib.hasSuffix ".aar" d.file) fetched;
    in
    pkgs.runCommand "prism-android-aar-natives" { nativeBuildInputs = [ pkgs.unzip ]; } ''
      for abi in ${lib.concatStringsSep " " abis}; do
        mkdir -p $out/lib/$abi
        for aar in ${lib.concatMapStringsSep " " (d: "${d.src}") aars}; do
          if unzip -Z1 $aar "jni/$abi/*.so" >/dev/null 2>&1; then
            unzip -q -j -o $aar "jni/$abi/*.so" -d $out/lib/$abi
          fi
        done
      done
      for abi in ${lib.concatStringsSep " " abis}; do
        for so in libjnidispatch libandroidx.graphics.path; do
          [ -f $out/lib/$abi/$so.so ] || { echo "no $so.so for $abi in the AARs" >&2; exit 1; }
        done
      done
    '';

  # Every library unpacked once: jars/ (classes for the compilers and R8),
  # res/<lib>.zip (its resources compiled by aapt2), packages (the Java
  # packages that need an R class), rules/ (their R8 rules), services/
  # (META-INF/services, which ServiceLoader reads from the APK).
  libraries = pkgs.stdenvNoCC.mkDerivation {
    pname = "prism-android-libraries";
    version = "1";
    dontUnpack = true;
    nativeBuildInputs = [
      pkgs.unzip
      pkgs.zip
    ];
    buildPhase = ''
      runHook preBuild
      mkdir -p $out/jars $out/res $out/rules $out/services
      touch $out/packages
      unpack() {
        local file=$1 src=$2
        local name=''${file%.*}
        case $file in
          *.aar)
            mkdir -p aar/$name
            unzip -q -o $src -d aar/$name
            if [ -f aar/$name/classes.jar ]; then cp aar/$name/classes.jar $out/jars/$name.jar; fi
            if [ -d aar/$name/libs ]; then
              for j in aar/$name/libs/*.jar; do cp "$j" $out/jars/$name-$(basename "$j"); done
            fi
            if [ -d aar/$name/res ] && [ -n "$(ls -A aar/$name/res)" ]; then
              ${tools}/aapt2 compile --dir aar/$name/res -o $out/res/$name.zip
              sed -n 's/.*package="\([^"]*\)".*/\1/p' aar/$name/AndroidManifest.xml | head -n1 >> $out/packages
            fi
            if [ -f aar/$name/proguard.txt ]; then cp aar/$name/proguard.txt $out/rules/$name.pro; fi
            ;;
          *.jar)
            cp $src $out/jars/$name.jar
            ;;
        esac
      }
    ''
    + lib.concatMapStrings (d: "unpack ${d.file} ${d.src}\n") fetched
    + ''
      # R8 rules and service registrations inside the jars (R8's own folder
      # first; the plain ProGuard ones only where there is none).
      for j in $out/jars/*.jar; do
        n=$(basename $j .jar)
        mkdir -p meta/$n
        unzip -q -o $j 'META-INF/*' -d meta/$n 2>/dev/null || true
        if [ -d meta/$n/META-INF/com.android.tools ]; then
          for f in $(find meta/$n/META-INF/com.android.tools -path '*r8-upto-*' -prune -o -name '*.pro' -print); do
            cp $f $out/rules/$n-$(basename $f)
          done
        elif [ -d meta/$n/META-INF/proguard ]; then
          for f in meta/$n/META-INF/proguard/*.pro; do cp $f $out/rules/$n-$(basename $f); done
        fi
        if [ -d meta/$n/META-INF/services ]; then
          for f in meta/$n/META-INF/services/*; do cat $f >> $out/services/$(basename $f); echo >> $out/services/$(basename $f); done
        fi
      done
      sort -u -o $out/packages $out/packages
      runHook postBuild
    '';
    dontInstall = true;
    dontFixup = true;
  };

  # --- The app ---

  # Google Sans Code (OFL, github.com/googlefonts/googlesans-code) for
  # commands and output; the text font is the phone's own Google Sans Flex
  # where it has one (ui/Type.kt).
  googleSansCode = pkgs.fetchurl {
    url = "https://github.com/googlefonts/googlesans-code/releases/download/v7.001/GoogleSansCode-Android-v7.001.zip";
    hash = "sha256-PXyeec6E6CTneS8TGSnx9oUOiT6wDu/xvdkFJHhrMec=";
  };
  # res/ with the font added.
  appRes =
    pkgs.runCommand "prism-android-res"
      {
        nativeBuildInputs = [ pkgs.unzip ];
      }
      ''
        cp -r ${./res} $out
        chmod -R u+w $out
        mkdir -p $out/font fonts
        unzip -q ${googleSansCode} -d fonts
        cp "fonts/GoogleSansCode[MONO,wght].ttf" $out/font/google_sans_code.ttf
      '';

  # The compiled code (dex) and the resources, for every application ID: the
  # code doesn't depend on it.
  sources = lib.fileset.toSource {
    root = ./.;
    fileset = lib.fileset.unions [
      ./src
      ./AndroidManifest.xml
      ./proguard-rules.pro
    ];
  };

  classes = pkgs.stdenvNoCC.mkDerivation {
    pname = "prism-android-classes";
    version = versionName;
    src = sources;
    nativeBuildInputs = [
      jdk
      kotlin
      pkgs.zip
    ];
    buildPhase = ''
      runHook preBuild
      mkdir -p build/res build/gen build/kotlin build/java build/dex
      libjars=$(find ${libraries}/jars -name '*.jar' | LC_ALL=C sort | tr '\n' ':')
      cp=${androidJar}:$libjars${netbird}/classes.jar

      # R for the app and every library with resources (their code reads
      # R fields at run time, so the IDs only have to agree within this APK).
      sed -e 's/@APPLICATION_ID@/at.quickme.prism/g' -e 's/@LABEL@/Prism/g' -e 's/@CLEARTEXT@/false/g' AndroidManifest.xml > build/AndroidManifest.xml
      ${tools}/aapt2 compile --dir ${appRes} -o build/res/app.zip
      ${tools}/aapt2 link -o build/res.apk -I ${androidJar} \
        --manifest build/AndroidManifest.xml --auto-add-overlay --min-sdk-version ${minSdk} \
        --extra-packages $(paste -sd: ${libraries}/packages) \
        --java build/gen --proguard build/aapt-rules.pro \
        $(for r in ${libraries}/res/*.zip; do printf -- '-R %s ' $r; done) -R build/res/app.zip

      # Kotlin (with Compose) sees the Java sources; javac then compiles them
      # against Kotlin's classes.
      javaSources=$(find src build/gen -name '*.java')
      # The app's sources with shared/'s (commonMain, jvmMain) in one module.
      ${shared.kotlincArgs.jvm} build/kotlin.args $(find src -name '*.kt') $javaSources
      # kotlinc's default heap (512 MB) no longer holds the app with Compose.
      JAVA_OPTS="-Xmx3g -Xms256m -Xss4m" kotlinc -no-jdk -no-reflect -no-stdlib -jvm-target 17 -Werror \
        -Xplugin=${kotlin}/lib/compose-compiler-plugin.jar \
        -opt-in=androidx.compose.material3.ExperimentalMaterial3Api \
        -opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi \
        -opt-in=androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi \
        -classpath $cp -d build/kotlin @build/kotlin.args
      if [ -n "$javaSources" ]; then
        javac -Xlint:all,-serial,-options,-processing -Werror --release 17 -encoding UTF-8 \
          -classpath $cp:build/kotlin -d build/java $javaSources
      fi

      # R8: the app with the parts of the libraries it uses, as dex. Kept:
      # the manifest's components (aapt2's rules), the libraries' own rules,
      # ServiceLoader's classes and the app's rules (proguard-rules.pro).
      cat ${libraries}/services/* 2>/dev/null | sed 's/#.*//' | grep -v '^\s*$' | sort -u \
        | sed 's/.*/-keep class & { <init>(); }/' > build/service-rules.pro
      (cd build/kotlin && zip -q -r -X ../app-kotlin.jar .)
      (cd build/java && zip -q -r -X ../app-java.jar . 2>/dev/null || true)
      java -Xmx4g -cp ${tools}/lib/d8.jar com.android.tools.r8.R8 --release --min-api ${minSdk} \
        --lib ${androidJar} --output build/dex \
        --pg-conf proguard-rules.pro --pg-conf build/aapt-rules.pro --pg-conf build/service-rules.pro \
        $(for r in ${libraries}/rules/*.pro; do printf -- '--pg-conf %s ' $r; done) \
        build/app-kotlin.jar $( [ -s build/app-java.jar ] && echo build/app-java.jar ) \
        $(find ${libraries}/jars -name '*.jar' | LC_ALL=C sort) ${netbird}/classes.jar
      runHook postBuild
    '';
    # The JVM classes too, for the previews (they render the screens on a JVM).
    installPhase = ''
      mkdir -p $out
      cp build/dex/*.dex $out/
      mkdir -p $out/META-INF/services
      cp ${libraries}/services/* $out/META-INF/services/ 2>/dev/null || true
      mkdir -p $out/jvm
      cp build/app-kotlin.jar $out/jvm/kotlin.jar
      cp build/app-java.jar $out/jvm/java.jar
    '';
    dontFixup = true;
  };

  # The APK for one application ID.
  variant =
    {
      applicationId ? "at.quickme.prism",
      label ? "Prism",
      debuggable ? false,
      name ? "prism",
    }:
    pkgs.stdenvNoCC.mkDerivation {
      pname = "prism-android-${applicationId}";
      version = versionName;
      src = sources;
      nativeBuildInputs = [
        pkgs.zip
        pkgs.unzip
      ];
      buildPhase = ''
        runHook preBuild
        mkdir -p build/res
        # The manifest names providers by @APPLICATION_ID@ and the app by
        # @LABEL@; the code's package stays at.quickme.prism.
        sed -e 's/@APPLICATION_ID@/${applicationId}/g' -e 's/@LABEL@/${label}/g' \
          -e 's/@CLEARTEXT@/${lib.boolToString debuggable}/g' AndroidManifest.xml > build/AndroidManifest.xml
        ${tools}/aapt2 compile --dir ${appRes} -o build/res/app.zip
        ${tools}/aapt2 link -o build/base.apk -I ${androidJar} \
          --manifest build/AndroidManifest.xml --auto-add-overlay \
          --min-sdk-version ${minSdk} --target-sdk-version ${targetSdk} \
          --version-code ${toString versionCode} --version-name ${versionName} \
          ${
            lib.optionalString (
              applicationId != "at.quickme.prism"
            ) "--rename-manifest-package ${applicationId}"
          } \
          ${lib.optionalString debuggable "--debug-mode"} \
          $(for r in ${libraries}/res/*.zip; do printf -- '-R %s ' $r; done) -R build/res/app.zip
        cp build/base.apk build/unaligned.apk
        unaligned=$PWD/build/unaligned.apk
        mkdir -p build/dex && cp ${classes}/*.dex build/dex/ && chmod -R u+w build/dex
        touch -t 198001010000 build/dex/*
        (cd build/dex && zip -q -X "$unaligned" *.dex)
        (cd ${classes} && zip -q -X "$unaligned" META-INF/services/*)
        for abi in ${lib.concatStringsSep " " abis}; do
          (cd ${shellStarter} && zip -q -X -0 "$unaligned" lib/$abi/libprism_starter.so)
          (cd ${netbird} && zip -q -X -0 "$unaligned" lib/$abi/libgojni.so)
          (cd ${rockyPrism} && zip -q -X -0 "$unaligned" lib/$abi/librocky_prism_ffi.so)
          (cd ${aarNatives} && zip -q -X -0 "$unaligned" lib/$abi/*.so)
        done
        mkdir -p build/assets/licenses
        cp ${./shizuku/NOTICE} build/assets/licenses/NOTICE
        cp ${./shizuku/LICENSE.Shizuku} build/assets/licenses/LICENSE.Shizuku
        cp ${./shizuku/LICENSE.Shizuku-API} build/assets/licenses/LICENSE.Shizuku-API
        cp ${./netbird/NOTICE} build/assets/licenses/NOTICE.netbird
        unzip -q -o -j ${googleSansCode} OFL.txt -d build/assets/licenses
        mv build/assets/licenses/OFL.txt build/assets/licenses/OFL.GoogleSansCode
        touch -t 198001010000 build/assets/licenses/*
        (cd build && zip -q -X "$unaligned" assets/licenses/*)
        ${tools}/zipalign -P 16 -f 4 build/unaligned.apk build/aligned.apk
        ${tools}/zipalign -c -P 16 -v 4 build/aligned.apk > build/zipalign.txt
        for abi in ${lib.concatStringsSep " " abis}; do
          for so in libprism_starter libgojni librocky_prism_ffi $(cd ${aarNatives}/lib/$abi && ls *.so | sed 's/\.so$//'); do
            grep -q "lib/$abi/$so.so (OK)" build/zipalign.txt || { echo "lib/$abi/$so.so is not 16 KB aligned" >&2; exit 1; }
          done
        done
        runHook postBuild
      '';
      installPhase = ''
        install -Dm644 build/aligned.apk $out/${name}-unsigned.apk
        echo ${toString versionCode} > $out/version-code
      '';
    };

  # The release app, and builds that install next to it: another package id
  # (the classes keep theirs, so the admin component is
  # <applicationId>/at.quickme.prism.DeviceAdmin), its own provider
  # authorities and label, debuggable, and cleartext HTTP for a test server
  # behind `adb reverse`. A debug build updates from its own channel
  # (?package=) and is signed with a debug key, never the release key. It
  # must never get a netbird_setup_key: its VPN would replace the release
  # app's tunnel, and NetBird's socket directory is the release package's
  # (netbird/).
  apk = variant { };
  apk-debug = variant {
    applicationId = "at.quickme.prism.debug";
    label = "Prism Debug";
    debuggable = true;
    name = "prism-debug";
  };

  # The platform-independent core and the screens' pure logic on a JVM:
  # untrusted text, the metrics encoding, the hotspot token, the assistant's
  # tool arguments, weather, Matrix events, transcript and Markdown, and its
  # attachments (tests/).
  coreSources = lib.fileset.toSource {
    root = ./.;
    fileset = lib.fileset.unions [
      ./src/at/quickme/prism/core
      ./src/at/quickme/prism/assistant/core
      ./tests
    ];
  };
  # kotlinx-serialization (deps/lock.json) for the assistant's core.
  serializationJars = lib.concatMapStringsSep ":" (d: "${d.src}") (
    lib.filter (
      d:
      lib.hasPrefix "kotlinx-serialization-core-jvm-" d.file
      || lib.hasPrefix "kotlinx-serialization-json-jvm-" d.file
    ) fetched
  );

  # --- The previews ---

  # The renderer's libraries (deps/previews-lock.json): Paparazzi, layoutlib
  # with its runtime (fonts, ICU, native libraries for Linux and macOS) and
  # the framework's resources, and the @Preview annotation. jars/ for the
  # class path; runtime/ and resources/ unpacked, as layoutlib reads them.
  previewLocked = builtins.fromJSON (builtins.readFile ./deps/previews-lock.json);
  previewLibraries = pkgs.stdenvNoCC.mkDerivation {
    pname = "prism-android-preview-libraries";
    version = "1";
    dontUnpack = true;
    nativeBuildInputs = [ pkgs.unzip ];
    buildPhase = ''
      runHook preBuild
      mkdir -p $out/jars $out/runtime $out/resources
    ''
    + lib.concatMapStrings (
      d:
      let
        src = pkgs.fetchurl { inherit (d) url sha256; };
        name = lib.removeSuffix ".jar" (lib.removeSuffix ".aar" d.file);
      in
      if lib.hasPrefix "com.android.tools.layoutlib:layoutlib-runtime:" d.coordinate then
        "unzip -q ${src} -d $out/runtime -x 'META-INF/*' 'licenses/*'\n"
      else if lib.hasPrefix "com.android.tools.layoutlib:layoutlib-resources:" d.coordinate then
        "unzip -q ${src} -d $out/resources -x 'META-INF/*'\n"
      else if lib.hasSuffix ".aar" d.file then
        "unzip -q -p ${src} classes.jar > $out/jars/${name}.jar\n"
      else
        "cp ${src} $out/jars/${name}.jar\n"
    ) previewLocked
    + ''
      runHook postBuild
    '';
    dontInstall = true;
    dontFixup = true;
  };

  # The app's libraries' resources as they are in the AARs, which layoutlib
  # reads by name (the APK has them compiled), and every package with an R
  # class, the app's first.
  libraryResources =
    pkgs.runCommand "prism-android-library-resources" { nativeBuildInputs = [ pkgs.unzip ]; }
      (
        ''
          mkdir -p $out/aar
          echo at.quickme.prism > $out/packages
          cat ${libraries}/packages >> $out/packages
          touch $out/dirs
        ''
        + lib.concatMapStrings (
          d:
          let
            name = lib.removeSuffix ".aar" d.file;
          in
          lib.optionalString (lib.hasSuffix ".aar" d.file) ''
            unzip -q -o ${d.src} 'res/*' -d $out/aar/${name} 2>/dev/null || true
            if [ -d $out/aar/${name}/res ]; then echo $out/aar/${name}/res >> $out/dirs; fi
          ''
        ) fetched
      );

  # Google Sans Flex (OFL, from Google Fonts' repository): a Pixel has it as
  # the system font, which the app uses (ui/Type.kt); the renderer has none,
  # so it gets this copy (as static instances, below).
  googleSansFlex = pkgs.fetchurl {
    name = "GoogleSansFlex.ttf";
    url = "https://raw.githubusercontent.com/google/fonts/fa826bb2a3e26df8c1bee99351fc0d039aeed7d2/ofl/googlesansflex/GoogleSansFlex%5BROND%2Copsz%2Cslnt%2Cwdth%2Cwght%5D.ttf";
    hash = "sha256-qax3B5iFfV6KxZPO1Csi5Ap8CM7Jgxn5Kw7Mwng6d7o=";
  };

  # layoutlib draws a variable font at its default instance only, whatever
  # the weight asked for: the previews get static instances of both fonts
  # instead (ui/Type.kt), cut with fontTools. Google Sans Flex in every weight
  # the app uses, straight and rounded; Google Sans Code in its three.
  previewFonts =
    pkgs.runCommand "prism-android-preview-fonts"
      {
        nativeBuildInputs = [ (pkgs.python3.withPackages (p: [ p.fonttools ])) ];
      }
      ''
        mkdir -p $out
        for w in 300 400 500 600 700 800; do
          fonttools varLib.instancer -q ${googleSansFlex} wght=$w ROND=0 opsz=drop wdth=drop slnt=drop \
            -o $out/GoogleSansFlex-$w.ttf
          fonttools varLib.instancer -q ${googleSansFlex} wght=$w ROND=100 opsz=drop wdth=drop slnt=drop \
            -o $out/GoogleSansFlex-$w-Rounded.ttf
        done
        for w in 400 500 700; do
          fonttools varLib.instancer -q ${appRes}/font/google_sans_code.ttf wght=$w MONO=drop \
            -o $out/GoogleSansCode-$w.ttf
        done
      '';

  previewSources = lib.fileset.toSource {
    root = ./previews;
    fileset = ./previews;
  };

  # Every @Preview (android/previews), rendered to a PNG on the build machine
  # without a phone: previews/render/Render.kt drives layoutlib through
  # Paparazzi. The previews are compiled against the app's classes, as a
  # friend module (they see its internal declarations), and never go into
  # the APK.
  previews = pkgs.stdenvNoCC.mkDerivation {
    pname = "prism-android-previews";
    version = versionName;
    src = previewSources;
    # Paparazzi needs Java 21.
    nativeBuildInputs = [
      pkgs.jdk21_headless
      kotlin
    ];
    buildPhase = ''
      runHook preBuild
      libjars=$(find ${libraries}/jars -name '*.jar' | LC_ALL=C sort | tr '\n' ':')
      tooljars=$(find ${previewLibraries}/jars -name '*.jar' ! -name 'layoutlib-16*' | LC_ALL=C sort | tr '\n' ':')
      app=${classes}/jvm/kotlin.jar:${classes}/jvm/java.jar
      layoutlib=$(find ${previewLibraries}/jars -name 'layoutlib-16*.jar')
      compose() {
        JAVA_OPTS="-Xmx3g -Xss4m" kotlinc -no-reflect -no-stdlib -jvm-target 17 -Werror \
          -Xplugin=${kotlin}/lib/compose-compiler-plugin.jar \
          -opt-in=androidx.compose.material3.ExperimentalMaterial3Api \
          -opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi \
          -opt-in=androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi "$@"
      }
      # The previews against Android, the renderer on the JDK (with
      # layoutlib for Android).
      compose -no-jdk -module-name previews -Xfriend-paths=${classes}/jvm/kotlin.jar \
        -classpath ${androidJar}:$libjars$tooljars$app:${netbird}/classes.jar -d classes \
        $(find . -path ./render -prune -o -name '*.kt' -print)
      compose -module-name render -classpath $layoutlib:$libjars$tooljars -d render-classes render/*.kt

      # On the JVM, layoutlib is the Android framework; android.jar, last,
      # only supplies what layoutlib leaves out (android.os.Build, which
      # Paparazzi fills in from layoutlib's). Byte Buddy's agent patches
      # layoutlib, loaded up front rather than attached.
      mkdir -p $out
      ${lib.optionalString pkgs.stdenv.hostPlatform.isLinux ''
        # layoutlib's native libraries need libgcc_s, which no loader path has here.
        export LD_LIBRARY_PATH=${lib.makeLibraryPath [ pkgs.stdenv.cc.cc.lib ]}
      ''}
      java -Xmx3g -Djava.awt.headless=true \
        -javaagent:$(find ${previewLibraries}/jars -name 'byte-buddy-agent-*.jar') \
        -Dpaparazzi.layoutlib.runtime.root=${previewLibraries}/runtime \
        -Dpaparazzi.layoutlib.resources.root=${previewLibraries}/resources \
        -Dat.quickme.prism.previewFonts=${previewFonts} \
        -cp $layoutlib:$libjars$tooljars$app:${netbird}/classes.jar:classes:render-classes:${androidJar} \
        at.quickme.prism.previews.RenderKt \
        $out classes ${appRes} ${libraryResources}/packages ${libraryResources}/dirs
      runHook postBuild
    '';
    dontInstall = true;
    dontFixup = true;
    # layoutlib's native libraries: Linux on x86_64 only, macOS on both.
    meta.platforms = [
      "x86_64-linux"
      "x86_64-darwin"
      "aarch64-darwin"
    ];
  };
in
{
  inherit apk apk-debug variant;

  # The DPC work's debug build (at.quickme.prism.dpcdebug).
  apk-dpcdebug = variant {
    applicationId = "at.quickme.prism.dpcdebug";
    label = "Prism DPC debug";
    debuggable = true;
    name = "prism-dpcdebug";
  };

  # Signs the APK and uploads it to the Prism server, which the phone updates
  # itself from (publish.py); fleet runs it after each deploy.
  android-publish = pkgs.writeShellApplication {
    name = "android-publish";
    runtimeInputs = [ jdk ];
    text = ''
      PATH=${tools}:$PATH
      exec ${pkgs.python3}/bin/python3 -I ${./publish.py} \
        --apk ${apk}/prism-unsigned.apk --version-code ${toString versionCode} \
        --version-name ${versionName} "$@"
    '';
  };

  # Enrolls the phone over USB: installs Prism, makes it the Device Owner and
  # writes its settings (enroll.py); fleet wraps it as prism-phone-enroll.
  phone-enroll = pkgs.writeShellApplication {
    name = "phone-enroll";
    runtimeInputs = [
      jdk
      pkgs.sops
      pkgs.ssh-to-age
    ];
    # The adb already on PATH first: two adb versions kill each other's
    # server, and with it every other adb session on this Mac.
    text = ''
      PATH=${tools}:$PATH:${pkgs.android-tools}/bin
      exec ${pkgs.python3}/bin/python3 -I ${./enroll.py} \
        --apk ${apk}/prism-unsigned.apk --version-code ${toString versionCode} "$@"
    '';
  };

  check =
    pkgs.runCommand "prism-android-core-tests"
      {
        nativeBuildInputs = [
          jdk
          kotlin
          (pkgs.python3.withPackages (p: [ p.python-snappy ]))
        ];
      }
      ''
        cp -r ${coreSources} src && chmod -R u+w src && cd src
        kotlinc -jvm-target 17 -Werror -d classes \
          -Xplugin=${kotlin}/lib/kotlinx-serialization-compiler-plugin.jar \
          -classpath ${serializationJars} \
          $(find src -name '*.kt') $(find tests -path tests/live -prune -o -name '*.kt' -print)
        run() { java -cp classes:${kotlin}/lib/kotlin-stdlib.jar:${serializationJars} "$@"; }
        run CoreTestKt
        run AssistantTestKt
        run ConversationsTestKt
        run A2uiTestKt
        run OutboxTestKt
        run HotspotTestKt
        run AttachmentTestKt
        run MetricsTestKt remote-write.bin
        python3 tests/remote_write_decode.py remote-write.bin
        touch $out
      '';

  inherit
    previews
    previewLibraries
    sdk
    tools
    shellStarter
    netbird
    rockyPrism
    libraries
    classes
    ;
}
