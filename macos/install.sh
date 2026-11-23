#!/usr/bin/env bash
# Builds the Swift sources here into <dest>/Prism.app (default /Applications)
# and signs it. nix-darwin runs it on activation (fleet:
# traits/prism/services/prism.nix); a launchd agent runs the app.
#
# OWNER: the account whose running app is restarted on the new build
# (activation passes the fleet's admin user). SERVICE: that account's launchd
# job for the app (gui/<uid>/<label>), started right away after a new build
# instead of when launchd next retries it. BUILD_ONLY=1 restarts nothing.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEST_DIR="${1:-/Applications}"
APP="$DEST_DIR/Prism.app"
BUNDLE_ID="at.quickme.fleet.prism"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "Prism is a macOS app; nothing to do on $(uname -s)." >&2
  exit 0
fi

# CODESIGN: a signer `$CODESIGN IDENTIFIER PATH` (the fleet identity's, from
# activation). Without it the app is signed ad hoc.
CODESIGN="${CODESIGN:-}"

restart() {
  [[ -z "${BUILD_ONLY:-}" && -n "${OWNER:-}" ]] || return 0
  # The owner's launchd agent runs the same path: ending the running app
  # (not a `prism sign` in progress) makes it start the new build.
  if [[ -n "${SERVICE:-}" ]] && /bin/launchctl print "$SERVICE" >/dev/null 2>&1; then
    /bin/launchctl kickstart -k "$SERVICE" || true
  else
    /usr/bin/pkill -u "$OWNER" -f "^$APP/Contents/MacOS/Prism --config " || true
  fi
}

SOURCES=("$SCRIPT_DIR"/*.swift)

# PrismShared.framework: the code shared with the Android app (shared/,
# Kotlin/Native), a static framework linked into the executable. Nix builds
# it: lib.macosApp has it next to this script; from a checkout, nix builds
# it here (or PRISM_SHARED_FRAMEWORK names one).
SHARED_FRAMEWORK="${PRISM_SHARED_FRAMEWORK:-$SCRIPT_DIR/PrismShared.framework}"
if [[ ! -d "$SHARED_FRAMEWORK" ]]; then
  SHARED_FRAMEWORK="$(nix --extra-experimental-features 'nix-command flakes' build --no-link --print-out-paths \
    "path:$SCRIPT_DIR/..#prism-shared-framework")/PrismShared.framework"
fi
SHARED_FRAMEWORK="$(cd -P -- "$SHARED_FRAMEWORK" && pwd)"

SOURCE_HASH="$({ cat "${SOURCES[@]}" "$0"; echo "shared:$SHARED_FRAMEWORK"; echo "signer:${CODESIGN:+fleet}"; } | shasum -a 256 | cut -d' ' -f1)"
if [[ -d "$APP" ]] && [[ "$(/usr/libexec/PlistBuddy -c 'Print :FleetSourceHash' "$APP/Contents/Info.plist" 2>/dev/null)" == "$SOURCE_HASH" ]]; then
  echo "$APP is up to date."
  exit 0
fi

BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
BUILT="$BUILD_DIR/Prism.app"
mkdir -p "$BUILT/Contents/MacOS"

SDK="$(/usr/bin/xcrun --sdk macosx --show-sdk-path)"
export CLANG_MODULE_CACHE_PATH="$BUILD_DIR/module-cache"
/usr/bin/xcrun --sdk macosx swiftc \
  -O -swift-version 5 \
  -sdk "$SDK" -target "$(uname -m)-apple-macos26.0" \
  -module-cache-path "$BUILD_DIR/module-cache" \
  -module-name Prism \
  -F "$(dirname "$SHARED_FRAMEWORK")" -framework PrismShared -lc++ \
  -Xlinker -dead_strip \
  -o "$BUILT/Contents/MacOS/Prism" \
  "${SOURCES[@]}"

# A build whose signatures ssh-keygen rejects, or whose shared module fails,
# never replaces a working app.
"$BUILT/Contents/MacOS/Prism" self-test

cat > "$BUILT/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>$BUNDLE_ID</string>
  <key>CFBundleName</key><string>Prism</string>
  <key>CFBundleDisplayName</key><string>Prism</string>
  <key>CFBundleExecutable</key><string>Prism</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>2.0</string>
  <key>CFBundleVersion</key><string>2</string>
  <key>LSMinimumSystemVersion</key><string>26.0</string>
  <key>LSUIElement</key><true/>
  <key>NSBluetoothAlwaysUsageDescription</key><string>Prism asks your phone over Bluetooth to switch on its hotspot when this Mac is offline.</string>
  <key>NSLocationUsageDescription</key><string>Prism reads the names of nearby Wi-Fi networks to leave your phone's hotspot for a Wi-Fi network that worked before.</string>
  <key>NSLocationWhenInUseUsageDescription</key><string>Prism reads the names of nearby Wi-Fi networks to leave your phone's hotspot for a Wi-Fi network that worked before.</string>
  <key>NSHumanReadableCopyright</key><string>fleet</string>
  <key>FleetSourceHash</key><string>$SOURCE_HASH</string>
</dict>
</plist>
EOF

if [[ -n "$CODESIGN" ]]; then
  "$CODESIGN" "$BUNDLE_ID" "$BUILT"
else
  codesign --force --sign - --identifier "$BUNDLE_ID" "$BUILT"
fi

rm -rf "$APP"
ditto "$BUILT" "$APP"
echo "Installed $APP."

restart
