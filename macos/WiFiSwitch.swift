// Joining the phone's hotspot and leaving it for a reliable Wi-Fi network
// (docs/hotspot.md, "Joining" and "Leaving"). The networks come from
// WiFiNetworks.swift (the popout's Wi-Fi tab); the decisions from WiFiPolicy.
//
// - Which network the Mac is on: the phone's hotspot is a network named in
//   the Wi-Fi tab, or one whose DHCP lease carries Android's vendor option
//   43, "ANDROID_METERED" (such a network is added to the tab). configd's
//   DHCP info keeps only the options macOS asked for, never 43, so the lease
//   comes from `ipconfig getpacket` (vendor_specific). A Wi-Fi macOS marks
//   expensive (another phone's hotspot) counts as a hotspot to leave, but
//   isn't the phone's.
// - Joining: while Hotspot.swift asks the phone (offline, or "Ask the phone"
//   in the popout), it scans for the hotspot's names every few seconds and
//   joins the strongest itself (CoreWLAN), first as the known network macOS
//   keeps, then with a password saved in the Wi-Fi tab. macOS's own auto-join
//   is slow to scan while offline and never leaves a network it is on, even
//   a broken one.
// - Working internet: on a Wi-Fi network that is not the phone's hotspot, a
//   captive probe (http://captive.apple.com/hotspot-detect.html answers
//   "Success") on path changes and every 10 minutes updates the network's
//   time in the reliable list, or learns it there. Two failed probes in a row
//   (30 s apart) on a reliable network or another hotspot ask the phone and
//   join its hotspot; the broken network is skipped for 30 minutes.
// - Leaving: on a hotspot it scans every 60 s (without leaving it). For a
//   reliable network at -70 dBm or better it joins that network itself and
//   probes it 15 s later: "Success" keeps it and advertises a release for
//   30 s (the phone switches its instant hotspot off at once); anything else
//   skips the network for 30 minutes and goes back to the hotspot. If macOS
//   can't join the network, the release alone does it as before: the phone
//   switches its hotspot off and macOS auto-joins the best known network.
//
// Network names come from CoreWLAN only with Location permission for Prism,
// asked for once when hotspot support is configured.

import CoreLocation
import CoreWLAN
import Foundation
import Network
import SystemConfiguration

@MainActor
final class WiFiSwitch: NSObject, ObservableObject {
    enum State: Equatable {
        /// Not on a hotspot.
        case away
        /// On a hotspot, scanning for a reliable network.
        case onHotspot
        /// Joining the reliable network `to` from a hotspot.
        case leaving(to: String)
        /// A release for `to` is advertised (macOS couldn't join it itself).
        case switching(to: String)
        /// On the new network; the probe is due.
        case checking(String)
    }

    /// What the Mac is on, for the Wi-Fi tab.
    struct Current: Equatable {
        var ssid: String?
        var onWiFi = false
        var phoneHotspot = false
        var hotspot = false
    }

    @Published private(set) var state = State.away
    /// Why it can't see network names (Location permission), for the popout.
    @Published private(set) var problem: String?
    /// Looking for the phone's hotspot to join it (Hotspot.swift asks).
    @Published private(set) var joining = false
    /// The last time joining the hotspot failed, and why, for the Wi-Fi tab.
    @Published private(set) var joinError: String?
    @Published private(set) var current = Current()
    /// The last scan for the Wi-Fi tab: network name → RSSI.
    @Published private(set) var nearby: [String: Int] = [:]
    @Published private(set) var scanningNearby = false

    let networks: WiFiNetworks
    weak var hotspot: Hotspot?

    static let scanInterval: TimeInterval = 60
    /// The first scan on a hotspot: after the link has settled.
    static let firstScanDelay: TimeInterval = 10
    static let proveInterval: TimeInterval = 600
    /// After a failed probe: the next one, to tell a blip from a broken network.
    static let reproveInterval: TimeInterval = 30
    static let minimumRSSI = -70
    static let releaseDuration: TimeInterval = 30
    static let checkDelay: TimeInterval = 15
    static let skipDuration: TimeInterval = 1800
    /// While asking the phone: a scan for its hotspot this often, and less
    /// often once it hasn't shown up for `joinSlowAfter`.
    static let joinInterval: TimeInterval = 5
    static let joinSlowInterval: TimeInterval = 30
    static let joinSlowAfter: TimeInterval = 180
    /// After macOS refused to join the hotspot.
    static let joinFailedPause: TimeInterval = 20
    /// After a release the phone didn't act on (normal mode, out of
    /// range): no other release for this long.
    static let ignoredPause: TimeInterval = 600
    /// Path changes come in bursts, and configd's lease lags the link.
    static let settleDelay: TimeInterval = 2
    nonisolated static let meteredMarker = Data("ANDROID_METERED".utf8)

    private let monitor = NWPathMonitor(requiredInterfaceType: .wifi)
    /// macOS marks the Wi-Fi path expensive (a phone's hotspot).
    private var expensive = false
    private var location: CLLocationManager?
    private var started = false
    private var settleTimer: Timer?
    private var scanTimer: Timer?
    private var proveTimer: Timer?
    private var watchTimer: Timer?
    private var releaseTimer: Timer?
    private var checkTimer: Timer?
    private var joinTimer: Timer?
    private var scanning = false
    private var proving = false
    private var joinBusy = false
    private var joinStarted: Date?
    private var joinPausedUntil: Date?
    private var joinMissingLogged = false
    private var joinLookingLogged = false
    /// Probes in a row without working internet on `failedSSID`.
    private var failures = 0
    private var failedSSID: String?
    /// The hotspot's own network name, never a target.
    private var hotspotSSID: String?
    /// The network it is leaving for, from the scan until the check.
    private var target: String?
    /// Whether it left the phone's hotspot (then a release follows).
    private var leftPhone = false
    private var leftAt: Date?
    private var skipped: [String: Date] = [:]
    private var pausedUntil: Date?
    /// `prism preview`: sample state, nothing read from the system.
    private var isPreview = false

    init(networks: WiFiNetworks? = nil) {
        self.networks = networks ?? WiFiNetworks()
        super.init()
    }

    func start() {
        guard !started else { return }
        started = true
        let manager = CLLocationManager()
        // The status arrives through the delegate, also right after this;
        // asking happens there while it is still undetermined.
        manager.delegate = self
        location = manager
        monitor.pathUpdateHandler = { [weak self] path in
            let expensive = path.status == .satisfied && path.isExpensive
            MainActor.assumeIsolated {
                self?.expensive = expensive
                self?.pathChanged()
            }
        }
        monitor.start(queue: .main)
    }

    // MARK: - What the Mac is on

    private func pathChanged() {
        settleTimer?.invalidate()
        settleTimer = Timer.scheduledTimer(withTimeInterval: Self.settleDelay, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.evaluate() }
        }
    }

    private func now() -> Now {
        Self.current(hotspots: networks.hotspotSet, expensive: expensive)
    }

    /// Reads the current network for the Wi-Fi tab.
    func refreshCurrent() {
        guard !isPreview else { return }
        let now = now()
        current = Current(ssid: now.ssid, onWiFi: now.onWiFi, phoneHotspot: now.seen.phoneHotspot, hotspot: now.seen.hotspot)
    }

    private func evaluate() {
        let now = now()
        current = Current(ssid: now.ssid, onWiFi: now.onWiFi, phoneHotspot: now.seen.phoneHotspot, hotspot: now.seen.hotspot)
        if target != nil {
            if case .switching = state { watch() }
            return
        }
        if now.seen.hotspot {
            if let ssid = now.ssid {
                if now.seen.phoneHotspot { hotspotSSID = ssid }
                if let change = networks.sawHotspot(ssid, android: now.androidMetered) {
                    log("wifi: \(ssid): \(change)")
                }
            }
            if now.seen.phoneHotspot, joining {
                stopJoining(reason: "on the phone's hotspot")
            }
            if state != .onHotspot {
                let kind = now.seen.phoneHotspot ? "the phone's hotspot" : "a hotspot (metered Wi-Fi)"
                log("wifi: on \(kind)\(now.ssid.map { " (\($0))" } ?? "")")
                state = .onHotspot
            }
            if scanTimer == nil {
                scanTimer = Timer.scheduledTimer(withTimeInterval: Self.scanInterval, repeats: true) { [weak self] _ in
                    MainActor.assumeIsolated { self?.scan() }
                }
                Timer.scheduledTimer(withTimeInterval: Self.firstScanDelay, repeats: false) { [weak self] _ in
                    MainActor.assumeIsolated { self?.scan() }
                }
            }
        } else {
            scanTimer?.invalidate()
            scanTimer = nil
            if state == .onHotspot {
                log("wifi: off the hotspot")
            }
            state = .away
        }
        // Another phone's hotspot can be broken too: probe everything but the
        // phone's own.
        if now.onWiFi, !now.seen.phoneHotspot {
            prove()
            if proveTimer == nil { scheduleProve(after: Self.proveInterval) }
        } else {
            proveTimer?.invalidate()
            proveTimer = nil
        }
    }

    // MARK: - Working internet

    private func scheduleProve(after interval: TimeInterval) {
        proveTimer?.invalidate()
        proveTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.proveTimer = nil
                self.prove()
                if self.proveTimer == nil, self.now().onWiFi { self.scheduleProve(after: Self.proveInterval) }
            }
        }
    }

    /// Probes the current Wi-Fi network (not the phone's hotspot): "Success"
    /// keeps or learns it as reliable; a failure skips a reliable one for 30
    /// minutes, so the Mac doesn't come back to it from the hotspot while it
    /// is broken, and probes again in 30 s. The second failure in a row asks
    /// the phone for its hotspot (WiFiPolicy.asksPhone).
    private func prove() {
        guard !proving, target == nil else { return }
        let before = now()
        guard before.onWiFi, !before.seen.phoneHotspot else { return }
        proving = true
        Self.probe { [weak self] ok in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.proving = false
                let now = self.now()
                // A network that changed during the probe gets the next one.
                guard self.target == nil, now.onWiFi, !now.seen.phoneHotspot, let ssid = now.ssid, ssid == before.ssid else { return }
                if self.failedSSID != ssid {
                    self.failedSSID = ssid
                    self.failures = 0
                }
                if ok {
                    self.failures = 0
                    if !now.seen.hotspot, self.networks.worked(ssid) {
                        self.log("wifi: learned reliable network \(ssid) (working internet)")
                    }
                    self.skipped[ssid] = nil
                    return
                }
                self.failures += 1
                if self.networks.reliable[ssid] != nil, self.skipped[ssid].map({ $0 < Date() }) ?? true {
                    self.skip(ssid, because: "no working internet on it")
                }
                if WiFiPolicy.asksPhone(failures: self.failures, ssid: ssid, reliable: self.networks.reliableSet, seen: now.seen),
                   !self.joining {
                    self.failures = 0
                    self.log("wifi: still no working internet on \(ssid); asking the phone for its hotspot")
                    self.hotspot?.ask()
                } else {
                    self.scheduleProve(after: Self.reproveInterval)
                }
            }
        }
    }

    private func skip(_ ssid: String, because reason: String) {
        skipped[ssid] = Date().addingTimeInterval(Self.skipDuration)
        log("wifi: skipping \(ssid) for 30 minutes (\(reason))")
    }

    // MARK: - Joining the phone's hotspot

    /// Hotspot.swift asks the phone (`wants`), or stopped asking.
    func setWantsHotspot(_ wants: Bool) {
        guard wants != joining else { return }
        if wants {
            joining = true
            joinStarted = Date()
            joinPausedUntil = nil
            joinError = nil
            joinLookingLogged = false
            scheduleJoin(after: 2)
        } else {
            stopJoining(reason: nil)
        }
    }

    /// "Ask the phone for its hotspot" in the popout: the network the Mac is
    /// on is the one the owner wants off, so it isn't a target for a while.
    func askedFromMenu() {
        let now = now()
        if now.onWiFi, !now.seen.phoneHotspot, let ssid = now.ssid {
            skip(ssid, because: "asked for the phone's hotspot from the menu")
        }
        hotspot?.ask()
    }

    private func stopJoining(reason: String?) {
        joinTimer?.invalidate()
        joinTimer = nil
        guard joining else { return }
        joining = false
        if let reason { log("wifi: stopped looking for the phone's hotspot (\(reason))") }
    }

    private func scheduleJoin(after interval: TimeInterval) {
        joinTimer?.invalidate()
        joinTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.tryJoin() }
        }
    }

    private func nextJoin() {
        guard joining else { return }
        let slow = joinStarted.map { Date().timeIntervalSince($0) > Self.joinSlowAfter } ?? false
        scheduleJoin(after: slow ? Self.joinSlowInterval : Self.joinInterval)
    }

    /// Scans for the phone's hotspot names and joins the strongest.
    private func tryJoin() {
        guard joining else { return }
        guard !joinBusy, target == nil, problem == nil else { return nextJoin() }
        if let joinPausedUntil, joinPausedUntil > Date() { return nextJoin() }
        let now = now()
        if now.seen.phoneHotspot { return stopJoining(reason: "on the phone's hotspot") }
        let names = networks.hotspots
        guard !names.isEmpty else {
            if !joinMissingLogged {
                joinMissingLogged = true
                log("wifi: no name for the phone's hotspot yet (Wi-Fi tab); leaving the join to macOS")
            }
            return nextJoin()
        }
        joinMissingLogged = false
        if !joinLookingLogged {
            joinLookingLogged = true
            log("wifi: looking for the phone's hotspot (\(names.joined(separator: ", ")))")
        }
        joinBusy = true
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let result = Result { try Self.joinHotspot(names: names) }
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.joined(result) }
            }
        }
    }

    private func joined(_ result: Result<JoinResult, Error>) {
        joinBusy = false
        switch result {
        case .success(.notFound):
            break
        case .success(.joined(let ssid, let savedPassword)):
            joinError = nil
            log("wifi: joined the phone's hotspot \(ssid)\(savedPassword ? " (with the password saved in Prism)" : "")")
            // The path change does the rest; until then, no new attempt.
            joinPausedUntil = Date().addingTimeInterval(Self.joinFailedPause)
        case .failure(let error):
            let text = Self.describe(error)
            joinError = text
            log("wifi: \(text)")
            joinPausedUntil = Date().addingTimeInterval(Self.joinFailedPause)
        }
        nextJoin()
    }

    enum JoinResult: Sendable {
        case notFound
        case joined(String, savedPassword: Bool)
    }

    struct JoinError: LocalizedError {
        var ssid: String
        var underlying: String
        var triedSavedPassword: Bool
        var errorDescription: String? {
            "couldn't join the phone's hotspot \(ssid): \(underlying)"
                + (triedSavedPassword ? " (also with the password saved in Prism)" : "; if macOS doesn't know its password, save it in Prism's Wi-Fi tab")
        }
    }

    /// Directed scans for `names` (hidden ones too), then joins the strongest:
    /// first as the known network macOS keeps (nil password), then with the
    /// password saved in Prism. Blocks for seconds; not on the main thread.
    nonisolated static func joinHotspot(names: [String]) throws -> JoinResult {
        guard let interface = CWWiFiClient.shared().interface() else { throw WiFiError("no Wi-Fi interface") }
        guard interface.powerOn() else { throw WiFiError("Wi-Fi is off") }
        var found: [String: CWNetwork] = [:]
        for name in names {
            for network in try interface.scanForNetworks(withName: name, includeHidden: true) where network.ssid == name {
                if found[name].map({ $0.rssiValue < network.rssiValue }) ?? true { found[name] = network }
            }
        }
        guard let ssid = WiFiPolicy.hotspotToJoin(scan: found.mapValues(\.rssiValue), hotspots: names),
              let network = found[ssid] else { return .notFound }
        do {
            try interface.associate(to: network, password: nil)
            return .joined(ssid, savedPassword: false)
        } catch {
            guard let password = HotspotPassword.get(ssid) else {
                throw JoinError(ssid: ssid, underlying: describe(error), triedSavedPassword: false)
            }
            do {
                try interface.associate(to: network, password: password)
                return .joined(ssid, savedPassword: true)
            } catch {
                throw JoinError(ssid: ssid, underlying: describe(error), triedSavedPassword: true)
            }
        }
    }

    // MARK: - On a hotspot

    /// Scans off the main thread (it takes seconds) and leaves the hotspot
    /// when a reliable network is in range.
    private func scan() {
        guard !scanning, target == nil, problem == nil, state == .onHotspot else { return }
        if let pausedUntil, pausedUntil > Date() { return }
        scanning = true
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let result = Result { try Self.scanNetworks() }
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.scanned(result) }
            }
        }
    }

    private func scanned(_ result: Result<Networks, Error>) {
        scanning = false
        guard state == .onHotspot, target == nil else { return }
        let found: Networks
        switch result {
        case .success(let networks): found = networks
        case .failure(let error):
            log("wifi: scan failed: \(Self.describe(error))")
            return
        }
        let now = now()
        guard now.seen.hotspot else { return }
        var hotspots = networks.hotspotSet
        if let hotspotSSID { hotspots.insert(hotspotSSID) }
        guard let (ssid, rssi) = WiFiPolicy.target(
            scan: found.rssi, reliable: networks.reliableSet, hotspots: hotspots, current: now.ssid,
            skipped: skipped, now: Date(), minimumRSSI: Self.minimumRSSI),
            let network = found.networks[ssid] else { return }
        log("wifi: reliable network \(ssid) in range at \(rssi) dBm; joining it")
        target = ssid
        leftPhone = now.seen.phoneHotspot
        leftAt = nil
        state = .leaving(to: ssid)
        // The path drops while it switches: no request to the phone for that.
        hotspot?.hold()
        let box = Networks.Box(network)
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let result = Result { try Self.associate(box.network) }
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.associated(ssid, result) }
            }
        }
    }

    private func associated(_ ssid: String, _ result: Result<Void, Error>) {
        guard target == ssid, state == .leaving(to: ssid) else { return }
        switch result {
        case .success:
            log("wifi: joined \(ssid); checking the internet in \(Int(Self.checkDelay)) s")
            leftAt = Date()
            state = .checking(ssid)
            checkTimer = Timer.scheduledTimer(withTimeInterval: Self.checkDelay, repeats: false) { [weak self] _ in
                MainActor.assumeIsolated { self?.check() }
            }
        case .failure(let error):
            let text = Self.describe(error)
            if leftPhone, Self.current(hotspots: networks.hotspotSet, expensive: expensive).seen.phoneHotspot,
               hotspot?.release(ssid: ssid) == true {
                // macOS couldn't join it itself; the phone switching its
                // instant hotspot off lets macOS auto-join the best one.
                log("wifi: couldn't join \(ssid) (\(text)); releasing the phone's hotspot instead")
                state = .switching(to: ssid)
                releaseTimer = Timer.scheduledTimer(withTimeInterval: Self.releaseDuration, repeats: false) { [weak self] _ in
                    MainActor.assumeIsolated { self?.releaseEnded() }
                }
                watchTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
                    MainActor.assumeIsolated { self?.watch() }
                }
            } else {
                skip(ssid, because: "couldn't join it: \(text)")
                finish()
            }
        }
    }

    // MARK: - Leaving by a release

    /// While a release is advertised: once the hotspot is gone, stop it and
    /// check the new network.
    private func watch() {
        guard let target, leftAt == nil, case .switching = state, !now().seen.phoneHotspot else { return }
        leftAt = Date()
        watchTimer?.invalidate()
        releaseTimer?.invalidate()
        hotspot?.endRelease(reason: "the Mac left the hotspot")
        log("wifi: left the phone's hotspot; checking the internet in \(Int(Self.checkDelay)) s")
        leftPhone = false
        state = .checking(target)
        checkTimer = Timer.scheduledTimer(withTimeInterval: Self.checkDelay, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.check() }
        }
    }

    /// 30 s of release and still on the hotspot: the phone didn't act on it.
    private func releaseEnded() {
        guard target != nil, leftAt == nil else { return }
        watchTimer?.invalidate()
        log("wifi: still on the phone's hotspot after the release (normal mode, or the phone didn't see it); no release for 10 minutes")
        pausedUntil = Date().addingTimeInterval(Self.ignoredPause)
        finish()
    }

    private func check() {
        guard let target else { return }
        Self.probe { [weak self] ok in
            MainActor.assumeIsolated {
                guard let self else { return }
                let now = self.now()
                if ok, now.onWiFi, !now.seen.hotspot {
                    let ssid = now.ssid ?? target
                    self.log("wifi: on \(ssid) with working internet")
                    self.networks.worked(ssid)
                    let release = self.leftPhone
                    self.finish()
                    // The phone switches its instant hotspot off now, not
                    // after its idle timeout.
                    if release { self.hotspot?.releaseAfterLeaving(ssid: ssid) }
                } else {
                    self.skip(target, because: "no working internet \(Int(Self.checkDelay)) s after leaving the hotspot")
                    if let ssid = now.ssid, ssid != target, ssid != self.hotspotSSID, !now.seen.hotspot {
                        self.skip(ssid, because: "no working internet \(Int(Self.checkDelay)) s after leaving the hotspot")
                    }
                    self.finish()
                    // The path may be up on a broken network, where the
                    // offline request never comes: ask explicitly, which
                    // joins the hotspot again.
                    self.log("wifi: asking the phone for its hotspot again")
                    self.hotspot?.ask()
                }
            }
        }
    }

    private func finish() {
        target = nil
        leftAt = nil
        leftPhone = false
        watchTimer?.invalidate()
        releaseTimer?.invalidate()
        checkTimer?.invalidate()
        hotspot?.endHold()
        state = .away
        evaluate()
    }

    // MARK: - The Wi-Fi tab

    /// Scans for the tab's "Nearby" list (doesn't leave the network).
    func scanNearby() {
        guard !scanningNearby else { return }
        scanningNearby = true
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let result = Result { try Self.scanNetworks() }
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard let self else { return }
                    self.scanningNearby = false
                    switch result {
                    case .success(let found): self.nearby = found.rssi
                    case .failure(let error): self.log("wifi: scan failed: \(Self.describe(error))")
                    }
                }
            }
        }
    }

    /// The networks macOS knows (its preferred networks), for the tab.
    func knownNetworks() -> [String] {
        isPreview ? ["Hotel", "Train"] : Self.knownNetworks()
    }

    nonisolated static func knownNetworks() -> [String] {
        guard let profiles = CWWiFiClient.shared().interface()?.configuration()?.networkProfiles.array as? [CWNetworkProfile] else { return [] }
        var seen = Set<String>()
        return profiles.compactMap(\.ssid).filter { seen.insert($0).inserted }
    }

    /// Sample state for `prism preview`.
    func preview(current: Current, nearby: [String: Int]) {
        isPreview = true
        self.current = current
        self.nearby = nearby
    }

    // MARK: - Location

    fileprivate func updateProblem() {
        guard let location else { return }
        let text: String? = switch location.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse: nil
        case .notDetermined: "Waiting for Location permission (Wi-Fi names)."
        case .restricted: "Location is restricted on this Mac; Prism can't see Wi-Fi names."
        default: "Allow Location for Prism to switch to a known Wi-Fi (Privacy & Security → Location Services)."
        }
        if text != problem {
            problem = text
            if let text { log("wifi: \(text)") }
        }
    }

    fileprivate func authorizationChanged() {
        if location?.authorizationStatus == .notDetermined {
            log("wifi: asking for Location permission (network names)")
            location?.requestWhenInUseAuthorization()
        }
        updateProblem()
        evaluate()
    }

    // MARK: - Reading the system

    struct Now {
        var interface: String?
        /// The interface has an IPv4 configuration (it's on a network).
        var onWiFi = false
        var ssid: String?
        var option43: Data?
        /// The lease carries ANDROID_METERED.
        var androidMetered = false
        var expensive = false
        var seen = WiFiPolicy.Seen(phoneHotspot: false, hotspot: false)
    }

    /// The Wi-Fi interface (CoreWLAN, not a fixed en0), its network, its DHCP
    /// lease's option 43, and whether that makes it a hotspot.
    nonisolated static func current(hotspots: Set<String>, expensive: Bool) -> Now {
        guard let interface = CWWiFiClient.shared().interface(), let name = interface.interfaceName else { return Now() }
        var now = Now(interface: name, ssid: interface.ssid())
        guard let store = SCDynamicStoreCreate(nil, "Prism" as CFString, nil, nil),
              let service = serviceID(store: store, interface: name) else { return now }
        now.onWiFi = true
        now.expensive = expensive
        // What SCDynamicStoreCopyDHCPInfo returns (not imported into Swift):
        // the lease's options as Option_<code> data, only those macOS asked
        // for (43 isn't); the whole lease is in `ipconfig getpacket`.
        let info = SCDynamicStoreCopyValue(store, "State:/Network/Service/\(service)/DHCP" as CFString) as? [String: Any]
        if let option = info?["Option_43"] as? Data ?? vendorSpecific(interface: name) {
            now.option43 = option
            now.androidMetered = option.range(of: meteredMarker) != nil
        }
        now.seen = WiFiPolicy.classify(ssid: now.ssid, androidMetered: now.androidMetered, expensive: expensive, hotspots: hotspots)
        return now
    }

    /// Option 43 of the lease on `interface`, from `ipconfig getpacket`
    /// (milliseconds). Reads to the end and polls for the exit: waiting with
    /// waitUntilExit would run the main run loop inside the caller.
    nonisolated static func vendorSpecific(interface: String) -> Data? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/sbin/ipconfig")
        process.arguments = ["getpacket", interface]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        do { try process.run() } catch { return nil }
        let output = pipe.fileHandleForReading.readDataToEndOfFile()
        while process.isRunning { usleep(1000) }
        guard process.terminationStatus == 0 else { return nil }
        return parseVendorSpecific(String(decoding: output, as: UTF8.self))
    }

    /// The bytes of `vendor_specific (opaque):` in `ipconfig getpacket`'s
    /// output: hex dump lines "0000  41 4e 44 ... 5f  4d 45 ...  ANDROID_...",
    /// offset, two groups of eight bytes, then the text (cut off by position,
    /// so text that looks like hex isn't read as bytes).
    nonisolated static func parseVendorSpecific(_ text: String) -> Data? {
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false)
        guard let start = lines.firstIndex(where: { $0.hasPrefix("vendor_specific") }) else { return nil }
        var bytes = Data()
        for line in lines[(start + 1)...] {
            let chars = Array(line)
            guard chars.count > 6, chars[0..<4].allSatisfy(\.isHexDigit), chars[4] == " " else { break }
            let hex = String(chars[6..<min(chars.count, 55)])
            for token in hex.split(separator: " ") {
                guard token.count == 2, let byte = UInt8(token, radix: 16) else { return bytes.isEmpty ? nil : bytes }
                bytes.append(byte)
            }
        }
        return bytes.isEmpty ? nil : bytes
    }

    /// The network service with an IPv4 configuration on `interface`.
    private nonisolated static func serviceID(store: SCDynamicStore, interface: String) -> String? {
        let keys = SCDynamicStoreCopyKeyList(store, "State:/Network/Service/[^/]+/IPv4" as CFString) as? [String] ?? []
        for key in keys {
            guard let value = SCDynamicStoreCopyValue(store, key as CFString) as? [String: Any],
                  value["InterfaceName"] as? String == interface else { continue }
            return key.split(separator: "/")[3].description
        }
        return nil
    }

    /// A scan: each network name's strongest RSSI and that network, to join.
    struct Networks: @unchecked Sendable {
        var rssi: [String: Int] = [:]
        var networks: [String: CWNetwork] = [:]

        /// A CWNetwork from a scan, handed to another queue for the join.
        struct Box: @unchecked Sendable {
            let network: CWNetwork
            init(_ network: CWNetwork) { self.network = network }
        }
    }

    /// A scan that doesn't leave the current network. Blocks for seconds; not
    /// on the main thread.
    nonisolated static func scanNetworks() throws -> Networks {
        guard let interface = CWWiFiClient.shared().interface() else { return Networks() }
        var found = Networks()
        for network in try interface.scanForNetworks(withSSID: nil) {
            guard let ssid = network.ssid else { continue }
            if network.rssiValue > found.rssi[ssid] ?? .min {
                found.rssi[ssid] = network.rssiValue
                found.networks[ssid] = network
            }
        }
        return found
    }

    /// Joins `network` as the known network macOS keeps. Blocks; not on the
    /// main thread.
    nonisolated static func associate(_ network: CWNetwork) throws {
        guard let interface = CWWiFiClient.shared().interface() else { throw WiFiError("no Wi-Fi interface") }
        try interface.associate(to: network, password: nil)
    }

    struct WiFiError: LocalizedError {
        var errorDescription: String?
        init(_ text: String) { errorDescription = text }
    }

    nonisolated static func describe(_ error: Error) -> String {
        if let error = error as? LocalizedError, let text = error.errorDescription { return text }
        let error = error as NSError
        return "\(error.localizedDescription) (\(error.domain) \(error.code))"
    }

    /// The captive probe over Wi-Fi: HTTP 200 with "Success" from
    /// captive.apple.com within 5 s. A plain connection (no URL cache, and
    /// bound to Wi-Fi, which URLSession can't be). Calls back on the main
    /// queue.
    nonisolated static func probe(timeout: TimeInterval = 5, _ completion: @escaping @Sendable (Bool) -> Void) {
        let parameters = NWParameters.tcp
        parameters.requiredInterfaceType = .wifi
        let connection = NWConnection(host: "captive.apple.com", port: 80, using: parameters)
        let probe = Probe(connection: connection, completion: completion)
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                let request = "GET /hotspot-detect.html HTTP/1.1\r\nHost: captive.apple.com\r\nUser-Agent: Prism\r\n"
                    + "Cache-Control: no-cache\r\nPragma: no-cache\r\nConnection: close\r\n\r\n"
                connection.send(content: Data(request.utf8), completion: .contentProcessed { error in
                    if error != nil { probe.finish(false) } else { probe.receive() }
                })
            case .failed, .waiting:
                probe.finish(false)
            default:
                break
            }
        }
        connection.start(queue: probe.queue)
        probe.queue.asyncAfter(deadline: .now() + timeout) { probe.finish(false) }
    }

    private final class Probe: @unchecked Sendable {
        let queue = DispatchQueue(label: "at.quickme.prism.probe")
        let connection: NWConnection
        let completion: @Sendable (Bool) -> Void
        var received = Data()
        var done = false

        init(connection: NWConnection, completion: @escaping @Sendable (Bool) -> Void) {
            self.connection = connection
            self.completion = completion
        }

        func receive() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [self] data, _, complete, error in
                if let data { received.append(data) }
                if complete || error != nil || received.count > 65536 {
                    finish(Self.success(received))
                } else {
                    receive()
                }
            }
        }

        func finish(_ ok: Bool) {
            guard !done else { return }
            done = true
            connection.cancel()
            let completion = completion
            DispatchQueue.main.async { completion(ok) }
        }

        static func success(_ response: Data) -> Bool {
            let text = String(decoding: response, as: UTF8.self)
            guard let status = text.split(separator: "\r\n", maxSplits: 1).first,
                  status.hasPrefix("HTTP/1."), status.split(separator: " ").dropFirst().first == "200",
                  let body = text.range(of: "\r\n\r\n") else { return false }
            return text[body.upperBound...].contains("Success")
        }
    }

    // MARK: - prism wifi-status

    nonisolated static func printStatus() {
        let (reliable, hotspots, ignored) = MainActor.assumeIsolated {
            let networks = WiFiNetworks()
            return (networks.list, networks.hotspots, networks.ignored.sorted())
        }
        let now = current(hotspots: Set(hotspots), expensive: wifiExpensive())
        print("interface   \(now.interface ?? "none")")
        print("on network  \(now.onWiFi ? "yes" : "no")")
        print("ssid        \(now.ssid ?? "unknown (not associated, or no Location permission)")")
        print("option 43   \(now.option43.map { $0.map { String(format: "%02x", $0) }.joined(separator: " ") } ?? "none")")
        print("expensive   \(now.expensive ? "yes" : "no")")
        print("hotspot     \(now.seen.phoneHotspot ? "the phone's" : now.seen.hotspot ? "yes (metered Wi-Fi)" : "no")"
              + (now.androidMetered ? " (ANDROID_METERED)" : ""))
        let done = DispatchSemaphore(value: 0)
        nonisolated(unsafe) var ok = false
        DispatchQueue.global().async {
            probe { result in ok = result; done.signal() }
        }
        // probe calls back on the main queue: run it until then.
        while done.wait(timeout: .now()) == .timedOut {
            RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        }
        print("probe       \(ok ? "Success" : "no working internet")")
        print("phone's hotspot \(hotspots.isEmpty ? "none" : hotspots.joined(separator: ", "))")
        print("reliable    \(reliable.isEmpty ? "none" : "")")
        for network in reliable {
            print("  \(network.ssid)  \(network.worked.map { "\($0)" } ?? "not seen working yet")\(network.addedByUser ? "  (added)" : "")")
        }
        if !ignored.isEmpty { print("ignored     \(ignored.joined(separator: ", "))") }
    }

    /// Whether macOS marks the Wi-Fi path expensive, for wifi-status.
    nonisolated static func wifiExpensive() -> Bool {
        let monitor = NWPathMonitor(requiredInterfaceType: .wifi)
        let done = DispatchSemaphore(value: 0)
        nonisolated(unsafe) var expensive = false
        monitor.pathUpdateHandler = { path in
            expensive = path.status == .satisfied && path.isExpensive
            done.signal()
        }
        monitor.start(queue: DispatchQueue(label: "at.quickme.prism.wifi-status"))
        _ = done.wait(timeout: .now() + 2)
        monitor.cancel()
        return expensive
    }

    private func log(_ text: String) {
        Hotspot.log(text)
    }
}

extension WiFiSwitch: CLLocationManagerDelegate {
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        MainActor.assumeIsolated { authorizationChanged() }
    }
}
