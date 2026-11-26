// The approvals on this Mac: shared/'s ApprovalQueue (what waits for the
// owner, and the last decisions), Touch ID per approval, the Secure Enclave
// key that signs an approved one, expiry and the stored history. The dialog
// (ApprovalPanel.swift) and the popout's Approvals tab (MenuBar.swift) only
// show it.
//
// Nothing offers approvals yet: an integration hands the queue an Approval
// with its ApprovalChannel (shared/: Approvals.kt), which gets the answer.
// Approve signs Approvals.signedData(approval) with the device key, so the
// asker can check the answer against this Mac's public key.

import AppKit
import Foundation
@preconcurrency import LocalAuthentication
import PrismShared
import UserNotifications

/// Settings from fleet (traits/prism/services/prism.nix). Keys it no longer
/// reads (hosts, lockOnAway) are ignored.
struct Config: Decodable {
    /// This Mac's fleet host name: its device name in Prism (provisioning,
    /// approvals' signatures).
    let host: String
    /// The relay (ntfy) and the file with this Mac's relay token.
    let relayUrl: String
    let relayTokenFile: String
    /// The file with the key that asks the phone for its hotspot (64 hex
    /// characters, docs/hotspot.md; none: the Mac doesn't ask).
    var hotspotKeyFile: String?
    /// The phone's calls on this Mac (docs/calls.md), with the same key as
    /// the hotspot (hotspotKeyFile).
    var calls: Bool?
    /// This Mac's replica of Prism's database, for the popout's alerts and
    /// the approvals (none: not connected).
    let rocky: RockyConfig?

    static func load(_ path: String) throws -> Config {
        try JSONDecoder().decode(Config.self, from: Data(contentsOf: URL(fileURLWithPath: path)))
    }
}

/// An approval waiting for the owner, with the dialog's Touch ID state.
final class PendingApproval: Identifiable, ObservableObject {
    enum Phase: Equatable {
        /// Not on screen yet, or not armed.
        case queued
        /// On screen, the sensor waiting for a touch (`context`).
        case waitingForTouch
        /// Touch ID is unavailable or failed; `notice` says why.
        case needsAction
        /// Authenticated; the Secure Enclave is signing.
        case signing
    }

    @Published var approval: Approval
    var id: String { approval.id }
    var requestedAt: Date { Date(timeIntervalSince1970: TimeInterval(approval.requestedAt)) }
    var expiresAt: Date { Date(timeIntervalSince1970: TimeInterval(approval.expiresAt)) }

    @Published var phase = Phase.queued
    @Published var notice: String?
    /// The Touch ID evaluation the dialog embeds; a fresh one per attempt.
    @Published private(set) var context = LAContext()

    init(_ approval: Approval) {
        self.approval = approval
    }

    func renewContext() {
        context.invalidate()
        context = LAContext()
    }
}

@MainActor
final class Approver: ObservableObject {
    let config: Config
    let queue: ApprovalQueue
    /// The queue's pending approvals, oldest first.
    @Published private(set) var pending: [PendingApproval] = []
    /// The last decisions, newest first.
    @Published private(set) var history: [Decision] = []
    @Published private(set) var key: EnclaveKey?
    @Published private(set) var problems: [String] = []
    /// The approval the owner picked in the popout; the dialog shows the
    /// oldest otherwise.
    @Published private var chosenID: String?
    /// False for a preview snapshot, which can't take a touch: the dialog
    /// then shows its waiting state.
    var listens = true
    private var watching: [Task<Void, Never>] = []
    private var expiryTimer: Timer?

    /// Called whenever the dialog should show something else, to show or hide it.
    var onQueueChange: (() -> Void)?
    private var remoteIDs: Set<String> = []

    func syncRemote(_ approvals: [Approval], client: PrismClient) {
        let ids = Set(approvals.map(\.id))
        for id in remoteIDs.subtracting(ids) { queue.withdraw(id: id) }
        remoteIDs = ids
        for approval in approvals {
            _ = queue.offer(approval: approval, channel: ServerApprovalChannel(client: client, refresh: { [weak self] in
                guard let self else { return }
                do { self.syncRemote(try await client.approvals(), client: client) } catch {}
            }), now: Self.now())
        }
    }

    func choose(_ request: PendingApproval, decision: String) {
        guard request.phase != .signing else { return }
        request.renewContext()
        guard let chosen = queue.choose(id: request.id, decision: decision) else { return }
        request.approval = chosen
        request.phase = .queued
    }

    /// `history` nil: the stored history, and every decision is stored.
    /// Otherwise (preview) these decisions, and nothing is written.
    init(config: Config, history: [Decision]? = nil) {
        self.config = config
        if let history {
            queue = ApprovalQueue(history: history, onHistory: { _ in })
        } else {
            let text = (try? String(contentsOf: Support.historyFile, encoding: .utf8)) ?? ""
            // The old root-approval history.json doesn't decode: an empty history.
            queue = ApprovalQueue(history: Approvals.shared.decodeHistory(text: text), onHistory: Self.store)
        }
        self.history = queue.history.value as? [Decision] ?? []
    }

    var current: PendingApproval? { pending.first { $0.id == chosenID } ?? pending.first }

    func position(of approval: PendingApproval) -> Int {
        (pending.firstIndex { $0 === approval } ?? 0) + 1
    }

    /// The key alone, for `prism preview`.
    func loadKey() {
        key = try? EnclaveKey.loadOrCreate()
    }

    func start() {
        do {
            key = try EnclaveKey.loadOrCreate()
        } catch {
            problems.append("No signing key: \(error.localizedDescription)")
        }
        do {
            try Support.makePrivateDirectory(Support.directory)
        } catch {
            problems.append(error.localizedDescription)
        }
        watch()
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert]) { _, _ in }
    }

    /// Follows the queue: the pending list and the history.
    func watch() {
        guard watching.isEmpty else { return }
        let queue = queue
        watching = [
            Task { [weak self] in
                do {
                    for try await list in stream(queue.watchPending()) {
                        self?.update(pending: list as? [Approval] ?? [])
                    }
                } catch {}
            },
            Task { [weak self] in
                do {
                    for try await list in stream(queue.watchHistory()) {
                        self?.history = list as? [Decision] ?? []
                    }
                } catch {}
            },
        ]
    }

    private func update(pending list: [Approval]) {
        let ids = list.map(\.id)
        guard ids != pending.map(\.id) else { return }
        let old = Dictionary(uniqueKeysWithValues: pending.map { ($0.id, $0) })
        for gone in pending where !ids.contains(gone.id) { gone.context.invalidate() }
        pending = list.map { old[$0.id] ?? PendingApproval($0) }
        if let chosenID, !ids.contains(chosenID) { self.chosenID = nil }
        scheduleExpiry()
        onQueueChange?()
    }

    /// Brings `id` into the dialog (the popout's list or the dialog's stepper).
    func show(_ id: String) {
        guard pending.contains(where: { $0.id == id }) else { return }
        if let previous = current, previous.id != id, previous.phase != .signing {
            previous.renewContext()
            previous.phase = .queued
            previous.notice = nil
        }
        chosenID = id
        onQueueChange?()
    }

    /// Moves to the previous or next pending approval in the dialog.
    func step(_ delta: Int) {
        guard pending.count > 1, let current, let index = pending.firstIndex(where: { $0 === current }) else { return }
        let next = pending[(index + delta + pending.count) % pending.count]
        show(next.id)
    }

    private static func now() -> Int64 { Int64(Date().timeIntervalSince1970) }

    // MARK: - Deny and approve

    func deny(_ request: PendingApproval) {
        request.context.invalidate()
        queue.decide(id: request.id, outcome: .denied, signature: nil, now: Self.now())
    }

    /// Waits for Touch ID on the approval on screen: one touch approves. The
    /// dialog shows the sensor prompt itself (LAAuthenticationView on
    /// `request.context`), and the evaluated context then unlocks the key, so
    /// the signature needs no second prompt.
    func listenForTouch(_ request: PendingApproval) {
        guard listens, request.approval.decision.hasPrefix("accept"), key != nil, request === current, request.phase == .queued || request.phase == .needsAction
        else { return }
        if request.phase == .needsAction { request.renewContext() }
        let context = request.context
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else {
            request.phase = .needsAction
            request.notice = "Touch ID is not available\(error.map { ": \($0.localizedDescription)" } ?? ".")"
            return
        }
        request.phase = .waitingForTouch
        request.notice = nil
        let id = request.id
        context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: Self.reason(request.approval)) {
            success, error in
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    // Gone if it was decided, expired or withdrawn meanwhile.
                    guard let request = self.pending.first(where: { $0.id == id }), request.context === context
                    else { return }
                    if success {
                        self.sign(request, context: context)
                    } else {
                        request.phase = .needsAction
                        request.notice = Self.touchFailure(error)
                    }
                }
            }
        }
    }

    /// The fallback without the sensor: macOS's own prompt, which also takes
    /// the login password, while signing.
    func approveWithPassword(_ request: PendingApproval) {
        guard request === current, request.phase == .needsAction else { return }
        request.renewContext()
        request.context.localizedReason = Self.reason(request.approval)
        sign(request, context: request.context)
    }

    /// Signs Approvals.signedData(approval) in the Enclave and decides it
    /// APPROVED with the SSHSIG.
    private func sign(_ request: PendingApproval, context: LAContext) {
        guard let key else { return }
        request.phase = .signing
        request.notice = nil
        let data = Data(Approvals.shared.signedData(approval: request.approval))
        let id = request.id
        DispatchQueue.global(qos: .userInitiated).async {
            let result = Result { try key.sign(data, context: context) }
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self.signed(id, result, key: key) }
            }
        }
    }

    private func signed(_ id: String, _ result: Result<Data, Error>, key: EnclaveKey) {
        // Gone if it expired or was withdrawn meanwhile.
        guard let request = pending.first(where: { $0.id == id }) else { return }
        switch result {
        case .success(let der):
            let signature = Approvals.shared.signature(point: key.point.kotlin, derSignature: der.kotlin)
            queue.decide(id: id, outcome: .approved, signature: signature, now: Self.now())
        case .failure(let error):
            request.phase = .needsAction
            request.notice = Self.isCancel(error) ? "Cancelled." : "Signing failed: \(error.localizedDescription)"
        }
    }

    private static func reason(_ approval: Approval) -> String {
        "approve “\(visibleShort(approval.title, 60))” from \(visibleShort(approval.source, 40))"
    }

    private static func touchFailure(_ error: Error?) -> String {
        switch (error as? LAError)?.code {
        case .biometryLockout: "Touch ID is locked after too many attempts."
        case .authenticationFailed: "Fingerprint not recognised."
        case .userCancel, .systemCancel, .appCancel: "Touch ID was interrupted."
        default: "Touch ID failed\(error.map { ": \($0.localizedDescription)" } ?? ".")"
        }
    }

    private static func isCancel(_ error: Error) -> Bool {
        let code = (error as NSError).code
        return [LAError.userCancel, .appCancel, .systemCancel, .userFallback]
            .map(\.rawValue).contains(code)
    }

    // MARK: - Expiry

    /// queue.expire() every two seconds while something is pending.
    private func scheduleExpiry() {
        if pending.isEmpty {
            expiryTimer?.invalidate()
            expiryTimer = nil
        } else if expiryTimer == nil {
            expiryTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
                MainActor.assumeIsolated { self?.expireDue() }
            }
        }
    }

    private func expireDue() {
        let now = Self.now()
        let due = pending.filter { $0.approval.expiresAt != 0 && $0.approval.expiresAt <= now }
        guard !due.isEmpty else { return }
        queue.expire(now: now)
        for request in due { notifyExpired(request.approval) }
    }

    /// An approval the owner missed.
    private func notifyExpired(_ approval: Approval) {
        let content = UNMutableNotificationContent()
        content.title = "Expired: \(visibleShort(approval.title, 80))"
        content.body = "from \(visibleShort(approval.source, 80)), not answered in time"
        content.interruptionLevel = .passive
        content.threadIdentifier = "approvals"
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }

    // MARK: - History

    /// The queue's onHistory: the history as Approvals.encodeHistory, 0600.
    /// Called on the deciding thread.
    nonisolated private static func store(_ history: [Decision]) {
        let text = Approvals.shared.encodeHistory(history: history)
        try? Data(text.utf8).write(to: Support.historyFile, options: [.atomic])
        chmod(Support.historyFile.path, 0o600)
    }

    // MARK: - Preview

    /// Sample approvals and decisions for `prism preview`; answers go nowhere
    /// and nothing is stored.
    static func sample(config: Config) -> Approver {
        let now = Self.now()
        func decision(_ minutes: Int64, _ outcome: Outcome, _ title: String, _ source: String) -> Decision {
            Decision(time: now - minutes * 60, outcome: outcome, title: title, source: source)
        }
        let approver = Approver(config: config, history: [
            decision(4, .approved, "Restart the ntfy deployment", "prism"),
            decision(38, .denied, "Delete the PVC data-loki-0", "david-server"),
            decision(95, .expired, "Rotate the Gitea runner token", "fleet"),
            decision(60 * 5, .approved, "Deploy main to the cluster", "prism"),
            decision(60 * 26, .withdrawn, "Reboot david-tp", "david-tp"),
        ])
        let channel = DiscardingChannel()
        let approvals = [
            Approval(
                id: "sample-deploy", title: "Deploy main to the cluster", source: "prism",
                requestedAt: now - 40, expiresAt: now + 260,
                summary: "Argo CD syncs selfhosted-platform at 3f2c1ab. Four apps change; prism and grafana restart.",
                details: [
                    ApprovalDetail(label: "Repository", value: "david-cluster-1/selfhosted-platform", code: false),
                    ApprovalDetail(label: "Apps", value: "prism, grafana, loki, ntfy", code: false),
                    ApprovalDetail(label: "Command", value: "argocd app sync platform --revision 3f2c1ab --prune", code: true),
                    ApprovalDetail(label: "Changed", value: "4 files, +38 −12", code: false),
                ],
                thread: ThreadLink(environmentId: "sample", threadId: "thread-roll-out-2-1", title: "Roll out Prism 2.1 to the cluster"), options: [], decision: "accept"),
            Approval(
                id: "sample-pvc", title: "Delete the PVC data-loki-0", source: "david-server",
                requestedAt: now - 15, expiresAt: now + 110,
                summary: "Loki's volume is corrupt after the node crash; the StatefulSet recreates it empty.",
                details: [ApprovalDetail(label: "Namespace", value: "monitoring", code: false)],
                thread: nil, options: [], decision: "accept"),
        ]
        for approval in approvals { _ = approver.queue.offer(approval: approval, channel: channel, now: now) }
        return approver
    }

    /// Where the preview's answers go: nowhere.
    private final class DiscardingChannel: NSObject, ApprovalChannel {
        func answer(approval _: Approval, outcome _: Outcome, signature _: String?, completionHandler: @escaping (Error?) -> Void) {
            completionHandler(nil)
        }
    }
}
