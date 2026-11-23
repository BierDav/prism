// The phone's calls on this Mac (docs/calls.md): Prism listens on TCP port
// 47470 for the phone's call bridge (the Android app's shell/CallBridge.kt),
// which connects while a call rings or goes on. It checks the phone's proof
// (HMAC with K = HMAC-SHA256(hotspot key, "prism-call/v1")), proves itself,
// then follows the call's state, and sends the owner's answer, decline,
// hang-up and moves. While the call's audio is on the Mac, CallAudio plays
// the caller through the speakers and sends the microphone, with macOS's
// voice processing (echo cancellation), as 16 kHz 16-bit mono PCM in 20 ms
// frames.
//
// CallPanel.swift shows it all.

import AppKit
import AVFoundation
import CryptoKit
import Foundation
import Network

@MainActor
final class PhoneCalls: ObservableObject {
    nonisolated static let port: UInt16 = 47470
    nonisolated static let label = "prism-call/v1"

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

    /// Called when `call` comes or goes, for the panel.
    var onChange: (() -> Void)?

    private let keyFile: String?
    private let queue = DispatchQueue(label: "prism.calls")
    private var listener: NWListener?
    private var link: CallConnection?
    /// Connections still in their handshake (nothing else holds them).
    private var candidates: [CallConnection] = []
    private var audio: CallAudio?
    private var clearTask: Task<Void, Never>?
    private var ringTimer: Timer?

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
        do {
            let tcp = NWProtocolTCP.Options()
            tcp.noDelay = true
            tcp.enableKeepalive = true
            tcp.keepaliveIdle = 10
            tcp.keepaliveInterval = 5
            tcp.keepaliveCount = 3
            let listener = try NWListener(using: NWParameters(tls: nil, tcp: tcp), on: NWEndpoint.Port(rawValue: Self.port)!)
            listener.newConnectionHandler = { [weak self] connection in
                Task { @MainActor in self?.accept(connection) }
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

    func answer() { send(["type": "answer"]) }
    func decline() { send(["type": "decline"]) }
    func hangUp() { send(["type": "hangup"]) }
    func moveToMac() { send(["type": "audio", "to": "mac"]) }
    func moveToPhone() { send(["type": "audio", "to": "phone"]) }

    private func send(_ message: [String: String]) {
        guard let link, let data = try? JSONSerialization.data(withJSONObject: message) else {
            call?.error = "The phone isn't connected."
            return
        }
        call?.error = nil
        link.send(type: CallConnection.message, payload: data)
    }

    // MARK: - The connection

    private func accept(_ connection: NWConnection) {
        guard let key = key().map({ Self.callKey($0) }) else {
            connection.cancel()
            return
        }
        let candidate = CallConnection(connection: connection, key: key, queue: queue)
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
        candidate.onAudio = { [weak self, weak candidate] frame in
            // On the connection's queue: straight to the player.
            guard let self, let candidate else { return }
            Task { @MainActor in
                if self.link === candidate { self.audio?.play(frame) }
            }
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

    /// A phone that proved itself: it replaces any earlier connection.
    private func ready(_ candidate: CallConnection) {
        candidates.removeAll { $0 === candidate }
        if let old = link, old !== candidate { old.cancel() }
        link = candidate
        connected = true
        clearTask?.cancel()
        log("calls: the phone connected (\(candidate.peer))")
    }

    private func closed(_ reason: String) {
        link = nil
        connected = false
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

    private func message(_ data: Data) {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              json["type"] as? String == "call",
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
        guard audio == nil, let link else { return }
        let audio = CallAudio { [weak link] frame in
            link?.send(type: CallConnection.audioFrame, payload: frame)
        }
        audio.muted = muted
        self.audio = audio
        Task { [weak self] in
            let allowed = await CallAudio.microphoneAllowed()
            guard let self, self.audio === audio else { return }
            if !allowed {
                self.call?.error = "Allow the microphone for Prism (System Settings → Privacy & Security → Microphone)."
            }
            do {
                try audio.start()
                self.log("calls: the call's audio is on this Mac")
            } catch {
                self.log("calls: audio failed: \(error.localizedDescription)")
                self.call?.error = "The Mac's audio failed: \(error.localizedDescription)"
                self.audio = nil
                self.moveToPhone()
            }
        }
    }

    private func stopAudio() {
        guard let audio else { return }
        self.audio = nil
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

    /// K = HMAC-SHA256(hotspot key, "prism-call/v1").
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

    /// docs/calls.md's vector: hotspot key 00..1f, nm 16 x a1, np 16 x b2.
    nonisolated static func selfTest() -> Bool {
        let k = callKey(SymmetricKey(data: Data(0..<32)))
        let nm = Data(repeating: 0xa1, count: 16), np = Data(repeating: 0xb2, count: 16)
        let results = [
            (k.withUnsafeBytes { Data($0) }, "770eb5e989c18d4fbd66609b94934b6e218999c822a3477313a8e726c356b864", "call key"),
            (phoneProof(k, macNonce: nm, phoneNonce: np), "16ceaf00645894bb0710f4daccb787f733b3b4c7b58433e4d46ec61bd67362d7", "call phone proof"),
            (macProof(k, phoneNonce: np, macNonce: nm), "967350dc16f4d14828d8320272b3a6e9446f932f9265667dbfa2750445d08321", "call Mac proof"),
        ].map { value, expected, name in
            let hex = value.map { String(format: "%02x", $0) }.joined()
            let passed = hex == expected
            print(passed ? "self-test passed: \(name) for the test vector" : "self-test FAILED: \(name) \(hex), expected \(expected)")
            return passed
        }
        return results.allSatisfy { $0 }
    }

    private func log(_ text: String) {
        Hotspot.log(text)
    }
}

// MARK: - The connection

/// One TCP connection from the phone: the handshake, then frames (type,
/// u16be length, payload). Its callbacks run on the connection's queue.
final class CallConnection: @unchecked Sendable {
    static let hello: UInt8 = 1
    static let auth: UInt8 = 2
    static let message: UInt8 = 3
    static let audioFrame: UInt8 = 4
    static let maxPayload = 16 * 1024

    var onReady: (() -> Void)?
    var onMessage: ((Data) -> Void)?
    var onAudio: ((Data) -> Void)?
    var onClose: ((String) -> Void)?

    let peer: String
    private let connection: NWConnection
    private let key: SymmetricKey
    private let queue: DispatchQueue
    private let macNonce: Data
    private var authenticated = false
    private var closed = false

    init(connection: NWConnection, key: SymmetricKey, queue: DispatchQueue) {
        self.connection = connection
        self.key = key
        self.queue = queue
        var nonce = Data(count: 16)
        _ = nonce.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        macNonce = nonce
        peer = "\(connection.endpoint)"
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.send(type: Self.hello, payload: self.macNonce)
                self.receive()
            case .failed(let error):
                self.close("failed: \(error.localizedDescription)")
            case .cancelled:
                self.close("cancelled")
            default:
                break
            }
        }
        connection.start(queue: queue)
        // A peer that doesn't prove itself in 5 s is dropped.
        queue.asyncAfter(deadline: .now() + 5) { [weak self] in
            guard let self, !self.authenticated, !self.closed else { return }
            self.cancel()
        }
    }

    func send(type: UInt8, payload: Data) {
        var frame = Data([type, UInt8(payload.count >> 8), UInt8(payload.count & 0xff)])
        frame.append(payload)
        connection.send(content: frame, completion: .contentProcessed { [weak self] error in
            if let error { self?.close("send: \(error.localizedDescription)") }
        })
    }

    func cancel() {
        connection.cancel()
    }

    private func close(_ reason: String) {
        guard !closed else { return }
        closed = true
        connection.cancel()
        onClose?(reason)
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 3, maximumLength: 3) { [weak self] header, _, done, error in
            guard let self else { return }
            guard let header, header.count == 3, error == nil else {
                self.close(error.map { "receive: \($0.localizedDescription)" } ?? (done ? "the phone closed it" : "short read"))
                return
            }
            let bytes = [UInt8](header)
            let type = bytes[0]
            let length = Int(bytes[1]) << 8 | Int(bytes[2])
            guard length <= Self.maxPayload else {
                self.close("frame too long: \(length)")
                return
            }
            if length == 0 {
                self.frame(type: type, payload: Data())
                return
            }
            self.connection.receive(minimumIncompleteLength: length, maximumLength: length) { payload, _, _, error in
                guard let payload, payload.count == length, error == nil else {
                    self.close(error.map { "receive: \($0.localizedDescription)" } ?? "short read")
                    return
                }
                self.frame(type: type, payload: payload)
            }
        }
    }

    private func frame(type: UInt8, payload: Data) {
        if !authenticated {
            guard type == Self.auth, payload.count == 48 else {
                close("no proof from the phone")
                return
            }
            let phoneNonce = payload.prefix(16)
            let proof = payload.suffix(32)
            let expected = PhoneCalls.phoneProof(key, macNonce: macNonce, phoneNonce: Data(phoneNonce))
            guard constantTimeEqual(Data(proof), expected) else {
                close("a wrong proof")
                return
            }
            authenticated = true
            send(type: Self.auth, payload: PhoneCalls.macProof(key, phoneNonce: Data(phoneNonce), macNonce: macNonce))
            onReady?()
        } else {
            switch type {
            case Self.message: onMessage?(payload)
            case Self.audioFrame: onAudio?(payload)
            default: break
            }
        }
        receive()
    }

    private func constantTimeEqual(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        return zip(a, b).reduce(0) { $0 | ($1.0 ^ $1.1) } == 0
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

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let send: (Data) -> Void
    private let lock = NSLock()
    private var queued = 0
    private var pending = Data()
    private var converter: AVAudioConverter?
    private var running = false
    private var _muted = false

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
        let input = engine.inputNode
        try input.setVoiceProcessingEnabled(true)
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: playFormat)
        let micFormat = input.outputFormat(forBus: 0)
        guard let converter = AVAudioConverter(from: micFormat, to: wire) else {
            throw NSError(domain: "PrismCalls", code: 1, userInfo: [NSLocalizedDescriptionKey: "no converter from \(micFormat)"])
        }
        converter.downmix = true
        self.converter = converter
        input.installTap(onBus: 0, bufferSize: 960, format: micFormat) { [weak self] buffer, _ in
            self?.captured(buffer)
        }
        engine.prepare()
        try engine.start()
        player.play()
        lock.withLock { running = true }
    }

    func stop() {
        lock.withLock { running = false }
        engine.inputNode.removeTap(onBus: 0)
        player.stop()
        engine.stop()
    }

    /// The microphone, converted to the wire's format and cut into frames.
    private func captured(_ buffer: AVAudioPCMBuffer) {
        guard let converter else { return }
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
        var frames: [Data] = []
        lock.withLock {
            guard running else { return }
            pending.append(bytes)
            while pending.count >= Self.frameBytes {
                let frame = pending.prefix(Self.frameBytes)
                pending.removeFirst(Self.frameBytes)
                frames.append(_muted ? Data(count: Self.frameBytes) : Data(frame))
            }
        }
        frames.forEach(send)
    }

    /// A frame from the phone, scheduled on the player; the oldest go when it falls behind.
    func play(_ frame: Data) {
        let ok: Bool = lock.withLock {
            guard running, queued < Self.maxQueued else { return false }
            queued += 1
            return true
        }
        guard ok else { return }
        let count = frame.count / 2
        guard let buffer = AVAudioPCMBuffer(pcmFormat: playFormat, frameCapacity: AVAudioFrameCount(count)),
              let channel = buffer.floatChannelData?[0] else { return }
        buffer.frameLength = AVAudioFrameCount(count)
        frame.withUnsafeBytes { raw in
            let samples = raw.bindMemory(to: Int16.self)
            for i in 0..<count { channel[i] = Float(Int16(littleEndian: samples[i])) / 32768 }
        }
        player.scheduleBuffer(buffer) { [weak self] in
            guard let self else { return }
            self.lock.withLock { self.queued -= 1 }
        }
    }
}
