// Entry point. Without a subcommand (or with --config) it runs the menu-bar
// app; the subcommands are small tools on the same key and code:
//
//   pubkey                  print this Mac's device key (authorized_keys form)
//   self-test               prove the SSHSIG signatures (shared/'s SshSig with a
//                           P-256 key, as the Secure Enclave signs) against
//                           ssh-keygen -Y verify, the shared Kotlin module
//                           (Shared.swift), and the hotspot's and calls'
//                           vectors (Hotspot.swift, PhoneCalls.swift)
//   sign -n NAMESPACE FILE  sign FILE (or stdin, "-") after Touch ID, like
//                           ssh-keygen -Y sign: FILE.sig, or stdout for stdin
//   preview                 the popout in a window, for screenshots (Preview.swift)
//   hotspot-uuid [--release] [KEYHEX WINDOW]
//                           print the hotspot request's (or release's) service
//                           UUID (Hotspot.swift), by default for the test vector
//   wifi-status             what WiFiSwitch.swift sees: the Wi-Fi interface,
//                           whether it is on the phone's hotspot, its network,
//                           the captive probe, the phone's hotspot and the
//                           reliable networks

import CryptoKit
import Foundation
@preconcurrency import LocalAuthentication
import PrismShared

signal(SIGPIPE, SIG_IGN)

let arguments = Array(CommandLine.arguments.dropFirst())

func fail(_ text: String) -> Never {
    FileHandle.standardError.write(Data("prism: \(text)\n".utf8))
    exit(1)
}

func usage() -> Never {
    FileHandle.standardError.write(Data("""
        usage: prism [--config FILE]
               prism pubkey
               prism self-test
               prism sign -n NAMESPACE [FILE|-]
               prism hotspot-uuid [--release] [KEYHEX WINDOW]
               prism wifi-status
               prism preview [--dark|--light] [--approval|--approvals|--machines|--profiles|--wifi] [--expand N] [--live --config FILE] [--snapshot PNG]

        """.utf8))
    exit(2)
}

switch arguments.first {
case "pubkey":
    do {
        print(try EnclaveKey.loadOrCreate().publicKeyLine)
    } catch {
        fail(error.localizedDescription)
    }

case "self-test":
    let signatures = SelfTest.run()
    let shared = SharedSelfTest.run()
    let hotspot = Hotspot.selfTest()
    let wifi = WiFiPolicy.selfTest()
    let calls = PhoneCalls.selfTest()
    exit(signatures && shared && hotspot && wifi && calls ? 0 : 1)

case "hotspot-uuid":
    var rest = Array(arguments.dropFirst())
    let release = rest.first == "--release"
    if release { rest.removeFirst() }
    guard rest.isEmpty || rest.count == 2 else { usage() }
    let key = rest.isEmpty ? Hotspot.vectorKey : Hotspot.hex(rest[0]).flatMap { $0.count == 32 ? SymmetricKey(data: $0) : nil }
    let window = rest.isEmpty ? Hotspot.vectorWindow : UInt64(rest[1])
    guard let key, let window else { fail("KEYHEX is 64 hex characters, WINDOW floor(unix seconds / 30)") }
    let label = release ? Hotspot.releaseLabel : Hotspot.requestLabel
    let uuid = Hotspot.uuid(key: key, window: window, label: label)
    print("label  \(label)")
    print("window \(window)")
    print("tag    \(uuid.dropFirst(4).map { String(format: "%02x", $0) }.joined())")
    print("uuid   \(UUID(uuid: uuid.withUnsafeBytes { $0.loadUnaligned(as: uuid_t.self) }).uuidString)")

case "wifi-status":
    WiFiSwitch.printStatus()

case "sign":
    var rest = arguments.dropFirst()
    guard rest.first == "-n", rest.count >= 2 else { usage() }
    let namespace = rest.dropFirst().first!
    rest = rest.dropFirst(2)
    let file = rest.first ?? "-"
    guard rest.count <= 1 else { usage() }
    do {
        let message = file == "-" ? FileHandle.standardInput.readDataToEndOfFile() : try Data(contentsOf: URL(fileURLWithPath: file))
        let context = LAContext()
        context.localizedReason = "sign \(file == "-" ? "standard input" : URL(fileURLWithPath: file).lastPathComponent) for \(namespace)"
        let signature = try EnclaveKey.loadOrCreate().sign(message: message, namespace: namespace, context: context)
        if file == "-" {
            FileHandle.standardOutput.write(Data(signature.utf8))
        } else {
            try Data(signature.utf8).write(to: URL(fileURLWithPath: file + ".sig"))
            FileHandle.standardError.write(Data("Write signature to \(file).sig\n".utf8))
        }
    } catch {
        fail(error.localizedDescription)
    }

case nil, "--config":
    let configPath = arguments.count == 2 ? arguments[1] : nil
    guard arguments.isEmpty || configPath != nil else { usage() }
    guard let configPath else { fail("no --config given") }
    let config: Config
    do {
        config = try Config.load(configPath)
    } catch {
        fail("cannot read the configuration: \(error.localizedDescription)")
    }
    MainActor.assumeIsolated {
        AppDelegate.approver = Approver(config: config)
        AppDelegate.store = PrismStore(
            config: config.rocky, device: config.host, relayURL: config.relayUrl, relayTokenFile: config.relayTokenFile)
        AppDelegate.hotspot = Hotspot(keyFile: config.hotspotKeyFile)
        AppDelegate.calls = PhoneCalls(keyFile: config.calls == true ? config.hotspotKeyFile : nil)
    }
    PrismApp.main()

case "preview":
    MainActor.assumeIsolated { Preview.run(Array(arguments.dropFirst())) }

default:
    usage()
}

/// Signs with throwaway software P-256 keys exactly as the app signs with the
/// Secure Enclave key (shared/'s signed data in, a DER signature out, armoured
/// by the shared code) and has ssh-keygen verify the results, plus a tampered
/// message that must fail. Approvals go the app's way: through an
/// ApprovalQueue, whose channel gets the signature ssh-keygen checks; the
/// enrollment messages are Enrollment.message's.
enum SelfTest {
    static func run() -> Bool {
        let work = FileManager.default.temporaryDirectory.appendingPathComponent("prism-self-test-\(getpid())")
        try? FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: work) }

        var passed = true
        // Several keys and messages, so r and s with and without the mpint
        // sign byte both occur.
        for round in 1...16 {
            let key = P256.Signing.PrivateKey()
            let point = key.publicKey.x963Representation.kotlin
            let kind = round % 2 == 0 ? "approval" : "enrollment"
            do {
                let (message, namespace, signature) = round % 2 == 0
                    ? try approve(round: round, key: key)
                    : try enroll(round: round, key: key)
                let signers = work.appendingPathComponent("allowed_signers")
                let sigFile = work.appendingPathComponent("message.sig")
                let messageFile = work.appendingPathComponent("message")
                let line = SshSig.shared.publicKeyLine(point: point)
                try Data("self-test namespaces=\"\(namespace)\" \(line)\n".utf8).write(to: signers)
                try Data(signature.utf8).write(to: sigFile)
                try message.write(to: messageFile)

                let good = verify(signers: signers, signature: sigFile, message: messageFile, namespace: namespace)
                try (message + Data("x".utf8)).write(to: messageFile)
                let tampered = verify(signers: signers, signature: sigFile, message: messageFile, namespace: namespace)
                if !good || tampered {
                    print("round \(round) (\(kind)): FAIL (verify: \(good), tampered verified: \(tampered))")
                    print(signature)
                    passed = false
                }
            } catch {
                print("round \(round) (\(kind)): FAIL: \(error.localizedDescription)")
                passed = false
            }
        }
        print(passed
              ? "self-test passed: 8 approvals and 8 enrollment messages verified by ssh-keygen, tampered messages rejected"
              : "self-test FAILED")
        return passed
    }

    /// An approval approved as the dialog does: the key signs
    /// Approvals.signedData, the queue decides with the SSHSIG, and the
    /// channel's signature comes back for ssh-keygen.
    private static func approve(round: Int, key: P256.Signing.PrivateKey) throws -> (Data, String, String) {
        let now = Int64(Date().timeIntervalSince1970)
        let approval = Approval(
            id: "self-test-\(round)-\(UUID().uuidString)", title: "Self-test \(round): ü \"quoted\"", source: "self-test",
            requestedAt: now, expiresAt: now + 300, summary: "A sample approval.",
            details: [ApprovalDetail(label: "Command", value: "true \(round)", code: true)],
            thread: round % 4 == 0 ? ThreadLink(environmentId: "env", threadId: "thread-\(round)", title: nil) : nil, options: [], decision: "accept")
        let channel = CapturingChannel()
        let queue = ApprovalQueue(history: [], onHistory: { _ in })
        guard queue.offer(approval: approval, channel: channel, now: now) else { throw SharedSelfTest.Failure("offer refused") }
        let der = try key.signature(for: Data(Approvals.shared.signedData(approval: approval))).derRepresentation
        let signature = Approvals.shared.signature(point: key.publicKey.x963Representation.kotlin, derSignature: der.kotlin)
        guard queue.decide(id: approval.id, outcome: .approved, signature: signature, now: now) else {
            throw SharedSelfTest.Failure("decide refused")
        }
        guard let answer = channel.wait(), answer.outcome == .approved, let sent = answer.signature else {
            throw SharedSelfTest.Failure("the channel got no approval")
        }
        guard (queue.history.value as? [Decision])?.first?.outcome == .approved else {
            throw SharedSelfTest.Failure("no decision in the history")
        }
        return (Data(Approvals.shared.message(approval: approval)), Approvals.shared.NAMESPACE, sent)
    }

    /// An enrollment message signed as PrismStore.enroll does (EnclaveKey.sign).
    private static func enroll(round: Int, key: P256.Signing.PrivateKey) throws -> (Data, String, String) {
        let message = Enrollment.shared.message(device: "self-test-\(round)", now: Int64(Date().timeIntervalSince1970))
        let namespace = Enrollment.shared.NAMESPACE
        let der = try key.signature(for: Data(SshSig.shared.signedData(message: message, namespace: namespace))).derRepresentation
        let signature = SshSig.shared.armor(namespace: namespace, point: key.publicKey.x963Representation.kotlin, derSignature: der.kotlin)
        return (Data(message), namespace, signature)
    }

    /// Keeps the answer the queue sends, for `wait()`.
    private final class CapturingChannel: NSObject, ApprovalChannel, @unchecked Sendable {
        private let done = DispatchSemaphore(value: 0)
        private var answer: (outcome: Outcome, signature: String?)?

        func answer(approval _: Approval, outcome: Outcome, signature: String?, completionHandler: @escaping (Error?) -> Void) {
            answer = (outcome, signature)
            done.signal()
            completionHandler(nil)
        }

        func wait() -> (outcome: Outcome, signature: String?)? {
            done.wait(timeout: .now() + 5) == .success ? answer : nil
        }
    }

    private static func verify(signers: URL, signature: URL, message: URL, namespace: String) -> Bool {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/ssh-keygen")
        process.arguments = [
            "-Y", "verify", "-f", signers.path, "-I", "self-test", "-n", namespace, "-s", signature.path,
        ]
        process.standardInput = try? FileHandle(forReadingFrom: message)
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        do { try process.run() } catch { return false }
        process.waitUntilExit()
        return process.terminationStatus == 0
    }
}
