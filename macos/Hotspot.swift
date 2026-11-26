// Asking the phone for its hotspot (docs/hotspot.md): in instant mode the
// phone keeps its hotspot off and scans, filtered in hardware, for a Bluetooth
// LE advertisement from this Mac. The Mac advertises while it has no network
// path (NWPathMonitor unsatisfied for 5 s) and for 3 minutes after "Ask the
// phone for its hotspot" in the popout, and stops as soon as a path is there
// again, which it is once macOS has joined the hotspot it already knows.
//
// The advertisement is one 128-bit service UUID, since that is all macOS
// lets an app advertise besides a name: a fixed prefix the phone's scan
// filter masks on, then an HMAC tag over the current 30 s window with the key
// both sides share. A new UUID each window keeps a recorded advertisement
// from being useful for long, and the phone's window check bounds the rest.
//
// A release (label "prism-hotspot-release/v1") is the same advertisement
// with another label: WiFiSwitch.swift sends it while the Mac is on the
// hotspot and a reliable Wi-Fi is in range (or after it joined that Wi-Fi),
// and the phone switches its instant hotspot off. The Mac can't tell instant
// from normal mode; the phone ignores a release in normal mode.
//
// While it asks (whether or not Bluetooth lets it advertise: a hotspot in
// normal mode is on anyway), WiFiSwitch.swift looks for the hotspot and joins
// it.

import CoreBluetooth
import CryptoKit
import Foundation
import Network

@MainActor
final class Hotspot: NSObject, ObservableObject {
    enum Reason: Equatable {
        /// No network path for 5 s.
        case offline
        /// The menu's request, until `until`.
        case asked(until: Date)
        /// A release: a reliable Wi-Fi network (`ssid`) is in range
        /// (WiFiSwitch.swift).
        case release(ssid: String)

        var text: String {
            switch self {
            case .offline: "no network path"
            case .asked: "asked from the menu"
            case .release(let ssid): "release: switching to \(ssid)"
            }
        }

        var label: String {
            if case .release = self { Hotspot.releaseLabel } else { Hotspot.requestLabel }
        }
    }

    /// Why it advertises right now (nil: it doesn't).
    @Published private(set) var advertising: Reason?
    /// Why it can't, shown in the popout after a request: no key, Bluetooth off.
    @Published private(set) var problem: String?

    /// The file with the key, from fleet's `hotspotKeyFile` (nil: not set up,
    /// and the popout shows no hotspot row).
    let keyFile: String?
    /// Joining the hotspot, and leaving it for a reliable Wi-Fi network.
    let wifi: WiFiSwitch

    nonisolated static let requestLabel = "prism-hotspot/v1"
    nonisolated static let releaseLabel = "prism-hotspot-release/v1"
    nonisolated static let prefix: [UInt8] = [0x51, 0x52, 0x28, 0xf6]
    static let window: TimeInterval = 30
    /// How long the path must stay unsatisfied: a Wi-Fi roam or a sleep wake
    /// drops it for a moment and shouldn't wake the phone's hotspot.
    static let offlineDelay: TimeInterval = 5
    static let askDuration: TimeInterval = 180

    private let monitor = NWPathMonitor()
    private var peripheral: CBPeripheralManager?
    private var satisfied = true
    private var offlineTimer: Timer?
    private var offline = false
    private var asked: Date?
    private var windowTimer: Timer?
    private var askTimer: Timer?
    private var keyMissingLogged = false
    private var advertisedUUID: CBUUID?
    /// The network a release is for, while it is advertised.
    private var releasing: String?
    /// While the Mac leaves the hotspot: no request when the path drops, which
    /// it does until macOS has joined the other network.
    private var holdOffline = false
    private var releaseAfterTimer: Timer?

    init(keyFile: String?, networks: WiFiNetworks? = nil) {
        self.keyFile = keyFile.flatMap { $0.isEmpty ? nil : $0 }
        wifi = WiFiSwitch(networks: networks ?? WiFiNetworks())
        super.init()
        wifi.hotspot = self
    }

    func start() {
        guard keyFile != nil else {
            log("hotspot: no hotspotKeyFile configured; not asking the phone for its hotspot")
            return
        }
        monitor.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == .satisfied
            MainActor.assumeIsolated { self?.pathChanged(satisfied: satisfied) }
        }
        monitor.start(queue: .main)
        wifi.start()
    }

    /// Advertises a release for `ssid` until `endRelease`, and holds requests
    /// back until `endHold`. False without a key.
    func release(ssid: String) -> Bool {
        guard key() != nil else { return false }
        releasing = ssid
        holdOffline = true
        update(stopReason: "")
        return true
    }

    /// No request while the path drops, until `endHold`: the Mac is leaving
    /// the hotspot for another network.
    func hold() {
        holdOffline = true
        update(stopReason: "")
    }

    /// The Mac left the hotspot for `ssid` and has working internet there:
    /// a release for 30 s switches the phone's instant hotspot off now.
    func releaseAfterLeaving(ssid: String) {
        guard release(ssid: ssid) else { return }
        releaseAfterTimer?.invalidate()
        releaseAfterTimer = Timer.scheduledTimer(withTimeInterval: WiFiSwitch.releaseDuration, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.endHold() }
        }
    }

    func endRelease(reason: String) {
        guard releasing != nil else { return }
        releasing = nil
        update(stopReason: reason)
    }

    /// Requests again while the path is down (after a release).
    func endHold() {
        releaseAfterTimer?.invalidate()
        releaseAfterTimer = nil
        releasing = nil
        holdOffline = false
        update(stopReason: "")
    }

    /// The menu's request: advertise for 3 minutes whatever the path, unless a
    /// path comes back in that time (the Mac joined the hotspot).
    func ask() {
        guard key() != nil else {
            problem = "No hotspot key in \(keyFile ?? "the configuration")."
            return
        }
        let until = Date().addingTimeInterval(Self.askDuration)
        asked = until
        askTimer?.invalidate()
        askTimer = Timer.scheduledTimer(withTimeInterval: Self.askDuration, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.asked = nil
                self?.update(stopReason: "the 3 minutes are over")
            }
        }
        update(stopReason: "")
    }

    // MARK: - The path

    private func pathChanged(satisfied: Bool) {
        let was = self.satisfied
        self.satisfied = satisfied
        if satisfied {
            offlineTimer?.invalidate()
            offlineTimer = nil
            offline = false
            // A path that comes back ends a request too: either the Mac joined
            // the hotspot, or it no longer needs it.
            if !was {
                asked = nil
                askTimer?.invalidate()
            }
            update(stopReason: "a network path is back")
        } else if was {
            offlineTimer?.invalidate()
            offlineTimer = Timer.scheduledTimer(withTimeInterval: Self.offlineDelay, repeats: false) { [weak self] _ in
                MainActor.assumeIsolated {
                    guard let self, !self.satisfied else { return }
                    self.offline = true
                    self.update(stopReason: "")
                }
            }
        }
    }

    // MARK: - Advertising

    /// Brings the advertisement in line with what is wanted; `stopReason` is
    /// logged if this stops it.
    private func update(stopReason: String) {
        let request: Reason? = if let asked, asked > Date() { .asked(until: asked) }
            else if offline && !holdOffline { .offline }
            else { nil }
        let wanted: Reason? = if let releasing { .release(ssid: releasing) } else { request }
        // Joining doesn't need Bluetooth or the key: the hotspot may be on.
        wifi.setWantsHotspot(request != nil && releasing == nil)
        guard let wanted else {
            problem = nil
            if advertising != nil {
                stop(reason: stopReason)
            }
            return
        }
        guard key() != nil else { return }
        let peripheral = self.peripheral ?? {
            // Created on first use: it is what asks for the Bluetooth
            // permission, which a Mac that never goes offline needn't see.
            let manager = CBPeripheralManager(delegate: self, queue: .main)
            self.peripheral = manager
            return manager
        }()
        guard peripheral.state == .poweredOn else {
            // peripheralManagerDidUpdateState calls update again.
            if peripheral.state != .unknown && peripheral.state != .resetting {
                problem = "Bluetooth is \(Self.describe(peripheral.state))."
            }
            return
        }
        problem = nil
        let previous = advertising
        advertising = wanted
        if previous != wanted {
            log("hotspot: advertising (\(wanted.text))")
        }
        if previous?.label != wanted.label {
            // A request after a release, or the other way round: the next
            // UUID is the new label's.
            advertise()
        }
    }

    private func stop(reason: String) {
        windowTimer?.invalidate()
        windowTimer = nil
        peripheral?.stopAdvertising()
        advertisedUUID = nil
        advertising = nil
        log("hotspot: stopped advertising (\(reason))")
    }

    /// Advertises the current window's UUID and comes back at the next window.
    private func advertise() {
        guard let peripheral, let advertising, let key = key() else { return }
        let now = Date().timeIntervalSince1970
        let window = UInt64(now / Self.window)
        let uuid = CBUUID(data: Self.uuid(key: key, window: window, label: advertising.label))
        if uuid != advertisedUUID {
            peripheral.stopAdvertising()
            peripheral.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [uuid]])
            advertisedUUID = uuid
        }
        let next = Double(window + 1) * Self.window - now
        windowTimer?.invalidate()
        windowTimer = Timer.scheduledTimer(withTimeInterval: next + 0.05, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.advertise() }
        }
    }

    // MARK: - The key

    /// The 32-byte key from `keyFile` (64 hex characters), read each time so a
    /// key that arrives later (a new secret on activation) is picked up.
    /// Logs once while it is missing.
    private func key() -> SymmetricKey? {
        guard let keyFile else { return nil }
        let text = (try? String(contentsOfFile: keyFile, encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let text, let bytes = Self.hex(text), bytes.count == 32 else {
            if !keyMissingLogged {
                keyMissingLogged = true
                log("hotspot: no key in \(keyFile) (64 hex characters); not asking the phone for its hotspot")
            }
            return nil
        }
        keyMissingLogged = false
        return SymmetricKey(data: bytes)
    }

    // MARK: - The contract

    /// The service UUID for `window` (floor(unix seconds / 30)): the prefix,
    /// then HMAC-SHA256(key, label || u64be(window))[0..12], label
    /// "prism-hotspot/v1" for a request, "prism-hotspot-release/v1" for a
    /// release.
    nonisolated static func uuid(key: SymmetricKey, window: UInt64, label: String = requestLabel) -> Data {
        var message = Data(label.utf8)
        withUnsafeBytes(of: window.bigEndian) { message.append(contentsOf: $0) }
        let tag = HMAC<SHA256>.authenticationCode(for: message, using: key)
        return Data(prefix) + Data(tag).prefix(12)
    }

    nonisolated static func hex(_ text: String) -> Data? {
        guard text.count % 2 == 0 else { return nil }
        var bytes = Data(capacity: text.count / 2)
        var index = text.startIndex
        while index < text.endIndex {
            let next = text.index(index, offsetBy: 2)
            guard let byte = UInt8(text[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        return bytes
    }

    /// The test vector in docs/hotspot.md's terms, for the self-test and
    /// `prism hotspot-uuid`: key 00 01 .. 1f, window 1000. The Android side
    /// checks the same value.
    nonisolated static let vectorKey = SymmetricKey(data: Data(0..<32))
    nonisolated static let vectorWindow: UInt64 = 1000
    nonisolated static let vectorUUID = "515228F6-A5AF-AB36-9705-08191B7F7CDE"
    nonisolated static let vectorReleaseUUID = "515228F6-D502-7623-F660-C1F921FE0527"

    nonisolated static func selfTest() -> Bool {
        // Both run (map), so a failure names every vector that is off.
        [(requestLabel, vectorUUID, "hotspot UUID"), (releaseLabel, vectorReleaseUUID, "hotspot release UUID")].map { label, expected, name in
            let uuid = CBUUID(data: uuid(key: vectorKey, window: vectorWindow, label: label)).uuidString
            let passed = uuid == expected
            print(passed
                  ? "self-test passed: \(name) for the test vector is \(uuid)"
                  : "self-test FAILED: \(name) \(uuid), expected \(expected)")
            return passed
        }.allSatisfy { $0 }
    }

    private static func describe(_ state: CBManagerState) -> String {
        switch state {
        case .poweredOff: "off"
        case .unauthorized: "not allowed for Prism (System Settings → Privacy & Security → Bluetooth)"
        case .unsupported: "not supported on this Mac"
        default: "not ready"
        }
    }

    nonisolated static func log(_ text: String) {
        FileHandle.standardError.write(Data("\(Date()) prism: \(text)\n".utf8))
    }

    private func log(_ text: String) {
        Self.log(text)
    }
}

extension Hotspot: CBPeripheralManagerDelegate {
    nonisolated func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        let state = peripheral.state
        MainActor.assumeIsolated {
            if state != .poweredOn, advertising != nil {
                // The advertisement is gone with the radio; it starts again
                // through update once Bluetooth is back.
                windowTimer?.invalidate()
                advertisedUUID = nil
                advertising = nil
                log("hotspot: stopped advertising (Bluetooth is \(Self.describe(state)))")
            }
            update(stopReason: "")
        }
    }

    nonisolated func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        guard let error else { return }
        let text = error.localizedDescription
        MainActor.assumeIsolated { log("hotspot: advertising failed: \(text)") }
    }
}
