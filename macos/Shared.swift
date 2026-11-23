// The code shared with the Android app (shared/, Kotlin Multiplatform), as
// PrismShared.framework, which install.sh links into the executable.
//
// Calling it from Swift:
// - A Kotlin suspend function is an `async throws` function here (Kotlin/Native
//   exports it with a completion handler, which Swift imports as async). Call
//   it from any thread or actor. A Kotlin exception comes as an NSError; the
//   Kotlin side doesn't see Swift's task cancellation, so long-running work
//   comes as a Watchable instead.
// - A Kotlin Flow comes as a Watchable; `stream(_:)` makes it an
//   AsyncThrowingStream, and ending the loop (break, or the task cancelled)
//   cancels the Kotlin coroutine.

import Foundation
import Network
import PrismShared

/// A Kotlin Flow (shared/src/commonMain/.../Watch.kt) as an async sequence.
func stream<T: AnyObject>(_ watchable: Watchable<T>) -> AsyncThrowingStream<T, Error> {
    AsyncThrowingStream { continuation in
        let subscription = watchable.watch(
            onEach: { continuation.yield($0) },
            onComplete: { error in
                if let error {
                    continuation.finish(throwing: error.asError())
                } else {
                    continuation.finish()
                }
            })
        continuation.onTermination = { _ in subscription.cancel() }
    }
}

/// Bytes in and out of Kotlin (a ByteArray is a KotlinByteArray here).
extension Data {
    init(_ bytes: KotlinByteArray) {
        self.init(count: Int(bytes.size))
        for i in 0..<Int(bytes.size) { self[i] = UInt8(bitPattern: bytes.get(index: Int32(i))) }
    }

    var kotlin: KotlinByteArray {
        let out = KotlinByteArray(size: Int32(count))
        for (i, byte) in enumerated() { out.set(index: Int32(i), value: Int8(bitPattern: byte)) }
        return out
    }
}

/// The shared module's part of `prism self-test`: its version, a JSON round
/// trip on a Kotlin coroutine, a Flow (to its end, and cancelled), a Ktor
/// request (Darwin engine) to a server on loopback, and a Rocky replica in a
/// temporary directory.
enum SharedSelfTest {
    static func run() -> Bool {
        let outcome = Outcome()
        Task.detached {
            do {
                outcome.set(.success(try await check()))
            } catch {
                outcome.set(.failure(error))
            }
        }
        // The main thread stays free (run loop) for anything Kotlin sends there.
        let deadline = Date().addingTimeInterval(30)
        while outcome.get() == nil, Date() < deadline {
            RunLoop.main.run(until: Date().addingTimeInterval(0.02))
        }
        switch outcome.get() {
        case .success(let line):
            print(line)
            return true
        case .failure(let error):
            print("shared self-test FAILED: \(error.localizedDescription)")
            return false
        case nil:
            print("shared self-test FAILED: no result in 30 s")
            return false
        }
    }

    struct Failure: LocalizedError {
        let errorDescription: String?
        init(_ text: String) { errorDescription = text }
    }

    private static func check() async throws -> String {
        let shared = PrismShared.shared
        let version = shared.version()
        guard version.hasPrefix("prism-shared ") else { throw Failure("version: \(version)") }

        let probe = Probe(name: "swift", count: 7, tags: ["a", "ü"], note: nil)
        let back = try await shared.roundTrip(probe: probe)
        guard back.isEqual(probe) else { throw Failure("round trip: \(back)") }

        var counts: [Int32] = []
        for try await tick in stream(WatchKt.countdown(count: 3, intervalMillis: 1)) {
            counts.append(tick.count)
        }
        guard counts == [3, 2, 1] else { throw Failure("countdown: \(counts)") }
        // A long Flow, left after its first value: its coroutine is cancelled.
        for try await tick in stream(WatchKt.countdown(count: 1000, intervalMillis: 1000)) {
            guard tick.count == 1000 else { throw Failure("countdown: \(tick.count)") }
            break
        }

        let body = Data(#"{"name":"served","count":42,"tags":["ktor"],"note":"darwin","extra":true}"#.utf8)
        let server = try LoopbackServer(body: body)
        defer { server.stop() }
        let port = try server.start()
        let fetched = try await shared.fetchProbe(url: "http://127.0.0.1:\(port)/probe")
        let served = Probe(name: "served", count: 42, tags: ["ktor"], note: "darwin")
        guard fetched.isEqual(served) else { throw Failure("fetched: \(fetched)") }

        // The Rocky replica (rocky-prism-ffi, linked into the framework): a
        // profile written in a fresh database reads back.
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("prism-self-test-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let replica = try await PrismReplica.companion.open(directory: directory.path, dial: false, clusterMachine: "")
        let entry = PlacementEntry(
            instanceId: "claudeAgent", model: "claude-1/claude-opus-5-5", label: "", driver: "", provider: "",
            reasoning: "high", enabled: true, sessionShareOfWeekly: nil, state: "", reason: "", runningThreads: 0)
        let saved = try await replica.savePlacementProfile(id: "swift", label: "Swift", entries: [entry])
        try await replica.close()
        guard let profile = saved.profiles.first(where: { $0.id == "swift" }),
              profile.label == "Swift", profile.entries.map(\.model) == ["claude-1/claude-opus-5-5"]
        else { throw Failure("replica: \(saved.profiles)") }

        return "shared self-test passed: \(version), JSON round trip, Flow, Ktor GET on loopback, Rocky replica"
    }

    private final class Outcome: @unchecked Sendable {
        private let lock = NSLock()
        private var value: Result<String, Error>?
        func set(_ result: Result<String, Error>) { lock.withLock { value = result } }
        func get() -> Result<String, Error>? { lock.withLock { value } }
    }
}

/// An HTTP server on 127.0.0.1 that answers every request with one JSON body.
private final class LoopbackServer: @unchecked Sendable {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "prism.self-test.server")

    init(body: Data) throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
        let queue = queue
        listener.newConnectionHandler = { connection in
            connection.start(queue: queue)
            connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { _, _, _, _ in
                let head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \(body.count)\r\nConnection: close\r\n\r\n"
                connection.send(content: Data(head.utf8) + body, completion: .contentProcessed { _ in connection.cancel() })
            }
        }
    }

    /// Starts listening; the port.
    func start() throws -> UInt16 {
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready, .failed, .cancelled: ready.signal()
            default: break
            }
        }
        listener.start(queue: queue)
        guard ready.wait(timeout: .now() + 5) == .success, let port = listener.port?.rawValue, port != 0 else {
            throw SharedSelfTest.Failure("the loopback server did not start")
        }
        return port
    }

    func stop() { listener.cancel() }
}
