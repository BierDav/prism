// The phone's calls on this Mac (docs/calls.md, "The connection (v2)"): the
// phone's call bridge (the Android app's shell/CallBridge.kt) links to Prism
// while a call rings or goes on, over Bluetooth LE when it can and TCP port
// 47470 otherwise. For Bluetooth, Prism publishes an L2CAP channel and
// advertises it (a service UUID with the PSM and a tag from the call key and
// the 30 s window), and the bridge opens it; for TCP Prism listens. Both
// links run the same connection: Prism says hello, checks the phone's proof
// (HMAC with K = HMAC-SHA256(hotspot key, "prism-call/v2")), proves itself,
// and from there every frame is AES-256-GCM, a key for each direction. It
// follows the call's state and sends the owner's answer, decline, hang-up
// and moves. The newest authenticated link wins. While the call's audio is
// on the Mac, CallAudio plays the caller through the speakers and sends the
// microphone, with macOS's voice processing (echo cancellation), as 16 kHz
// 16-bit mono in 20 ms frames: IMA ADPCM over Bluetooth, PCM over TCP.
//
// CallPanel.swift shows it all.

import AppKit
import AVFoundation
import CoreBluetooth
import CryptoKit
import Foundation
import Network

@MainActor
final class PhoneCalls: ObservableObject {
    nonisolated static let port: UInt16 = 47470
    nonisolated static let label = "prism-call/v2"
    /// The label of the bridge's Bluetooth service UUIDs (keyed with K).
    nonisolated static let bluetoothLabel = "prism-call-ble/v1"

    enum State: String { case ringing, active, ended }

    struct Call: Equatable {
        var state: State
        var number: String?
        var name: String?
        var audioOnMac: Bool
        var since: Date?
        var error: String?

        /// Who calls, as the panel's title.
        var title: String { name ?? number ?? (state == .ringing ? "Unknown caller" : "Phone call") }
    }

    /// The phone's call, while there is one (and a moment after it ended).
    @Published private(set) var call: Call?
    /// Whether the phone's bridge is connected now.
    @Published private(set) var connected = false
    /// The Mac's microphone sends silence.
    @Published var muted = false {
        didSet { audio?.muted = muted }
    }
    /// What keeps calls from working on this Mac, if anything.
    @Published private(set) var problem: String?
    /// About how much later than on the phone the caller is heard here, or
    /// the Mac's microphone there (the larger), in ms, while the call's
    /// audio is on this Mac.
    @Published private(set) var delay: Int?
    /// From this delay on (ms) the panel shows it: past ITU-T G.114's 150 ms
    /// a conversation starts to feel it, and the network's delay comes on top.
    nonisolated static let delayShownFrom = 150

    /// Called when `call` comes or goes, for the panel.
    var onChange: (() -> Void)?

    private let keyFile: String?
    private let queue = DispatchQueue(label: "prism.calls")
    private var listener: NWListener?
    private var bluetooth: CallBluetooth?
    /// Whether the hotspot's request or release advertises (Hotspot.swift):
    /// the call's advertisement steps aside meanwhile (CallBluetooth).
    var hotspotAdvertising: () -> Bool = { false }
    private var link: CallConnection?
    /// The link the microphone's frames go to, read on the audio thread: the
    /// call's audio moves with the link.
    private nonisolated let audioLink = AudioLink()
    /// Connections still in their handshake (nothing else holds them).
    private var candidates: [CallConnection] = []
    private var audio: CallAudio?
    private var clearTask: Task<Void, Never>?
    private var ringTimer: Timer?
    /// Pings the phone while linked: the round trip, and the phone's audio counters.
    private var pingTimer: Timer?
    private var pongs = 0
    /// Pongs in a row that took longer than `slowRoundTrip`.
    private var slowPongs = 0
    /// A link this far behind holds seconds of audio in its buffers (an LE
    /// link whose throughput collapsed): a new one starts empty.
    private static let slowRoundTrip = 1500
    private var recentDelays: [Int] = []

    init(keyFile: String?) {
        self.keyFile = keyFile
    }

    func start() {
        guard keyFile != nil else {
            log("calls: not set up (no calls with a hotspotKeyFile in the configuration)")
            return
        }
        guard key() != nil else {
            problem = "No key in \(keyFile ?? "the configuration")."
            log("calls: \(problem!)")
            return
        }
        let bluetooth = CallBluetooth(
            key: { [weak self] in self?.key().map { Self.callKey($0) } },
            yields: { [weak self] in self?.hotspotAdvertising() ?? false }
        )
        bluetooth.onChannel = { [weak self] transport in self?.accept(transport) }
        bluetooth.start()
        self.bluetooth = bluetooth
        do {
            let tcp = NWProtocolTCP.Options()
            tcp.noDelay = true
            tcp.enableKeepalive = true
            tcp.keepaliveIdle = 10
            tcp.keepaliveInterval = 5
            tcp.keepaliveCount = 3
            let listener = try NWListener(using: NWParameters(tls: nil, tcp: tcp), on: NWEndpoint.Port(rawValue: Self.port)!)
            listener.newConnectionHandler = { [weak self] connection in
                Task { @MainActor in self?.accept(TCPTransport(connection)) }
            }
            listener.stateUpdateHandler = { [weak self] state in
                Task { @MainActor in self?.listenerChanged(state) }
            }
            listener.start(queue: queue)
            self.listener = listener
        } catch {
            problem = "Can't listen on port \(Self.port): \(error.localizedDescription)"
            log("calls: \(problem!)")
        }
    }

    private func listenerChanged(_ state: NWListener.State) {
        switch state {
        case .ready:
            problem = nil
            log("calls: listening on port \(Self.port)")
        case .failed(let error):
            problem = "Not listening for the phone: \(error.localizedDescription)"
            log("calls: \(problem!)")
        default:
            break
        }
    }

    // MARK: - Commands

    /// The hotspot started or stopped advertising.
    func hotspotChanged() {
        bluetooth?.refresh()
    }

    func answer() { send(["type": "answer"]) }
    func decline() { send(["type": "decline"]) }
    func hangUp() { send(["type": "hangup"]) }
    func moveToMac() { send(["type": "audio", "to": "mac"]) }
    func moveToPhone() { send(["type": "audio", "to": "phone"]) }

    private func send(_ message: [String: Any]) {
        guard let link, let data = try? JSONSerialization.data(withJSONObject: message) else {
            call?.error = "The phone isn't connected."
            return
        }
        call?.error = nil
        link.send(type: CallConnection.message, payload: data)
    }

    // MARK: - The connection

    private func accept(_ transport: CallTransport) {
        guard let key = key().map({ Self.callKey($0) }) else {
            transport.cancel("no key")
            return
        }
        let candidate = CallConnection(transport: transport, key: key, queue: queue)
        candidate.onReady = { [weak self, weak candidate] in
            guard let self, let candidate else { return }
            Task { @MainActor in self.ready(candidate) }
        }
        candidate.onMessage = { [weak self, weak candidate] data in
            guard let candidate else { return }
            Task { @MainActor in
                guard let self, self.link === candidate else { return }
                self.message(data)
            }
        }
        let audioLink = self.audioLink
        candidate.onAudio = { [weak candidate] frame in
            guard let candidate, audioLink.link === candidate else { return }
            audioLink.audio?.play(frame)
        }
        candidate.onClose = { [weak self, weak candidate] reason in
            Task { @MainActor in
                guard let self, let candidate else { return }
                self.candidates.removeAll { $0 === candidate }
                if self.link === candidate { self.closed(reason) }
            }
        }
        candidates.append(candidate)
        candidate.start()
    }

    /// A phone that proved itself: it replaces any earlier connection, and
    /// the call's audio moves to it.
    private func ready(_ candidate: CallConnection) {
        candidates.removeAll { $0 === candidate }
        let old = link
        link = candidate
        audioLink.link = candidate
        if let old, old !== candidate { old.cancel("replaced by a newer link") }
        connected = true
        slowPongs = 0
        clearTask?.cancel()
        startPings()
        log("calls: the phone connected over \(candidate.transport.bluetooth ? "Bluetooth" : "TCP") (\(candidate.peer))\(old != nil && old !== candidate ? ", replacing the earlier link" : "")")
    }

    private func closed(_ reason: String) {
        link = nil
        audioLink.link = nil
        connected = false
        pingTimer?.invalidate()
        pingTimer = nil
        log("calls: the phone's connection closed (\(reason))")
        stopAudio()
        guard var call, call.state != .ended else { return }
        // The phone reconnects within seconds while the call goes on.
        call.audioOnMac = false
        self.call = call
        clearTask?.cancel()
        clearTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(15))
            guard !Task.isCancelled, let self, !self.connected else { return }
            self.setCall(nil)
        }
    }

    // MARK: - The delay

    private static func milliseconds() -> Int { Int(DispatchTime.now().uptimeNanoseconds / 1_000_000) }

    private func startPings() {
        pingTimer?.invalidate()
        pingTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let link = self.link,
                      let data = try? JSONSerialization.data(withJSONObject: ["type": "ping", "t": Self.milliseconds()]) else { return }
                link.send(type: CallConnection.message, payload: data)
            }
        }
    }

    /// The phone's answer to a ping: the round trip gives the link's share
    /// of the delay; the buffers on both sides give the rest.
    private func pong(_ json: [String: Any]) {
        guard let t = (json["t"] as? NSNumber)?.intValue else { return }
        let rtt = Self.milliseconds() - t
        slowPongs = rtt > Self.slowRoundTrip ? slowPongs + 1 : 0
        if slowPongs >= 2, let link {
            slowPongs = 0
            log("calls: the link fell \(rtt) ms behind; connecting again")
            link.cancel("it fell \(rtt) ms behind")
            return
        }
        guard let audio, call?.audioOnMac == true else {
            if delay != nil { delay = nil }
            recentDelays = []
            return
        }
        let mac = audio.stats()
        let phoneQueued = (json["queued"] as? NSNumber)?.intValue ?? 0
        // Per direction: half the round trip, one frame of framing, the
        // other side's queue and this Mac's audio device.
        let frame = 20
        let hear = rtt / 2 + 2 * frame + mac.playQueued * frame + mac.outputLatency
        let speak = rtt / 2 + 2 * frame + phoneQueued * frame + mac.inputLatency
        recentDelays = (recentDelays + [max(hear, speak)]).suffix(5)
        let shown = recentDelays.sorted()[recentDelays.count / 2]
        if shown != delay { delay = shown }
        pongs += 1
        if pongs % 5 == 1 {
            func dB(_ peak: Any?) -> String {
                guard let p = (peak as? NSNumber)?.doubleValue, p > 0 else { return "silent" }
                return String(format: "peak %.0f dBFS", 20 * log10(p / 32768))
            }
            log("calls: audio: delay about \(shown) ms (hear \(hear), speak \(speak), round trip \(rtt) ms);"
                + " from the phone \(mac.received) frames (\(dB(mac.receivedPeak))), dropped \(mac.dropped), shed \(mac.trimmed), gaps \(mac.concealed), buffer \(mac.playQueued)/\(mac.playTarget), paced out \(link?.skipped ?? 0) of ours, \(json["skipped"] ?? "?") of the phone's;"
                + " to the phone \(mac.sent) frames (\(dB(mac.sentPeak)));"
                + " the phone recorded \(json["recorded"] ?? "?") (\(dB(json["recordedPeak"])))"
                + " and played \(json["played"] ?? "?") (\(dB(json["playedPeak"]))), queued \(phoneQueued)/\(json["target"] ?? "?"), gaps \(json["gaps"] ?? "?"), shed \(json["trimmed"] ?? "?"), mode \(json["mode"] ?? "?")")
        }
    }

    private func message(_ data: Data) {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            log("calls: a message that isn't JSON")
            return
        }
        if json["type"] as? String == "pong" {
            pong(json)
            return
        }
        guard json["type"] as? String == "call",
              let state = (json["state"] as? String).flatMap(State.init(rawValue:)) else {
            log("calls: a message that isn't a call's state")
            return
        }
        let since = (json["since"] as? NSNumber).map { Date(timeIntervalSince1970: $0.doubleValue / 1000) }
        let next = Call(
            state: state,
            number: json["number"] as? String,
            name: json["name"] as? String,
            audioOnMac: json["audio"] as? String == "mac",
            since: since,
            error: json["error"] as? String)
        if let error = next.error { log("calls: the phone says: \(error)") }
        if next.audioOnMac { startAudio() } else { stopAudio() }
        setCall(next)
        if state == .ended {
            // The phone waits for this to exit: closing on its side could
            // drop "ended" on the way.
            link?.cancel("the call ended")
            clearTask?.cancel()
            clearTask = Task { [weak self] in
                try? await Task.sleep(for: .seconds(3))
                guard !Task.isCancelled, let self, self.call?.state == .ended else { return }
                self.setCall(nil)
            }
        }
    }

    private func setCall(_ next: Call?) {
        let changed = (call == nil) != (next == nil) || call?.state != next?.state
        if call?.state != .ringing, next?.state == .ringing { log("calls: ringing") }
        call = next
        if next == nil { muted = false }
        updateRinging()
        if changed { onChange?() }
    }

    /// A soft sound every few seconds while the call rings and the panel shows it.
    private func updateRinging() {
        if call?.state == .ringing {
            guard ringTimer == nil else { return }
            NSSound(named: NSSound.Name("Glass"))?.play()
            ringTimer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { _ in
                NSSound(named: NSSound.Name("Glass"))?.play()
            }
        } else {
            ringTimer?.invalidate()
            ringTimer = nil
        }
    }

    // MARK: - The audio

    private func startAudio() {
        guard audio == nil, link != nil else { return }
        let audioLink = self.audioLink
        let audio = CallAudio { frame in
            audioLink.link?.sendAudio(frame)
        }
        audio.muted = muted
        audio.onRestart = { [weak self, weak audio] what, withoutVoiceProcessing in
            Task { @MainActor in
                guard let self, let audio else { return }
                self.log("calls: audio: \(what)")
                if withoutVoiceProcessing, self.audio === audio {
                    self.call?.error = "Echo cancellation stopped working on this Mac, so the caller may hear themselves. Headphones help."
                }
            }
        }
        self.audio = audio
        audioLink.audio = audio
        Task { [weak self] in
            let allowed = await CallAudio.microphoneAllowed()
            guard let self, self.audio === audio else { return }
            if !allowed {
                self.call?.error = "Allow the microphone for Prism (System Settings → Privacy & Security → Microphone)."
            }
            do {
                try audio.start()
                let devices = audio.devices
                self.log("calls: the call's audio is on this Mac (playing to \(devices.output), recording from \(devices.input))")
                try? await Task.sleep(for: .seconds(3))
                if self.audio === audio, audio.outputStalled {
                    self.log("calls: \(devices.output) plays nothing")
                    self.call?.error = "\(devices.output) plays nothing; it may be busy with another device. Choose another output in Sound settings, or move the call to the phone."
                }
            } catch {
                self.log("calls: audio failed: \(error.localizedDescription)")
                self.call?.error = "The Mac's audio failed: \(error.localizedDescription)"
                self.audio = nil
                self.audioLink.audio = nil
                self.moveToPhone()
            }
        }
    }

    private func stopAudio() {
        guard let audio else { return }
        self.audio = nil
        audioLink.audio = nil
        delay = nil
        recentDelays = []
        audio.stop()
        log("calls: the call's audio left this Mac")
    }

    // MARK: - The key and the contract

    private func key() -> SymmetricKey? {
        guard let keyFile,
              let text = (try? String(contentsOfFile: keyFile, encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines),
              let bytes = Hotspot.hex(text), bytes.count == 32 else { return nil }
        return SymmetricKey(data: bytes)
    }

    /// K = HMAC-SHA256(hotspot key, "prism-call/v2").
    nonisolated static func callKey(_ hotspotKey: SymmetricKey) -> SymmetricKey {
        SymmetricKey(data: Data(HMAC<SHA256>.authenticationCode(for: Data(label.utf8), using: hotspotKey)))
    }

    /// The phone's proof: HMAC(K, "phone" || nm || np).
    nonisolated static func phoneProof(_ k: SymmetricKey, macNonce: Data, phoneNonce: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: Data("phone".utf8) + macNonce + phoneNonce, using: k))
    }

    /// The Mac's proof: HMAC(K, "mac" || np || nm).
    nonisolated static func macProof(_ k: SymmetricKey, phoneNonce: Data, macNonce: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: Data("mac".utf8) + phoneNonce + macNonce, using: k))
    }

    /// A direction's AES-256-GCM key: HMAC(K, "phone->mac" || nm || np) or
    /// HMAC(K, "mac->phone" || nm || np).
    nonisolated static func directionKey(_ k: SymmetricKey, toMac: Bool, macNonce: Data, phoneNonce: Data) -> SymmetricKey {
        let label = toMac ? "phone->mac" : "mac->phone"
        return SymmetricKey(data: Data(HMAC<SHA256>.authenticationCode(for: Data(label.utf8) + macNonce + phoneNonce, using: k)))
    }

    /// The nonce of a direction's frame `counter`: 4 zero bytes, then u64be.
    nonisolated static func frameNonce(_ counter: UInt64) -> AES.GCM.Nonce {
        var bytes = Data(count: 4)
        withUnsafeBytes(of: counter.bigEndian) { bytes.append(contentsOf: $0) }
        return try! AES.GCM.Nonce(data: bytes)
    }

    /// An encrypted frame's payload: ciphertext || 16-byte tag, the type byte
    /// as additional data.
    nonisolated static func seal(_ plaintext: Data, type: UInt8, counter: UInt64, key: SymmetricKey) -> Data {
        let box = try! AES.GCM.seal(plaintext, using: key, nonce: frameNonce(counter), authenticating: Data([type]))
        var payload = Data(capacity: plaintext.count + 16)
        payload.append(box.ciphertext)
        payload.append(box.tag)
        return payload
    }

    /// The plaintext of an encrypted frame's payload, nil if it doesn't decrypt.
    nonisolated static func open(_ payload: Data, type: UInt8, counter: UInt64, key: SymmetricKey) -> Data? {
        guard payload.count >= 16 else { return nil }
        let payload = Data(payload)
        guard let box = try? AES.GCM.SealedBox(nonce: frameNonce(counter), ciphertext: payload.dropLast(16), tag: payload.suffix(16)) else { return nil }
        return try? AES.GCM.open(box, using: key, authenticating: Data([type]))
    }

    /// The call's Bluetooth prefix (the hotspot's is 51 52 28 f6).
    nonisolated static let bluetoothPrefix: [UInt8] = [0x51, 0x52, 0x28, 0xf7]

    /// The Mac's Bluetooth service UUID for `window` and the channel's `psm`:
    /// 51 52 28 f7, the PSM (u16be), then
    /// HMAC-SHA256(K, "prism-call-ble/v1" || u64be(window) || u16be(PSM))[0..10].
    nonisolated static func bluetoothUUID(callKey: SymmetricKey, window: UInt64, psm: UInt16) -> CBUUID {
        let psmBytes = Data([UInt8(psm >> 8), UInt8(psm & 0xff)])
        var message = Data(bluetoothLabel.utf8)
        withUnsafeBytes(of: window.bigEndian) { message.append(contentsOf: $0) }
        message.append(psmBytes)
        let tag = HMAC<SHA256>.authenticationCode(for: message, using: callKey)
        return CBUUID(data: Data(bluetoothPrefix) + psmBytes + Data(tag).prefix(10))
    }

    /// docs/calls.md's vectors: hotspot key 00..1f, nm 16 x a1, np 16 x b2;
    /// the ADPCM frames of a 440 Hz sine.
    nonisolated static func selfTest() -> Bool {
        let k = callKey(SymmetricKey(data: Data(0..<32)))
        let nm = Data(repeating: 0xa1, count: 16), np = Data(repeating: 0xb2, count: 16)
        let toMac = directionKey(k, toMac: true, macNonce: nm, phoneNonce: np)
        let toPhone = directionKey(k, toMac: false, macNonce: nm, phoneNonce: np)
        let answer = Data(#"{"type":"answer"}"#.utf8)
        let macFrame = seal(answer, type: CallConnection.message, counter: 0, key: toPhone)
        let phoneFrame = seal(answer, type: CallConnection.message, counter: 1, key: toMac)
        let hex: (Data) -> String = { $0.map { String(format: "%02x", $0) }.joined() }
        let sha: (Data) -> Data = { Data(SHA256.hash(data: $0)) }

        // 640 samples of round(8000 sin(2π 440 i / 16000)), two frames from predictor 0, index 0.
        var pcm = Data(capacity: 1280)
        for i in 0..<640 {
            let sample = Int16((8000 * sin(2 * Double.pi * 440 * Double(i) / 16000)).rounded())
            withUnsafeBytes(of: sample.littleEndian) { pcm.append(contentsOf: $0) }
        }
        var state = IMAADPCM.State()
        let frame0 = IMAADPCM.encode(pcm.prefix(640), state: &state)
        let frame1 = IMAADPCM.encode(pcm.suffix(640), state: &state)
        let decoded1 = IMAADPCM.decode(frame1) ?? Data()

        let results = [
            (hex(k.withUnsafeBytes { Data($0) }), "851c91dae3b20db650e63a523064dbf9e605f57b3c7f0e1125b239ed9c0e47f7", "call key"),
            (hex(phoneProof(k, macNonce: nm, phoneNonce: np)), "6d2840f6c2b6cb1bb98a9d9eea1e3580a827eadceea38372c3463c54a60ea3ef", "call phone proof"),
            (hex(macProof(k, phoneNonce: np, macNonce: nm)), "0b26ccd7c0ed51269b5108d93ec61edf7ff01d2a29849ea842944800e3b263a7", "call Mac proof"),
            (hex(toMac.withUnsafeBytes { Data($0) }), "3c7eca885cb1f3c6ab0dcadbd210d4b07f7bb61e4469ca63319807b53023a43c", "call phone → Mac key"),
            (hex(toPhone.withUnsafeBytes { Data($0) }), "e217c291c386519a354b1495f052bfa3ee077d449a732034e729b15ed389e0a1", "call Mac → phone key"),
            (hex(macFrame), "1460883d130b6181c25fdaf6e9590c9ab3944501a65af93501ef0fa7e62e2e8b85", "call Mac → phone frame 0"),
            (hex(phoneFrame), "82c2303e6af04e6dbef747805e1be4ecdecd0470cdb1f26aac9213a56f64603c11", "call phone → Mac frame 1"),
            (open(phoneFrame, type: CallConnection.message, counter: 1, key: toMac).map { String(decoding: $0, as: UTF8.self) } ?? "nil",
             #"{"type":"answer"}"#, "call phone → Mac frame 1 decrypted"),
            (open(phoneFrame, type: CallConnection.message, counter: 0, key: toMac) == nil ? "rejected" : "accepted",
             "rejected", "call frame with the wrong number"),
            (bluetoothUUID(callKey: k, window: 59_999_999, psm: 0x0080).uuidString.lowercased(), "515228f7-0080-8c3b-e48f-5aa7d078035d", "call Bluetooth UUID"),
            (hex(frame0.prefix(12)), "0000000070777777378898aa", "ADPCM frame 0 head"),
            (hex(sha(frame0)), "e17b4bcf8c19563fdf44bfc80c0d852b8051669c7df1d8fa9b57eb7510953e96", "ADPCM frame 0"),
            (hex(frame1.prefix(12)), "5be132002163433443333212", "ADPCM frame 1 head"),
            (hex(sha(frame1)), "48e4a988cd7299c7a31c0549af4343cad970723df63ee885bb4a3a3fa3b84010", "ADPCM frame 1"),
            (hex(sha(decoded1)), "a1a915d51880d90d3115543ac3943552faf742a4a52d73a4f528513dbbb9cee6", "ADPCM frame 1 decoded"),
        ].map { value, expected, name in
            let passed = value == expected
            print(passed ? "self-test passed: \(name) for the test vector" : "self-test FAILED: \(name) \(value), expected \(expected)")
            return passed
        }
        return results.allSatisfy { $0 }
    }

    private func log(_ text: String) {
        Hotspot.log(text)
    }
}

/// The link the call's audio goes to, set on the main actor and read on the
/// audio thread.
final class AudioLink: @unchecked Sendable {
    private let lock = NSLock()
    private weak var _link: CallConnection?
    private var _audio: CallAudio?

    var link: CallConnection? {
        get { lock.withLock { _link } }
        set { lock.withLock { _link = newValue } }
    }

    /// The call's audio while it's on this Mac: frames from the phone go
    /// straight to it from the link's queue, not through the main thread.
    var audio: CallAudio? {
        get { lock.withLock { _audio } }
        set { lock.withLock { _audio = newValue } }
    }
}

/// Paces a link's audio by its delay (docs/calls.md, "Acks"): the receiver
/// acks every 5th audio frame with the count it has, and the sender times
/// each ack against when that frame went out. The least of those times over
/// the last 5–10 s is the path's own delay; more than `behind` over it for
/// `sustained` acks means frames wait in buffers on the way (an LE link that
/// slowed down, a busy network), and the sender then leaves out one frame in
/// four until it drains.
/// One per link, on its queue.
struct Pace {
    static let ackEvery = 5
    /// Late by this much, for `sustained` acks in a row (300 ms), is a
    /// backlog, not the link's unevenness.
    static let behind = 250
    static let sustained = 3
    private var sentAt = [Int](repeating: 0, count: 256)
    private var sent = 0
    private var least = Int.max
    private var leastBefore = Int.max
    private var since = 0
    private var late = 0
    private var turn = 0
    var skipped = 0

    private static func now() -> Int { Int(DispatchTime.now().uptimeNanoseconds / 1_000_000) }

    /// Whether the next frame goes out; counts it if so.
    mutating func shouldSend() -> Bool {
        // Backed up: one frame in four stays home until it drains.
        if late >= Self.sustained {
            turn += 1
            if turn % 4 == 0 {
                skipped += 1
                return false
            }
        }
        sentAt[sent & 255] = Self.now()
        sent += 1
        return true
    }

    /// The receiver has `n` frames.
    mutating func acked(_ n: Int) {
        guard n > 0, n <= sent, sent - n < 256 else { return }
        let now = Self.now()
        let age = now - sentAt[(n - 1) & 255]
        if now - since > 5000 {
            leastBefore = least
            least = .max
            since = now
        }
        least = min(least, age)
        late = age - min(least, leastBefore) > Self.behind ? late + 1 : 0
    }
}

// MARK: - The connection

/// A byte stream to the phone: TCP or a Bluetooth LE L2CAP channel.
protocol CallTransport: AnyObject, Sendable {
    /// Who it is, for the log.
    var peer: String { get }
    /// A Bluetooth link: audio goes as ADPCM.
    var bluetooth: Bool { get }
    /// Bytes written but not yet sent.
    var backlog: Int { get }
    /// Starts it; `ready`, `received` and `closed` run on `queue`, and
    /// `closed` once, whatever ends it (`cancel` included).
    func start(queue: DispatchQueue, ready: @escaping () -> Void, received: @escaping (Data) -> Void, closed: @escaping (String) -> Void)
    func write(_ data: Data)
    func cancel(_ reason: String)
}

/// One link from the phone, over any transport: the handshake, then
/// encrypted frames (type, u16be length, payload). Its callbacks run on its
/// queue, and so does everything it does.
final class CallConnection: @unchecked Sendable {
    static let hello: UInt8 = 1
    static let auth: UInt8 = 2
    static let message: UInt8 = 3
    static let audioPCM: UInt8 = 4
    static let audioADPCM: UInt8 = 5
    static let maxPayload = 16 * 1024
    static let pcmBytes = 640
    /// How far audio may fall behind before frames are dropped (200 ms).
    static let maxAudioBehind = 10

    var onReady: (() -> Void)?
    var onMessage: ((Data) -> Void)?
    /// 20 ms of PCM, whichever way it came.
    var onAudio: ((Data) -> Void)?
    var onClose: ((String) -> Void)?

    let transport: CallTransport
    var peer: String { transport.peer }
    private let key: SymmetricKey
    private let queue: DispatchQueue
    private let macNonce: Data
    private var authenticated = false
    private var closed = false
    private var buffer = Data()
    private var sendKey: SymmetricKey?
    private var receiveKey: SymmetricKey?
    private var sent: UInt64 = 0
    private var received: UInt64 = 0
    private var encoder = IMAADPCM.State()
    /// Audio frames from the phone on this link, and the pacing of ours (on the queue).
    private var audioReceived = 0
    private var pace = Pace()
    /// Frames the pacing left out since the last look.
    var skipped: Int { queue.sync { defer { pace.skipped = 0 }; return pace.skipped } }

    init(transport: CallTransport, key: SymmetricKey, queue: DispatchQueue) {
        self.transport = transport
        self.key = key
        self.queue = queue
        var nonce = Data(count: 16)
        _ = nonce.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        macNonce = nonce
    }

    func start() {
        transport.start(
            queue: queue,
            ready: { [weak self] in
                guard let self else { return }
                self.write(type: Self.hello, payload: self.macNonce)
            },
            received: { [weak self] data in self?.receive(data) },
            closed: { [weak self] reason in self?.close(reason) })
        // A peer that doesn't prove itself in 5 s is dropped.
        queue.asyncAfter(deadline: .now() + 5) { [weak self] in
            guard let self, !self.authenticated, !self.closed else { return }
            self.close("no proof in 5 s")
        }
    }

    /// Sends an encrypted frame (after the handshake; dropped before).
    func send(type: UInt8, payload: Data) {
        queue.async { [self] in
            guard authenticated, !closed else { return }
            sendSealed(type: type, payload: payload)
        }
    }

    /// Sends 20 ms of the microphone's PCM: ADPCM over Bluetooth, PCM over
    /// TCP. Dropped while the link is more than 200 ms behind.
    func sendAudio(_ pcm: Data) {
        queue.async { [self] in
            guard authenticated, !closed else { return }
            let type: UInt8, payload: Data
            if transport.bluetooth {
                // Encoded even when dropped, so the encoder follows the signal.
                type = Self.audioADPCM
                payload = IMAADPCM.encode(pcm, state: &encoder)
            } else {
                type = Self.audioPCM
                payload = pcm
            }
            guard transport.backlog <= Self.maxAudioBehind * (payload.count + 19), pace.shouldSend() else { return }
            sendSealed(type: type, payload: payload)
        }
    }

    func cancel(_ reason: String) {
        queue.async { [self] in close(reason) }
    }

    private func close(_ reason: String) {
        guard !closed else { return }
        closed = true
        transport.cancel(reason)
        onClose?(reason)
    }

    private func sendSealed(type: UInt8, payload: Data) {
        guard let sendKey else { return }
        let sealed = PhoneCalls.seal(payload, type: type, counter: sent, key: sendKey)
        sent += 1
        write(type: type, payload: sealed)
    }

    private func write(type: UInt8, payload: Data) {
        guard payload.count <= Self.maxPayload else { return }
        var frame = Data([type, UInt8(payload.count >> 8), UInt8(payload.count & 0xff)])
        frame.append(payload)
        transport.write(frame)
    }

    /// Bytes from the transport, cut into frames.
    private func receive(_ data: Data) {
        guard !closed else { return }
        buffer.append(data)
        while buffer.count >= 3, !closed {
            let start = buffer.startIndex
            let type = buffer[start]
            let length = Int(buffer[start + 1]) << 8 | Int(buffer[start + 2])
            guard length <= Self.maxPayload else {
                close("frame too long: \(length)")
                return
            }
            guard buffer.count >= 3 + length else { break }
            let payload = Data(buffer[(start + 3)..<(start + 3 + length)])
            buffer = Data(buffer[(start + 3 + length)...])
            frame(type: type, payload: payload)
        }
    }

    private func frame(type: UInt8, payload: Data) {
        guard authenticated else {
            guard type == Self.auth, payload.count == 48 else {
                close("no proof from the phone")
                return
            }
            let phoneNonce = Data(payload.prefix(16))
            let proof = Data(payload.suffix(32))
            let expected = PhoneCalls.phoneProof(key, macNonce: macNonce, phoneNonce: phoneNonce)
            guard constantTimeEqual(proof, expected) else {
                close("a wrong proof")
                return
            }
            authenticated = true
            write(type: Self.auth, payload: PhoneCalls.macProof(key, phoneNonce: phoneNonce, macNonce: macNonce))
            sendKey = PhoneCalls.directionKey(key, toMac: false, macNonce: macNonce, phoneNonce: phoneNonce)
            receiveKey = PhoneCalls.directionKey(key, toMac: true, macNonce: macNonce, phoneNonce: phoneNonce)
            onReady?()
            return
        }
        guard type != Self.hello, type != Self.auth else {
            close("a handshake frame after the handshake")
            return
        }
        // Every frame after the handshake is encrypted and counts, a type
        // this side doesn't know too.
        guard let receiveKey, let plain = PhoneCalls.open(payload, type: type, counter: received, key: receiveKey) else {
            close("a frame that doesn't decrypt")
            return
        }
        received += 1
        switch type {
        case Self.message:
            // Acks are the link's own business: handled here, on its queue.
            if plain.count < 64, let json = try? JSONSerialization.jsonObject(with: plain) as? [String: Any],
               json["type"] as? String == "ack" {
                if let n = (json["n"] as? NSNumber)?.intValue { pace.acked(n) }
                return
            }
            onMessage?(plain)
        case Self.audioPCM:
            heard(plain)
        case Self.audioADPCM:
            if let pcm = IMAADPCM.decode(plain) { heard(pcm) }
        default:
            break
        }
    }

    /// An audio frame from the phone; every 5th gets an ack (Pace).
    private func heard(_ pcm: Data) {
        audioReceived += 1
        if audioReceived % Pace.ackEvery == 0,
           let ack = try? JSONSerialization.data(withJSONObject: ["type": "ack", "n": audioReceived]) {
            sendSealed(type: Self.message, payload: ack)
        }
        onAudio?(pcm)
    }

    private func constantTimeEqual(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        return zip(a, b).reduce(0) { $0 | ($1.0 ^ $1.1) } == 0
    }
}

/// A TCP connection from the phone.
final class TCPTransport: CallTransport, @unchecked Sendable {
    let peer: String
    let bluetooth = false
    private let connection: NWConnection
    private let lock = NSLock()
    private var _backlog = 0
    private var finished = false
    private var closed: ((String) -> Void)?

    init(_ connection: NWConnection) {
        self.connection = connection
        peer = "\(connection.endpoint)"
    }

    var backlog: Int { lock.withLock { _backlog } }

    func start(queue: DispatchQueue, ready: @escaping () -> Void, received: @escaping (Data) -> Void, closed: @escaping (String) -> Void) {
        self.closed = closed
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                ready()
                self.receive(received)
            case .failed(let error):
                self.finish("failed: \(error.localizedDescription)")
            case .cancelled:
                self.finish("cancelled")
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func receive(_ received: @escaping (Data) -> Void) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, done, error in
            guard let self else { return }
            if let data, !data.isEmpty { received(data) }
            if let error {
                self.finish("receive: \(error.localizedDescription)")
            } else if done {
                self.finish("the phone closed it")
            } else {
                self.receive(received)
            }
        }
    }

    func write(_ data: Data) {
        let count = data.count
        lock.withLock { _backlog += count }
        connection.send(content: data, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            self.lock.withLock { self._backlog -= count }
            if let error { self.finish("send: \(error.localizedDescription)") }
        })
    }

    func cancel(_ reason: String) {
        finish(reason)
    }

    private func finish(_ reason: String) {
        let first: Bool = lock.withLock {
            defer { finished = true }
            return !finished
        }
        guard first else { return }
        connection.cancel()
        closed?(reason)
        closed = nil
    }
}

/// An L2CAP channel to the phone. Its streams run on a thread of their own
/// (a run loop); what they read goes to the connection's queue.
final class L2CAPTransport: NSObject, CallTransport, StreamDelegate, @unchecked Sendable {
    let peer: String
    let bluetooth = true
    /// Called on the main queue once it's closed, with why.
    var onFinished: ((String) -> Void)? {
        get { lock.withLock { _onFinished } }
        set { lock.withLock { _onFinished = newValue } }
    }

    private let input: InputStream
    private let output: OutputStream
    /// What keeps the channel open while it's held (the CBL2CAPChannel).
    private let owner: AnyObject?
    private let lock = NSLock()
    private var _onFinished: ((String) -> Void)?
    private var outgoing = Data()
    private var finished = false
    private var runLoop: CFRunLoop?
    private var queue: DispatchQueue?
    private var received: ((Data) -> Void)?
    private var closed: ((String) -> Void)?
    /// On the stream thread only.
    private var stopped = false

    init(input: InputStream, output: OutputStream, owner: AnyObject?, peer: String) {
        self.input = input
        self.output = output
        self.owner = owner
        self.peer = peer
    }

    /// The channel's streams; nil if it has none.
    convenience init?(channel: CBL2CAPChannel, peer: String) {
        guard let input = channel.inputStream, let output = channel.outputStream else { return nil }
        self.init(input: input, output: output, owner: channel, peer: peer)
    }

    var backlog: Int { lock.withLock { outgoing.count } }

    func start(queue: DispatchQueue, ready: @escaping () -> Void, received: @escaping (Data) -> Void, closed: @escaping (String) -> Void) {
        lock.withLock {
            self.queue = queue
            self.received = received
            self.closed = closed
        }
        let thread = Thread { [self] in run(ready: ready) }
        thread.name = "prism.calls.l2cap"
        thread.qualityOfService = .userInteractive
        thread.start()
    }

    private func run(ready: @escaping () -> Void) {
        let loop = RunLoop.current
        // Cancelled before the thread got here: nothing to run.
        let cancelled: Bool = lock.withLock {
            runLoop = CFRunLoopGetCurrent()
            return finished
        }
        guard !cancelled else { return }
        // Keeps the run loop running whatever the streams do.
        loop.add(NSMachPort(), forMode: .default)
        for stream in [input, output] as [Stream] {
            stream.delegate = self
            stream.schedule(in: loop, forMode: .default)
            stream.open()
        }
        queue?.async(execute: ready)
        while !stopped {
            _ = loop.run(mode: .default, before: .distantFuture)
        }
        for stream in [input, output] as [Stream] {
            stream.delegate = nil
            stream.remove(from: loop, forMode: .default)
            stream.close()
        }
    }

    func write(_ data: Data) {
        let loop: CFRunLoop? = lock.withLock {
            guard !finished else { return nil }
            outgoing.append(data)
            return runLoop
        }
        guard let loop else { return }
        CFRunLoopPerformBlock(loop, CFRunLoopMode.defaultMode.rawValue) { [self] in flush() }
        CFRunLoopWakeUp(loop)
    }

    /// On the stream thread: as much of `outgoing` as the stream takes.
    private func flush() {
        while output.hasSpaceAvailable {
            let chunk: Data = lock.withLock { outgoing.prefix(4096) }
            guard !chunk.isEmpty else { return }
            let written = chunk.withUnsafeBytes { output.write($0.bindMemory(to: UInt8.self).baseAddress!, maxLength: chunk.count) }
            guard written > 0 else {
                if written < 0 { finish("write: \(output.streamError?.localizedDescription ?? "failed")") }
                return
            }
            lock.withLock { outgoing = Data(outgoing.dropFirst(written)) }
        }
    }

    func stream(_ stream: Stream, handle event: Stream.Event) {
        switch event {
        case .hasBytesAvailable:
            guard let input = stream as? InputStream else { return }
            var data = Data()
            var bytes = [UInt8](repeating: 0, count: 4096)
            while input.hasBytesAvailable {
                let count = input.read(&bytes, maxLength: bytes.count)
                if count > 0 {
                    data.append(contentsOf: bytes[0..<count])
                } else {
                    if count < 0 { finish("read: \(input.streamError?.localizedDescription ?? "failed")") }
                    break
                }
            }
            if !data.isEmpty, let queue, let received {
                queue.async { received(data) }
            }
        case .hasSpaceAvailable:
            flush()
        case .endEncountered:
            finish("the phone closed the channel")
        case .errorOccurred:
            finish("stream error: \(stream.streamError?.localizedDescription ?? "unknown")")
        default:
            break
        }
    }

    func cancel(_ reason: String) {
        finish(reason)
    }

    private func finish(_ reason: String) {
        let (first, loop, closed, onFinished): (Bool, CFRunLoop?, ((String) -> Void)?, ((String) -> Void)?) = lock.withLock {
            guard !finished else { return (false, nil, nil, nil) }
            finished = true
            outgoing = Data()
            defer {
                self.closed = nil
                self._onFinished = nil
            }
            return (true, runLoop, self.closed, self._onFinished)
        }
        guard first else { return }
        if let loop {
            CFRunLoopPerformBlock(loop, CFRunLoopMode.defaultMode.rawValue) { [self] in
                stopped = true
                CFRunLoopStop(loop)
            }
            CFRunLoopWakeUp(loop)
        }
        if let closed { (queue ?? .main).async { closed(reason) } }
        if let onFinished { DispatchQueue.main.async { onFinished(reason) } }
    }
}

// MARK: - Bluetooth

/// The Mac's side of the Bluetooth link (docs/calls.md, "Bluetooth"): while
/// calls are set up (there's a key) and Bluetooth is on, it publishes an LE
/// L2CAP channel without Bluetooth encryption and advertises it as one
/// service UUID, the call's prefix, the PSM and a tag over the 30 s window,
/// new at every window. The phone scans for it during a call and opens the
/// channel; each channel that opens is handed over as a transport, and the
/// Mac says hello first as over TCP.
///
/// It has its own peripheral manager, apart from the hotspot's
/// (Hotspot.swift), but macOS gives all apps' advertisers one advertisement
/// with room for one 128-bit UUID (the rest go to Apple's hashed overflow
/// area, which Android can't match), held by the advertiser that registered
/// first. So it stops advertising while the hotspot's request or release
/// advertises (`yields`): that's while the Mac has no network, or for the
/// 30 s of a release, when the call's TCP link has the hotspot anyway.
@MainActor
final class CallBluetooth: NSObject {
    /// A channel to hand to a connection.
    var onChannel: ((L2CAPTransport) -> Void)?

    private let key: () -> SymmetricKey?
    private let yields: () -> Bool
    private var manager: CBPeripheralManager?
    /// The published channel's PSM.
    private var psm: CBL2CAPPSM?
    /// A channel was asked for and not yet published.
    private var publishing = false
    /// A PSM published before Bluetooth went off, unpublished once it's back.
    private var stalePSM: CBL2CAPPSM?
    private var advertisedUUID: CBUUID?
    /// Whether the log says it advertises (logged when that changes).
    private var advertisingLogged = false
    private var lastAdvertisingError: String?
    private var windowTimer: Timer?
    private var stateLogged: CBManagerState?
    /// The channels the phone opened and their transports, until they close.
    private var channels: [ObjectIdentifier: L2CAPTransport] = [:]

    /// `key`: the call key K, nil while there's none (calls aren't set up).
    /// `yields`: whether the hotspot advertises, and this mustn't.
    init(key: @escaping () -> SymmetricKey?, yields: @escaping () -> Bool) {
        self.key = key
        self.yields = yields
        super.init()
    }

    /// Something `update` depends on changed (the hotspot's advertising).
    func refresh() {
        update()
    }

    func start() {
        guard manager == nil else { return }
        manager = CBPeripheralManager(delegate: self, queue: .main)
    }

    /// Brings the channel and the advertisement in line with the key and
    /// Bluetooth's state, and comes back at the next window.
    private func update() {
        windowTimer?.invalidate()
        windowTimer = nil
        guard let manager, manager.state == .poweredOn else { return }
        if let stale = stalePSM {
            stalePSM = nil
            manager.unpublishL2CAPChannel(stale)
        }
        let now = Date().timeIntervalSince1970
        let window = UInt64(now / Hotspot.window)
        let next = Double(window + 1) * Hotspot.window - now
        windowTimer = Timer.scheduledTimer(withTimeInterval: next + 0.05, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.update() }
        }
        guard let key = key() else {
            // No key: calls aren't set up. Checked again at the next window,
            // since a key may come on activation.
            stopAdvertising(reason: "no call key")
            if let psm {
                self.psm = nil
                manager.unpublishL2CAPChannel(psm)
                log("calls: unpublished the Bluetooth L2CAP channel (PSM \(psm)): no call key")
            }
            return
        }
        guard let psm else {
            if !publishing {
                publishing = true
                manager.publishL2CAPChannel(withEncryption: false)
            }
            return
        }
        if yields() {
            stopAdvertising(reason: "the hotspot's advertisement has the slot")
            return
        }
        let uuid = PhoneCalls.bluetoothUUID(callKey: key, window: window, psm: psm)
        guard uuid != advertisedUUID else { return }
        manager.stopAdvertising()
        manager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [uuid]])
        advertisedUUID = uuid
    }

    private func stopAdvertising(reason: String) {
        guard advertisedUUID != nil else { return }
        manager?.stopAdvertising()
        advertisedUUID = nil
        if advertisingLogged {
            advertisingLogged = false
            log("calls: stopped advertising over Bluetooth (\(reason))")
        }
    }

    private func published(_ psm: CBL2CAPPSM, error: Error?) {
        publishing = false
        guard manager?.state == .poweredOn else { return }
        if let error {
            // Tried again at the next window.
            log("calls: publishing the Bluetooth L2CAP channel failed: \(error.localizedDescription)")
            return
        }
        self.psm = psm
        log("calls: published the Bluetooth L2CAP channel, PSM \(psm)")
        update()
    }

    private func startedAdvertising(error: Error?) {
        if let error {
            let text = error.localizedDescription
            // Tried again at the next window (a new UUID).
            advertisedUUID = nil
            advertisingLogged = false
            if text != lastAdvertisingError {
                lastAdvertisingError = text
                log("calls: advertising over Bluetooth failed: \(text)")
            }
            return
        }
        lastAdvertisingError = nil
        guard !advertisingLogged else { return }
        advertisingLogged = true
        log("calls: advertising over Bluetooth (PSM \(psm.map { "\($0)" } ?? "?"), a new service UUID every \(Int(Hotspot.window)) s)")
    }

    private func opened(_ channel: CBL2CAPChannel?, error: Error?) {
        guard let channel, error == nil else {
            log("calls: a Bluetooth L2CAP channel didn't open: \(error?.localizedDescription ?? "no channel")")
            return
        }
        let peer = "Bluetooth \(channel.peer.identifier.uuidString.prefix(8)), PSM \(channel.psm)"
        guard let transport = L2CAPTransport(channel: channel, peer: peer) else {
            log("calls: the Bluetooth L2CAP channel from \(peer) has no streams")
            return
        }
        log("calls: the phone opened the Bluetooth L2CAP channel (\(peer))")
        let id = ObjectIdentifier(transport)
        channels[id] = transport
        transport.onFinished = { [weak self] reason in
            MainActor.assumeIsolated {
                guard let self, self.channels.removeValue(forKey: id) != nil else { return }
                self.log("calls: Bluetooth link to the phone closed (\(reason))")
            }
        }
        onChannel?(transport)
    }

    private func closeChannels(_ reason: String) {
        let open = channels
        channels = [:]
        for transport in open.values {
            transport.onFinished = nil
            transport.cancel(reason)
            log("calls: Bluetooth link to the phone closed (\(reason))")
        }
    }

    private func stateChanged(_ state: CBManagerState) {
        if state == .poweredOn {
            stateLogged = nil
            update()
            return
        }
        // The channel and the advertisement are gone with the radio; both
        // come again once it's back.
        windowTimer?.invalidate()
        windowTimer = nil
        if let psm { stalePSM = psm }
        psm = nil
        publishing = false
        advertisedUUID = nil
        if advertisingLogged {
            advertisingLogged = false
            log("calls: stopped advertising over Bluetooth (Bluetooth is \(Self.describe(state)))")
        }
        closeChannels("Bluetooth is \(Self.describe(state))")
        if state != .unknown, state != .resetting, stateLogged != state {
            stateLogged = state
            log("calls: Bluetooth is \(Self.describe(state)); calls link over the network only")
        }
    }

    private static func describe(_ state: CBManagerState) -> String {
        switch state {
        case .poweredOff: "off"
        case .unauthorized: "not allowed for Prism (System Settings → Privacy & Security → Bluetooth)"
        case .unsupported: "not supported on this Mac"
        case .resetting: "resetting"
        default: "not ready"
        }
    }

    private func log(_ text: String) {
        Hotspot.log(text)
    }
}

extension CallBluetooth: CBPeripheralManagerDelegate {
    nonisolated func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        let state = peripheral.state
        MainActor.assumeIsolated { stateChanged(state) }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, didPublishL2CAPChannel PSM: CBL2CAPPSM, error: Error?) {
        MainActor.assumeIsolated { published(PSM, error: error) }
    }

    nonisolated func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        MainActor.assumeIsolated { startedAdvertising(error: error) }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, didOpen channel: CBL2CAPChannel?, error: Error?) {
        MainActor.assumeIsolated { opened(channel, error: error) }
    }
}

// MARK: - ADPCM

/// IMA ADPCM as docs/calls.md has it: a frame is the encoder's state before
/// its first sample (predictor i16le, step index u8, a zero byte), then the
/// 4-bit codes, the even sample in the low nibble.
enum IMAADPCM {
    struct State {
        var predictor: Int = 0
        var index: Int = 0
    }

    static let steps: [Int] = [
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
        253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
        1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
        3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487,
        12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
    ]
    static let indexTable = [-1, -1, -1, -1, 2, 4, 6, 8]

    /// Encodes PCM (i16le samples) from `state`, which it advances.
    static func encode(_ pcm: Data, state: inout State) -> Data {
        let samples = pcm.count / 2
        var out = Data(capacity: 4 + (samples + 1) / 2)
        let head = UInt16(bitPattern: Int16(state.predictor))
        out.append(contentsOf: [UInt8(head & 0xff), UInt8(head >> 8), UInt8(state.index), 0])
        var predictor = state.predictor, index = state.index
        var low: UInt8 = 0
        pcm.withUnsafeBytes { raw in
            for i in 0..<samples {
                let sample = Int(Int16(littleEndian: raw.loadUnaligned(fromByteOffset: 2 * i, as: Int16.self)))
                let step = steps[index]
                var diff = sample - predictor
                var code = 0
                if diff < 0 {
                    code = 8
                    diff = -diff
                }
                var delta = step >> 3
                if diff >= step {
                    code |= 4
                    diff -= step
                    delta += step
                }
                if diff >= step >> 1 {
                    code |= 2
                    diff -= step >> 1
                    delta += step >> 1
                }
                if diff >= step >> 2 {
                    code |= 1
                    delta += step >> 2
                }
                predictor = min(32767, max(-32768, code & 8 != 0 ? predictor - delta : predictor + delta))
                index = min(88, max(0, index + indexTable[code & 7]))
                if i % 2 == 0 {
                    low = UInt8(code)
                } else {
                    out.append(low | UInt8(code) << 4)
                }
            }
        }
        if samples % 2 == 1 { out.append(low) }
        state = State(predictor: predictor, index: index)
        return out
    }

    /// Decodes a frame to PCM (i16le), nil if it isn't one.
    static func decode(_ frame: Data) -> Data? {
        let bytes = [UInt8](frame)
        guard bytes.count >= 4, bytes[2] <= 88 else { return nil }
        var predictor = Int(Int16(bitPattern: UInt16(bytes[0]) | UInt16(bytes[1]) << 8))
        var index = Int(bytes[2])
        var out = Data(capacity: (bytes.count - 4) * 4)
        for byte in bytes[4...] {
            for code in [Int(byte & 0x0f), Int(byte >> 4)] {
                let step = steps[index]
                var delta = step >> 3
                if code & 4 != 0 { delta += step }
                if code & 2 != 0 { delta += step >> 1 }
                if code & 1 != 0 { delta += step >> 2 }
                predictor = min(32767, max(-32768, code & 8 != 0 ? predictor - delta : predictor + delta))
                index = min(88, max(0, index + indexTable[code & 7]))
                let sample = UInt16(bitPattern: Int16(predictor))
                out.append(contentsOf: [UInt8(sample & 0xff), UInt8(sample >> 8)])
            }
        }
        return out
    }
}

// MARK: - The audio

/// The call through the Mac's speakers and microphone, with voice processing
/// (echo cancellation). Frames go out through `send`, 640 bytes each.
final class CallAudio: @unchecked Sendable {
    /// The wire's format: 16 kHz, 16-bit, mono, interleaved.
    private let wire = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16_000, channels: 1, interleaved: true)!
    /// What the player plays: the same rate as float.
    private let playFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false)!
    private static let frameBytes = 640
    /// Playback falls at most this many frames behind (200 ms).
    private static let maxQueued = 10
    /// The jitter buffer (play()): after a gap it waits for `target` frames
    /// (the one playing included) before it plays again; a gap raises the
    /// target, 10 s without one lowers it. Over a second it sheds frames
    /// that stay above the target, the rest of a burst.
    private static let minTarget = 2
    private static let maxTarget = 6
    private static let window = 50
    private static let calmFrames = 500
    private var target = CallAudio.minTarget
    private var calm = 0
    /// Frames held while the buffer fills after a gap.
    private var holding: [AVAudioPCMBuffer] = []

    // The engine and its player, under the lock: the watchdog builds new
    // ones when macOS's audio stalls.
    private var engine = AVAudioEngine()
    private var player = AVAudioPlayerNode()
    /// Counts the engines, so an old player's completions leave the count alone.
    private var generation = 0
    private var voiceProcessing = true
    /// When the microphone last delivered, under the lock.
    private var lastInput = CACurrentMediaTime()
    private var restarts = 0
    private var watchdog: DispatchSourceTimer?
    private var outputLatency = 0
    private var inputLatency = 0
    /// The microphone stopped and the audio started over: what happened,
    /// and whether it now runs without echo cancellation. On micQueue.
    var onRestart: ((_ what: String, _ withoutVoiceProcessing: Bool) -> Void)?
    /// A stall gives up after this many new engines.
    private static let maxRestarts = 6
    /// The microphone's samples, off the audio thread.
    private let micQueue = DispatchQueue(label: "at.quickme.prism.calls.mic")
    private var micFormat: AVAudioFormat?
    private let send: (Data) -> Void
    private let lock = NSLock()
    private var queued = 0
    // The jitter buffer: the fewest frames queued over the window, and frames still to shed.
    private var least = Int.max
    private var seen = 0
    private var shed = 0
    private var trimmed = 0
    /// Playback broke off (a frame dropped or shed, or none came in time):
    /// the next frame fades in, not starting mid-wave with a click.
    private var afterGap = true
    /// The last frame scheduled, to fade out over a gap.
    private var lastFrame: [Float] = []
    private var concealed = 0
    /// Samples a fade takes (5 ms).
    private static let fade = 80
    private var pending = Data()
    private var converter: AVAudioConverter?
    private var running = false
    private var _muted = false
    // For the whole call, under the lock: frames from the phone, and those the output played.
    private var receivedTotal = 0
    private var playedTotal = 0
    // Since the last stats(), under the lock.
    private var received = 0
    private var receivedPeak: Int16 = 0
    private var dropped = 0
    private var sent = 0
    private var sentPeak: Int16 = 0

    struct Stats {
        var received: Int, receivedPeak: Int, dropped: Int, trimmed: Int, concealed: Int, sent: Int, sentPeak: Int
        /// Frames waiting for the player now, and the jitter buffer's target.
        var playQueued: Int, playTarget: Int
        /// The audio devices' own delay, ms.
        var outputLatency: Int, inputLatency: Int
    }

    /// The counters since the last call, and starts them over.
    /// Doesn't touch the engine: its properties wait on coreaudiod, which
    /// can hang.
    func stats() -> Stats {
        lock.withLock {
            defer { received = 0; receivedPeak = 0; dropped = 0; trimmed = 0; concealed = 0; sent = 0; sentPeak = 0 }
            return Stats(received: received, receivedPeak: Int(receivedPeak), dropped: dropped, trimmed: trimmed, concealed: concealed, sent: sent,
                         sentPeak: Int(sentPeak), playQueued: queued, playTarget: target, outputLatency: outputLatency, inputLatency: inputLatency)
        }
    }

    /// Frames came from the phone but the output played none: it doesn't run
    /// (a Bluetooth headset busy with another device, say).
    var outputStalled: Bool { lock.withLock { receivedTotal >= 25 && playedTotal == 0 } }

    /// The devices the engine plays to and records from, by name.
    /// With voice processing one unit does both, and names the output; the
    /// input is the system's default.
    var devices: (output: String, input: String) {
        (Self.deviceName(lock.withLock { engine }.outputNode.audioUnit), Self.deviceName(Self.defaultInput()))
    }

    private static func deviceName(_ unit: AudioUnit?) -> String {
        guard let unit else { return "?" }
        var device = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        guard AudioUnitGetProperty(unit, kAudioOutputUnitProperty_CurrentDevice, kAudioUnitScope_Global, 0, &device, &size) == noErr else { return "?" }
        return deviceName(device)
    }

    private static func defaultInput() -> AudioDeviceID {
        var device = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultInputDevice, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        _ = AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &device)
        return device
    }

    private static func deviceName(_ device: AudioDeviceID) -> String {
        var size: UInt32
        var address = AudioObjectPropertyAddress(mSelector: kAudioObjectPropertyName, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var name: Unmanaged<CFString>?
        size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(device, &address, 0, nil, &size, &name) == noErr, let name else { return "device \(device)" }
        return name.takeRetainedValue() as String
    }

    private static func peak(_ samples: UnsafeBufferPointer<Int16>) -> Int16 {
        var p: Int16 = 0
        for s in samples { p = max(p, s == .min ? .max : abs(s)) }
        return p
    }

    var muted: Bool {
        get { lock.withLock { _muted } }
        set { lock.withLock { _muted = newValue } }
    }

    init(send: @escaping (Data) -> Void) {
        self.send = send
    }

    static func microphoneAllowed() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized: return true
        case .notDetermined: return await AVCaptureDevice.requestAccess(for: .audio)
        default: return false
        }
    }

    func start() throws {
        try micQueue.sync { try build(voiceProcessing: true) }
        let timer = DispatchSource.makeTimerSource(queue: micQueue)
        timer.schedule(deadline: .now() + 1, repeating: .milliseconds(250))
        timer.setEventHandler { [weak self] in self?.check() }
        timer.resume()
        watchdog = timer
    }

    /// A new engine: the player into the mixer, the microphone into a sink.
    /// On micQueue.
    private func build(voiceProcessing: Bool) throws {
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        // The output and the mixer must exist before voice processing goes
        // on: made after, the output fails to initialize (-10875).
        _ = engine.outputNode
        _ = engine.mainMixerNode
        let input = engine.inputNode
        if voiceProcessing {
            try input.setVoiceProcessingEnabled(true)
        }
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: playFormat)
        // With voice processing the input has one channel per microphone (9
        // on a MacBook Pro), float, one buffer each; the processed voice is
        // the first. A sink node gets each IO cycle (about 10 ms) as it
        // comes: a tap hands over 100 ms at a time, all of it delay.
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.commonFormat == .pcmFormatFloat32, !inputFormat.isInterleaved,
              let mono = AVAudioFormat(standardFormatWithSampleRate: inputFormat.sampleRate, channels: 1),
              let converter = AVAudioConverter(from: mono, to: wire) else {
            throw NSError(domain: "PrismCalls", code: 1, userInfo: [NSLocalizedDescriptionKey: "no converter from \(inputFormat)"])
        }
        let sink = AVAudioSinkNode { [weak self] _, frames, list in
            guard let self, let data = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: list)).first?.mData else { return noErr }
            let samples = Data(bytes: data, count: Int(frames) * MemoryLayout<Float>.size)
            self.micQueue.async { self.captured(samples) }
            return noErr
        }
        engine.attach(sink)
        engine.connect(input, to: sink, format: inputFormat)
        engine.prepare()
        try engine.start()
        player.play()
        let output = Int(engine.outputNode.presentationLatency * 1000)
        let latency = Int(input.presentationLatency * 1000)
        self.converter = converter
        micFormat = mono
        lock.withLock {
            pending = Data()
            self.engine = engine
            self.player = player
            self.voiceProcessing = voiceProcessing
            generation += 1
            queued = 0
            holding = []
            afterGap = true
            lastInput = CACurrentMediaTime()
            outputLatency = output
            inputLatency = latency
            running = true
        }
    }

    /// The watchdog, on micQueue: macOS's voice processing sometimes starts
    /// without running, or stops (coreaudiod's "StartIO: Start failed"),
    /// and then neither the microphone nor the speakers run. A new engine
    /// usually helps; the third goes without voice processing, which runs
    /// where it doesn't.
    private func check() {
        let (stalled, engine, player) = lock.withLock { (running && CACurrentMediaTime() - lastInput > 0.6, self.engine, self.player) }
        guard stalled, restarts < Self.maxRestarts else { return }
        restarts += 1
        let withVoiceProcessing = restarts < 3
        lock.withLock { running = false }
        player.stop()
        engine.stop()
        do {
            try build(voiceProcessing: withVoiceProcessing)
            onRestart?("the microphone stopped; started the audio over"
                       + (withVoiceProcessing ? "" : " without echo cancellation"), !withVoiceProcessing)
        } catch {
            // Tries again at the next stall check.
            lock.withLock {
                running = true
                lastInput = CACurrentMediaTime()
            }
            onRestart?("the microphone stopped, and the audio didn't start over: \(error.localizedDescription)", !withVoiceProcessing)
        }
    }

    /// On micQueue, like the watchdog: a rebuild under way finishes first,
    /// and its engine is the one that stops.
    func stop() {
        onRestart = nil
        micQueue.sync {
            watchdog?.cancel()
            watchdog = nil
            let (engine, player) = lock.withLock {
                running = false
                return (self.engine, self.player)
            }
            player.stop()
            engine.stop()
        }
    }

    /// The microphone (its first channel, float), converted to the wire's
    /// format and cut into frames. On micQueue.
    private func captured(_ samples: Data) {
        guard let converter, let micFormat else { return }
        lock.withLock { lastInput = CACurrentMediaTime() }
        let count = samples.count / MemoryLayout<Float>.size
        guard count > 0, let buffer = AVAudioPCMBuffer(pcmFormat: micFormat, frameCapacity: AVAudioFrameCount(count)),
              let channel = buffer.floatChannelData?[0] else { return }
        buffer.frameLength = AVAudioFrameCount(count)
        samples.withUnsafeBytes { raw in
            channel.update(from: raw.bindMemory(to: Float.self).baseAddress!, count: count)
        }
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * wire.sampleRate / buffer.format.sampleRate) + 32
        guard let out = AVAudioPCMBuffer(pcmFormat: wire, frameCapacity: capacity) else { return }
        var given = false
        var error: NSError?
        converter.convert(to: out, error: &error) { _, status in
            if given {
                status.pointee = .noDataNow
                return nil
            }
            given = true
            status.pointee = .haveData
            return buffer
        }
        guard error == nil, out.frameLength > 0, let samples = out.int16ChannelData else { return }
        let bytes = Data(bytes: samples[0], count: Int(out.frameLength) * 2)
        let peak = Self.peak(UnsafeBufferPointer(start: samples[0], count: Int(out.frameLength)))
        var frames: [Data] = []
        lock.withLock {
            guard running else { return }
            pending.append(bytes)
            if !_muted { sentPeak = max(sentPeak, peak) }
            while pending.count >= Self.frameBytes {
                let frame = pending.prefix(Self.frameBytes)
                pending.removeFirst(Self.frameBytes)
                frames.append(_muted ? Data(count: Self.frameBytes) : Data(frame))
                sent += 1
            }
        }
        frames.forEach(send)
    }

    /// A frame from the phone, into the jitter buffer; the oldest go when it falls behind.
    func play(_ frame: Data) {
        let count = frame.count / 2
        guard count > 0, let buffer = AVAudioPCMBuffer(pcmFormat: playFormat, frameCapacity: AVAudioFrameCount(count)),
              let channel = buffer.floatChannelData?[0] else { return }
        buffer.frameLength = AVAudioFrameCount(count)
        var peak: Int16 = 0
        frame.withUnsafeBytes { raw in
            let samples = raw.bindMemory(to: Int16.self)
            for i in 0..<count {
                let s = Int16(littleEndian: samples[i])
                peak = max(peak, s == .min ? .max : abs(s))
                channel[i] = Float(s) / 32768
            }
        }
        let release: (AVAudioPlayerNode, Int, [AVAudioPCMBuffer])? = lock.withLock {
            guard running else { return nil }
            received += 1
            receivedTotal += 1
            receivedPeak = max(receivedPeak, peak)
            guard queued + holding.count < Self.maxQueued else {
                dropped += 1
                afterGap = true
                return nil
            }
            if holding.isEmpty, !afterGap {
                least = min(least, queued)
                seen += 1
                if seen == Self.window {
                    shed += max(0, least - target)
                    least = .max
                    seen = 0
                }
                if shed > 0 {
                    shed -= 1
                    trimmed += 1
                    return nil
                }
            }
            calm += 1
            if calm >= Self.calmFrames {
                calm = 0
                target = max(Self.minTarget, target - 1)
            }
            holding.append(buffer)
            // After a gap: wait until the buffer holds `target` frames.
            if afterGap, queued + holding.count < target { return nil }
            if afterGap {
                // Fades in, rather than starting mid-wave with a click.
                if let first = holding.first?.floatChannelData?[0] {
                    let n = Int(holding[0].frameLength)
                    for i in 0..<min(Self.fade, n) { first[i] *= Float(i) / Float(Self.fade) }
                }
                afterGap = false
            }
            let out = holding
            holding = []
            queued += out.count
            if let last = out.last?.floatChannelData?[0] {
                lastFrame = Array(UnsafeBufferPointer(start: last, count: Int(out.last!.frameLength)))
            }
            return (player, generation, out)
        }
        guard let (player, generation, buffers) = release else { return }
        for buffer in buffers {
            player.scheduleBuffer(buffer) { [weak self] in
                self?.played(player, generation: generation)
            }
        }
    }

    /// A frame finished: when it was the last, a gap begins. The last frame
    /// plays again fading out, and the buffer waits for more next time.
    private func played(_ player: AVAudioPlayerNode, generation: Int) {
        let conceal: [Float]? = lock.withLock {
            guard self.generation == generation else { return nil }
            queued -= 1
            playedTotal += 1
            guard running, queued == 0, !afterGap else { return nil }
            afterGap = true
            concealed += 1
            calm = 0
            target = min(Self.maxTarget, target + 1)
            return lastFrame
        }
        if let conceal { fadeOut(conceal, on: player) }
    }

    private func fadeOut(_ samples: [Float], on player: AVAudioPlayerNode) {
        guard !samples.isEmpty, let buffer = AVAudioPCMBuffer(pcmFormat: playFormat, frameCapacity: AVAudioFrameCount(samples.count)),
              let channel = buffer.floatChannelData?[0] else { return }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        let n = Float(samples.count)
        for i in samples.indices { channel[i] = samples[i] * 0.6 * (1 - Float(i) / n) }
        player.scheduleBuffer(buffer, completionHandler: nil)
    }
}
