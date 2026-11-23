// Leaving the phone's hotspot for a proven Wi-Fi network (docs/hotspot.md,
// "Leaving"). A Mac has one Wi-Fi radio and can't test a network without
// leaving the one it's on, so it only leaves for a network on which it has
// seen working internet before ("proven"):
//
// - On a Wi-Fi network that is not the hotspot, a captive probe
//   (http://captive.apple.com/hotspot-detect.html answers "Success") on path
//   changes and every 10 minutes records the network's name with the time,
//   in UserDefaults.
// - The hotspot is the network whose DHCP lease carries Android's vendor
//   option 43, "ANDROID_METERED" (configd's DHCP info for the Wi-Fi
//   interface's service, Option_43: the bytes `ipconfig getpacket` prints as
//   vendor_encapsulated_options).
// - On the hotspot it scans every 60 s (CoreWLAN, without leaving it). A
//   proven network at -70 dBm or better gets a release advertised for 30 s
//   (Hotspot.swift); the phone switches its instant hotspot off and macOS
//   auto-joins the best known network. 20 s after the hotspot is gone, a
//   probe without "Success" skips that network for 30 minutes and asks the
//   phone for its hotspot again.
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
        /// Not on the phone's hotspot.
        case away
        /// On the hotspot, scanning for a proven network.
        case onHotspot
        /// A release for `ssid` is advertised.
        case switching(to: String)
        /// The hotspot is gone; the probe on the new network is due.
        case checking(String)
    }

    @Published private(set) var state = State.away
    /// Why it can't see network names (Location permission), for the popout.
    @Published private(set) var problem: String?

    weak var hotspot: Hotspot?

    static let scanInterval: TimeInterval = 60
    static let proveInterval: TimeInterval = 600
    static let minimumRSSI = -70
    static let releaseDuration: TimeInterval = 30
    static let checkDelay: TimeInterval = 20
    static let skipDuration: TimeInterval = 1800
    /// After a release the phone didn't act on (normal mode, out of
    /// range): no other release for this long.
    static let ignoredPause: TimeInterval = 600
    /// Path changes come in bursts, and configd's lease lags the link.
    static let settleDelay: TimeInterval = 2
    nonisolated static let provenKey = "hotspotProvenNetworks"
    nonisolated static let meteredMarker = Data("ANDROID_METERED".utf8)

    private let monitor = NWPathMonitor()
    private var location: CLLocationManager?
    private var started = false
    private var settleTimer: Timer?
    private var scanTimer: Timer?
    private var proveTimer: Timer?
    private var watchTimer: Timer?
    private var releaseTimer: Timer?
    private var checkTimer: Timer?
    private var scanning = false
    private var proving = false
    /// The hotspot's own network name, never a target.
    private var hotspotSSID: String?
    /// The network a release is for, from release until the check.
    private var target: String?
    private var leftAt: Date?
    private var skipped: [String: Date] = [:]
    private var pausedUntil: Date?

    func start() {
        guard !started else { return }
        started = true
        let manager = CLLocationManager()
        manager.delegate = self
        location = manager
        if manager.authorizationStatus == .notDetermined {
            log("wifi: asking for Location permission (network names)")
            manager.requestWhenInUseAuthorization()
        }
        updateProblem()
        monitor.pathUpdateHandler = { [weak self] _ in
            MainActor.assumeIsolated { self?.pathChanged() }
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

    private func evaluate() {
        if target != nil {
            watch()
            return
        }
        let now = Self.current()
        if now.onHotspot {
            if let ssid = now.ssid { hotspotSSID = ssid }
            proveTimer?.invalidate()
            proveTimer = nil
            if state != .onHotspot {
                log("wifi: on the phone's hotspot\(now.ssid.map { " (\($0))" } ?? "")")
                state = .onHotspot
            }
            if scanTimer == nil {
                scanTimer = Timer.scheduledTimer(withTimeInterval: Self.scanInterval, repeats: true) { [weak self] _ in
                    MainActor.assumeIsolated { self?.scan() }
                }
                scan()
            }
        } else {
            scanTimer?.invalidate()
            scanTimer = nil
            if state == .onHotspot {
                log("wifi: off the phone's hotspot")
            }
            state = .away
            if now.onWiFi {
                prove()
                if proveTimer == nil {
                    proveTimer = Timer.scheduledTimer(withTimeInterval: Self.proveInterval, repeats: true) { [weak self] _ in
                        MainActor.assumeIsolated { self?.prove() }
                    }
                }
            } else {
                proveTimer?.invalidate()
                proveTimer = nil
            }
        }
    }

    // MARK: - Proven networks

    /// Probes the current Wi-Fi network (not the hotspot): "Success" records
    /// it; a failure skips a proven one for 30 minutes, so the Mac doesn't
    /// come back to it from the hotspot while it is broken.
    private func prove() {
        guard !proving else { return }
        let before = Self.current()
        guard before.onWiFi, !before.onHotspot else { return }
        proving = true
        Self.probe { [weak self] ok in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.proving = false
                let now = Self.current()
                // A network that changed during the probe gets the next one.
                guard now.onWiFi, !now.onHotspot, let ssid = now.ssid, ssid == before.ssid else { return }
                if ok {
                    self.record(ssid)
                } else if Self.proven()[ssid] != nil, self.skipped[ssid].map({ $0 < Date() }) ?? true {
                    self.skip(ssid, because: "no working internet on it")
                }
            }
        }
    }

    private func record(_ ssid: String) {
        var proven = Self.proven()
        let known = proven[ssid] != nil
        proven[ssid] = Date().timeIntervalSince1970
        UserDefaults.standard.set(proven, forKey: Self.provenKey)
        skipped[ssid] = nil
        if !known {
            log("wifi: proven network \(ssid) (working internet)")
        }
    }

    private func skip(_ ssid: String, because reason: String) {
        skipped[ssid] = Date().addingTimeInterval(Self.skipDuration)
        log("wifi: skipping \(ssid) for 30 minutes (\(reason))")
    }

    /// Network name → when it last had working internet (unix seconds).
    nonisolated static func proven() -> [String: Double] {
        UserDefaults.standard.dictionary(forKey: provenKey)?.compactMapValues { $0 as? Double } ?? [:]
    }

    // MARK: - On the hotspot

    /// Scans off the main thread (it takes seconds) and releases the hotspot
    /// when a proven network is in range.
    private func scan() {
        guard !scanning, target == nil, problem == nil else { return }
        if let pausedUntil, pausedUntil > Date() { return }
        scanning = true
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let result = Result { try Self.scanNetworks() }
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.scanned(result) }
            }
        }
    }

    private func scanned(_ result: Result<[String: Int], Error>) {
        scanning = false
        guard state == .onHotspot, target == nil else { return }
        let networks: [String: Int]
        switch result {
        case .success(let found): networks = found
        case .failure(let error):
            log("wifi: scan failed: \(error.localizedDescription)")
            return
        }
        let proven = Self.proven()
        let now = Date()
        let best = networks
            .filter { ssid, rssi in
                rssi >= Self.minimumRSSI && proven[ssid] != nil && ssid != hotspotSSID
                    && (skipped[ssid].map { $0 <= now } ?? true)
            }
            .max { $0.value < $1.value }
        guard let (ssid, rssi) = best else { return }
        guard Self.current().onHotspot, hotspot?.release(ssid: ssid) == true else { return }
        log("wifi: proven network \(ssid) in range at \(rssi) dBm; releasing the phone's hotspot")
        target = ssid
        leftAt = nil
        state = .switching(to: ssid)
        releaseTimer = Timer.scheduledTimer(withTimeInterval: Self.releaseDuration, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.releaseEnded() }
        }
        watchTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.watch() }
        }
    }

    // MARK: - Leaving

    /// While a release is advertised: once the hotspot is gone, stop it and
    /// check the new network 20 s later.
    private func watch() {
        guard let target, leftAt == nil, !Self.current().onHotspot else { return }
        leftAt = Date()
        watchTimer?.invalidate()
        releaseTimer?.invalidate()
        hotspot?.endRelease(reason: "the Mac left the hotspot")
        log("wifi: left the phone's hotspot; checking the internet in 20 s")
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
                let now = Self.current()
                if ok, now.onWiFi, !now.onHotspot {
                    let ssid = now.ssid ?? target
                    self.log("wifi: on \(ssid) with working internet")
                    self.record(ssid)
                    self.finish()
                } else {
                    self.skip(target, because: "no working internet 20 s after leaving the hotspot")
                    if let ssid = now.ssid, ssid != target, ssid != self.hotspotSSID, !now.onHotspot {
                        self.skip(ssid, because: "no working internet 20 s after leaving the hotspot")
                    }
                    self.finish()
                    // The path may be up on a broken network, where the
                    // offline request never comes: ask explicitly.
                    self.log("wifi: asking the phone for its hotspot again")
                    self.hotspot?.ask()
                }
            }
        }
    }

    private func finish() {
        target = nil
        leftAt = nil
        watchTimer?.invalidate()
        releaseTimer?.invalidate()
        checkTimer?.invalidate()
        hotspot?.endHold()
        state = .away
        evaluate()
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

    // MARK: - Reading the system

    struct Now {
        var interface: String?
        /// The interface has an IPv4 configuration (it's on a network).
        var onWiFi = false
        var ssid: String?
        var onHotspot = false
        var option43: Data?
    }

    /// The Wi-Fi interface (CoreWLAN, not a fixed en0), its network and its
    /// DHCP lease's option 43.
    nonisolated static func current() -> Now {
        guard let interface = CWWiFiClient.shared().interface(), let name = interface.interfaceName else { return Now() }
        var now = Now(interface: name, ssid: interface.ssid())
        guard let store = SCDynamicStoreCreate(nil, "Prism" as CFString, nil, nil),
              let service = serviceID(store: store, interface: name) else { return now }
        now.onWiFi = true
        // What SCDynamicStoreCopyDHCPInfo returns (not imported into Swift):
        // the lease's options as Option_<code> data.
        if let info = SCDynamicStoreCopyValue(store, "State:/Network/Service/\(service)/DHCP" as CFString) as? [String: Any],
           let option = info["Option_43"] as? Data {
            now.option43 = option
            now.onHotspot = option.range(of: meteredMarker) != nil
        }
        return now
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

    /// Network name → strongest RSSI, from a scan that doesn't leave the
    /// current network. Blocks for seconds; not on the main thread.
    nonisolated static func scanNetworks() throws -> [String: Int] {
        guard let interface = CWWiFiClient.shared().interface() else { return [:] }
        var found: [String: Int] = [:]
        for network in try interface.scanForNetworks(withSSID: nil) {
            guard let ssid = network.ssid else { continue }
            found[ssid] = max(found[ssid] ?? .min, network.rssiValue)
        }
        return found
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
        let now = current()
        print("interface   \(now.interface ?? "none")")
        print("on network  \(now.onWiFi ? "yes" : "no")")
        print("ssid        \(now.ssid ?? "unknown (not associated, or no Location permission)")")
        print("option 43   \(now.option43.map { $0.map { String(format: "%02x", $0) }.joined(separator: " ") } ?? "none")")
        print("hotspot     \(now.onHotspot ? "yes (ANDROID_METERED)" : "no")")
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
        let proven = proven()
        print("proven      \(proven.isEmpty ? "none" : "")")
        for (ssid, time) in proven.sorted(by: { $0.value > $1.value }) {
            print("  \(ssid)  \(Date(timeIntervalSince1970: time))")
        }
    }

    private func log(_ text: String) {
        Hotspot.log(text)
    }
}

extension WiFiSwitch: CLLocationManagerDelegate {
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        MainActor.assumeIsolated {
            updateProblem()
            evaluate()
        }
    }
}
