// The Wi-Fi networks WiFiSwitch.swift works with, kept in UserDefaults next to
// the app's other settings and edited in the popout's Wi-Fi tab
// (WiFiView.swift):
//
// - Reliable networks: the Mac leaves a hotspot for one of these when it is in
//   range. The user adds and removes them; with "Learn" on, a network on
//   which the captive probe saw working internet is added too, unless the
//   user removed it before (then it is ignored) or it is a hotspot.
// - The phone's hotspot: the networks the Mac joins itself after asking the
//   phone (Hotspot.swift), and never a reliable network. A network whose
//   DHCP lease carries Android's ANDROID_METERED is added on its own.
//
// WiFiPolicy holds the decisions as pure functions, checked by the
// self-test without the Wi-Fi radio.

import Foundation
import Security

@MainActor
final class WiFiNetworks: ObservableObject {
    /// Reliable network → when it last had working internet (unix seconds;
    /// 0: added by the user and not seen working yet). The key predates the
    /// tab, so networks learned before it stay.
    nonisolated static let reliableKey = "hotspotProvenNetworks"
    /// Reliable networks the user added: never removed on their own.
    nonisolated static let addedKey = "wifiReliableAdded"
    /// Networks the user removed: not learned again.
    nonisolated static let ignoredKey = "wifiIgnoredNetworks"
    /// The phone's hotspot (network names, in the order the user sees).
    nonisolated static let hotspotsKey = "wifiPhoneHotspots"
    /// Whether networks with working internet join the reliable list.
    nonisolated static let learnKey = "wifiLearnNetworks"

    struct Reliable: Identifiable, Equatable {
        var ssid: String
        /// When it last had working internet; nil: not seen yet.
        var worked: Date?
        var addedByUser: Bool
        var id: String { ssid }
    }

    @Published private(set) var reliable: [String: Double] = [:]
    @Published private(set) var added: Set<String> = []
    @Published private(set) var ignored: Set<String> = []
    @Published private(set) var hotspots: [String] = []
    @Published var learns = true {
        didSet { defaults.set(learns, forKey: Self.learnKey) }
    }

    let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        reload()
    }

    func reload() {
        reliable = defaults.dictionary(forKey: Self.reliableKey)?.compactMapValues { ($0 as? NSNumber)?.doubleValue } ?? [:]
        added = Set(defaults.stringArray(forKey: Self.addedKey) ?? [])
        ignored = Set(defaults.stringArray(forKey: Self.ignoredKey) ?? [])
        hotspots = defaults.stringArray(forKey: Self.hotspotsKey) ?? []
        learns = defaults.object(forKey: Self.learnKey) as? Bool ?? true
    }

    /// The reliable list for the tab: most recently working first.
    var list: [Reliable] {
        reliable
            .map { Reliable(ssid: $0.key, worked: $0.value > 0 ? Date(timeIntervalSince1970: $0.value) : nil, addedByUser: added.contains($0.key)) }
            .sorted { ($0.worked ?? .distantPast, $1.ssid) > ($1.worked ?? .distantPast, $0.ssid) }
    }

    var reliableSet: Set<String> { Set(reliable.keys) }
    var hotspotSet: Set<String> { Set(hotspots) }

    // MARK: - The user's edits

    func addReliable(_ name: String) {
        guard let ssid = Self.clean(name) else { return }
        if reliable[ssid] == nil { reliable[ssid] = 0 }
        added.insert(ssid)
        ignored.remove(ssid)
        hotspots.removeAll { $0 == ssid }
        save()
    }

    func removeReliable(_ ssid: String) {
        reliable[ssid] = nil
        added.remove(ssid)
        ignored.insert(ssid)
        save()
    }

    func addHotspot(_ name: String) {
        guard let ssid = Self.clean(name) else { return }
        if !hotspots.contains(ssid) { hotspots.append(ssid) }
        reliable[ssid] = nil
        added.remove(ssid)
        save()
    }

    func removeHotspot(_ ssid: String) {
        hotspots.removeAll { $0 == ssid }
        HotspotPassword.set(nil, for: ssid)
        save()
    }

    // MARK: - What WiFiSwitch saw

    /// Working internet on `ssid` (not a hotspot). True when it newly joined
    /// the reliable list.
    @discardableResult
    func worked(_ ssid: String, at date: Date = Date()) -> Bool {
        switch WiFiPolicy.learning(ssid: ssid, reliable: reliableSet, ignored: ignored, hotspots: hotspotSet, learns: learns) {
        case .update:
            reliable[ssid] = date.timeIntervalSince1970
            save()
            return false
        case .add:
            reliable[ssid] = date.timeIntervalSince1970
            save()
            return true
        case .none:
            return false
        }
    }

    /// The Mac is on a hotspot: `android` when its lease carries
    /// ANDROID_METERED (the phone's), else a metered Wi-Fi (another phone).
    /// A learned entry for it goes; one the user added stays. Returns what
    /// changed, for the log.
    func sawHotspot(_ ssid: String, android: Bool) -> String? {
        var changes: [String] = []
        if reliable[ssid] != nil, !added.contains(ssid) {
            reliable[ssid] = nil
            changes.append("no longer a reliable network")
        }
        if android, !hotspots.contains(ssid) {
            hotspots.append(ssid)
            changes.append("added as the phone's hotspot")
        }
        guard !changes.isEmpty else { return nil }
        save()
        return changes.joined(separator: ", ")
    }

    private func save() {
        defaults.set(reliable, forKey: Self.reliableKey)
        defaults.set(added.sorted(), forKey: Self.addedKey)
        defaults.set(ignored.sorted(), forKey: Self.ignoredKey)
        defaults.set(hotspots, forKey: Self.hotspotsKey)
    }

    /// A network name as typed: trimmed, at most 32 bytes (an SSID's limit).
    nonisolated static func clean(_ name: String) -> String? {
        let ssid = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !ssid.isEmpty, ssid.utf8.count <= 32 else { return nil }
        return ssid
    }
}

/// WiFiSwitch's decisions, without the radio.
enum WiFiPolicy {
    struct Seen: Equatable {
        /// The Wi-Fi network is the phone's hotspot: a listed name, or
        /// ANDROID_METERED in its lease.
        var phoneHotspot: Bool
        /// Any hotspot: the phone's, or a Wi-Fi macOS marks expensive
        /// (another phone's hotspot); the Mac leaves it for a reliable one.
        var hotspot: Bool
    }

    static func classify(ssid: String?, androidMetered: Bool, expensive: Bool, hotspots: Set<String>) -> Seen {
        let phone = androidMetered || ssid.map(hotspots.contains) == true
        return Seen(phoneHotspot: phone, hotspot: phone || expensive)
    }

    /// The reliable network to leave a hotspot for: the strongest in range
    /// at `minimumRSSI` or better, not the current network, not a hotspot,
    /// not skipped.
    static func target(
        scan: [String: Int], reliable: Set<String>, hotspots: Set<String>, current: String?,
        skipped: [String: Date], now: Date, minimumRSSI: Int
    ) -> (ssid: String, rssi: Int)? {
        scan
            .filter { ssid, rssi in
                rssi >= minimumRSSI && reliable.contains(ssid) && !hotspots.contains(ssid) && ssid != current
                    && (skipped[ssid].map { $0 <= now } ?? true)
            }
            .max { ($0.value, $1.key) < ($1.value, $0.key) }
            .map { ($0.key, $0.value) }
    }

    /// Which of the phone's hotspot names to join from a scan: the strongest
    /// one in range.
    static func hotspotToJoin(scan: [String: Int], hotspots: [String]) -> String? {
        hotspots
            .compactMap { ssid in scan[ssid].map { (ssid, $0) } }
            .max { ($0.1, $1.0) < ($1.1, $0.0) }?.0
    }

    enum Learning: Equatable { case none, update, add }

    /// Working internet on a network that is not a hotspot: update a reliable
    /// one's time, or learn a new one.
    static func learning(ssid: String, reliable: Set<String>, ignored: Set<String>, hotspots: Set<String>, learns: Bool) -> Learning {
        if hotspots.contains(ssid) { return .none }
        if reliable.contains(ssid) { return .update }
        return learns && !ignored.contains(ssid) ? .add : .none
    }

    /// After `failures` probes in a row without working internet on a Wi-Fi
    /// that isn't the phone's hotspot: ask the phone and join its hotspot.
    /// Only on a network that should work (reliable) or another hotspot; an
    /// unknown network may be a captive portal the user is signing in to.
    static func asksPhone(failures: Int, ssid: String?, reliable: Set<String>, seen: Seen) -> Bool {
        guard failures >= 2, !seen.phoneHotspot else { return false }
        return seen.hotspot || ssid.map(reliable.contains) == true
    }

    static func selfTest() -> Bool {
        var failures: [String] = []
        func check(_ ok: Bool, _ name: String) { if !ok { failures.append(name) } }
        let now = Date(timeIntervalSince1970: 1_000_000)

        let hotspots: Set = ["Phone"]
        check(classify(ssid: "Phone", androidMetered: false, expensive: false, hotspots: hotspots) == Seen(phoneHotspot: true, hotspot: true), "listed hotspot")
        check(classify(ssid: nil, androidMetered: true, expensive: false, hotspots: hotspots) == Seen(phoneHotspot: true, hotspot: true), "ANDROID_METERED without a name")
        check(classify(ssid: "Other phone", androidMetered: false, expensive: true, hotspots: hotspots) == Seen(phoneHotspot: false, hotspot: true), "expensive Wi-Fi")
        check(classify(ssid: "Home", androidMetered: false, expensive: false, hotspots: hotspots) == Seen(phoneHotspot: false, hotspot: false), "plain Wi-Fi")

        let scan = ["Home": -60, "Office": -50, "Weak": -80, "Phone": -30, "Cafe": -40]
        let reliable: Set = ["Home", "Office", "Weak", "Phone"]
        check(target(scan: scan, reliable: reliable, hotspots: hotspots, current: "Phone", skipped: [:], now: now, minimumRSSI: -70)?.ssid == "Office", "strongest reliable")
        check(target(scan: scan, reliable: reliable, hotspots: hotspots, current: "Phone",
                     skipped: ["Office": now.addingTimeInterval(60)], now: now, minimumRSSI: -70)?.ssid == "Home", "skipped network")
        check(target(scan: scan, reliable: reliable, hotspots: hotspots, current: "Phone",
                     skipped: ["Office": now.addingTimeInterval(-60)], now: now, minimumRSSI: -70)?.ssid == "Office", "skip over")
        check(target(scan: scan, reliable: ["Weak", "Phone"], hotspots: hotspots, current: nil, skipped: [:], now: now, minimumRSSI: -70) == nil, "too weak, or a hotspot")
        check(target(scan: scan, reliable: ["Office"], hotspots: [], current: "Office", skipped: [:], now: now, minimumRSSI: -70) == nil, "already on it")
        check(target(scan: [:], reliable: reliable, hotspots: hotspots, current: nil, skipped: [:], now: now, minimumRSSI: -70) == nil, "empty scan")

        check(hotspotToJoin(scan: scan, hotspots: ["Gone", "Phone"]) == "Phone", "hotspot in range")
        check(hotspotToJoin(scan: ["A": -70, "B": -50], hotspots: ["A", "B"]) == "B", "strongest hotspot")
        check(hotspotToJoin(scan: scan, hotspots: []) == nil, "no hotspot names")

        check(learning(ssid: "Home", reliable: ["Home"], ignored: [], hotspots: [], learns: false) == .update, "update while not learning")
        check(learning(ssid: "New", reliable: [], ignored: [], hotspots: [], learns: true) == .add, "learn")
        check(learning(ssid: "New", reliable: [], ignored: [], hotspots: [], learns: false) == .none, "learning off")
        check(learning(ssid: "Gone", reliable: [], ignored: ["Gone"], hotspots: [], learns: true) == .none, "removed stays removed")
        check(learning(ssid: "Phone", reliable: ["Phone"], ignored: [], hotspots: hotspots, learns: true) == .none, "hotspot never reliable")

        let plain = Seen(phoneHotspot: false, hotspot: false)
        check(asksPhone(failures: 2, ssid: "Home", reliable: ["Home"], seen: plain), "broken reliable network")
        check(!asksPhone(failures: 1, ssid: "Home", reliable: ["Home"], seen: plain), "one failure")
        check(!asksPhone(failures: 3, ssid: "Hotel", reliable: ["Home"], seen: plain), "unknown network (captive portal)")
        check(asksPhone(failures: 2, ssid: "Other phone", reliable: [], seen: Seen(phoneHotspot: false, hotspot: true)), "broken other hotspot")
        check(!asksPhone(failures: 5, ssid: "Phone", reliable: [], seen: Seen(phoneHotspot: true, hotspot: true)), "the phone's own hotspot")

        // `ipconfig getpacket` on an Android hotspot (addresses left out).
        let packet = """
            options:
            Options count is 9
            dhcp_message_type (uint8): ACK 0x5
            router (ip_mult): {10.0.0.1}
            vendor_specific (opaque):\u{20}
            0000  41 4e 44 52 4f 49 44 5f  4d 45 54 45 52 45 44     ANDROID_METERED\u{20}
            end (none):\u{20}
            """
        check(WiFiSwitch.parseVendorSpecific(packet) == Data("ANDROID_METERED".utf8), "getpacket's option 43")
        let long = """
            vendor_specific (opaque):
            0000  01 02 03 04 05 06 07 08  09 0a 0b 0c 0d 0e 0f 10  ................
            0010  41 42                                             AB
            end (none):
            """
        check(WiFiSwitch.parseVendorSpecific(long) == Data((1...16).map { UInt8($0) } + [0x41, 0x42]), "getpacket's dump over two lines")
        check(WiFiSwitch.parseVendorSpecific("router (ip_mult): {10.0.0.1}\nend (none):") == nil, "no option 43")

        // The settings, in a defaults domain of their own (one name, so
        // builds don't leave a file each), emptied before and after.
        let suite = "at.quickme.fleet.prism.self-test"
        if let defaults = UserDefaults(suiteName: suite) {
            let keys = [WiFiNetworks.reliableKey, WiFiNetworks.addedKey, WiFiNetworks.ignoredKey, WiFiNetworks.hotspotsKey, WiFiNetworks.learnKey]
            let empty = { for key in keys { defaults.removeObject(forKey: key) } }
            empty()
            defer {
                empty()
                defaults.removePersistentDomain(forName: suite)
            }
            defaults.set(["Old": 1000.0, "Phone": 2000.0], forKey: WiFiNetworks.reliableKey)
            MainActor.assumeIsolated {
                let networks = WiFiNetworks(defaults: defaults)
                check(networks.reliableSet == ["Old", "Phone"], "networks learned before the tab")
                check(networks.worked("New", at: now), "learned network")
                check(networks.sawHotspot("Phone", android: true) != nil, "hotspot moves out")
                check(networks.hotspots == ["Phone"] && networks.reliable["Phone"] == nil, "hotspot listed")
                networks.removeReliable("New")
                check(!networks.worked("New", at: now) && networks.reliable["New"] == nil, "removed not learned again")
                networks.addReliable("  New ")
                check(networks.added.contains("New") && networks.reliable["New"] == 0, "added by the user")
                check(networks.sawHotspot("New", android: false) == nil && networks.reliable["New"] != nil, "user's entry stays")
                networks.addReliable("Phone")
                check(networks.hotspots.isEmpty, "a reliable network is not the hotspot")
                networks.addReliable("   ")
                networks.addHotspot(String(repeating: "x", count: 33))
                check(networks.reliable.count == 3 && networks.hotspots.isEmpty, "bad names refused")
                networks.learns = false
                let again = WiFiNetworks(defaults: defaults)
                check(again.reliableSet == ["Old", "New", "Phone"] && again.ignored.isEmpty && !again.learns, "saved")
                check(again.list.first?.ssid == "Old" && again.list.last?.worked == nil, "list order")
            }
        } else {
            failures.append("defaults suite")
        }

        print(failures.isEmpty
              ? "self-test passed: Wi-Fi decisions (hotspot, reliable networks, learning, asking)"
              : "self-test FAILED: Wi-Fi decisions: \(failures.joined(separator: ", "))")
        return failures.isEmpty
    }
}

/// The phone's hotspot password, when macOS can't join it from its own known
/// network: Prism's item in the login keychain (generic password, one per
/// network name). Never logged.
enum HotspotPassword {
    static let service = "at.quickme.fleet.prism.wifi-hotspot"

    static func get(_ ssid: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: ssid, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func has(_ ssid: String) -> Bool { get(ssid) != nil }

    /// Stores `password` for `ssid` (nil or empty: removes it).
    @discardableResult
    static func set(_ password: String?, for ssid: String) -> Bool {
        let match: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: ssid,
        ]
        SecItemDelete(match as CFDictionary)
        guard let password, !password.isEmpty else { return true }
        var item = match
        item[kSecValueData as String] = Data(password.utf8)
        item[kSecAttrLabel as String] = "Prism: Wi-Fi hotspot \(ssid)"
        return SecItemAdd(item as CFDictionary, nil) == errSecSuccess
    }
}
