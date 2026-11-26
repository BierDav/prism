// Prism's side of the app: the cluster's and the machines' open alerts, the
// agents each one started, and the owner's quick actions on them (fix, do),
// and the choices for new agents: the model, its reasoning level and the
// machine "Do…" works on. Its contract is
// docs/prism-api.md in the prism repository ("the contract" below). The
// popout (AlertsView.swift) only shows what this store holds.
//
// The API and the push are shared/'s, as for the phone: PrismClient makes
// every call and decodes the answers, RelayStream reads the push. The calls
// go to this Mac's own replica of Prism's Rocky database (PrismReplica,
// rocky-prism-ffi), not to a server: reads come from the replica, and each
// action is written there for the worker leading the cluster, which runs it
// and syncs back the outcome. The replica syncs with the workers on its own
// (wss://prism.quickme.at/rocky/<pod>) while the app runs.
//
// - Enrollment: fleet writes this Mac's Rocky provisioning to a file only the
//   owner can read (`rocky.provisioningFile`, minted with `prism-worker
//   provision <host>`) and the workers' contacts (`rocky.peers`). No token,
//   no sign-in. The workers check approval signatures against this Mac's
//   Secure Enclave key in their config (devices.<host>.key).
// - Push: ntfy's prism-events on the relay, read with this Mac's relay token.
//   A push only triggers a refresh and a notification; so does a change the
//   replica syncs. The replica is the source of truth.

import AppKit
import PrismShared
import UserNotifications

// The server's objects are shared/'s (Api.kt): Alert, Agent, Machine,
// HostMetrics, ModelSettings, ModelChoice, Reasoning, Severity. The views use
// them as they are; what follows only says them in Swift's types (Date, URL,
// Double?) and makes them Identifiable for SwiftUI's lists.

/// shared/'s Alert. SwiftUI has an `Alert` of its own, so files that import
/// SwiftUI name the server's by this alias.
typealias PrismAlert = Alert

/// Now in the server's seconds since 1970.
func epochNow() -> Int64 { Int64(Date().timeIntervalSince1970) }

extension Date {
    init(epoch: Int64) { self.init(timeIntervalSince1970: TimeInterval(epoch)) }
}

extension Alert: @retroactive Identifiable {
    /// When the episode started (Alert.since).
    var sinceDate: Date { Date(epoch: since(now: epochNow())) }
}

extension Agent: @retroactive Identifiable {
    /// The thread in T3 Code (Agent.link), nil without one.
    var url: URL? { link.flatMap(URL.init(string:)) }
    var updated: Date { Date(epoch: lastChange) }
}

extension Machine: @retroactive Identifiable {
    public var id: String { name }
}

extension HostMetrics: @retroactive Identifiable {
    public var id: String { name }
    var cpuShare: Double? { cpu?.doubleValue }
    var loads: [Double] { load.map(\.doubleValue) }
    var lastSeenDate: Date { Date(epoch: lastSeen) }
    var bootDate: Date? { bootTime > 0 ? Date(epoch: bootTime) : nil }
    /// The last two hours (nil where nothing was reported); Kotlin's nulls
    /// arrive as NSNull.
    var cpuHistory: [Double?] { (history?.cpu ?? []).map { ($0 as? NSNumber)?.doubleValue } }
    var memoryHistory: [Double?] { (history?.memory ?? []).map { ($0 as? NSNumber)?.doubleValue } }
}

extension HostMetrics.Usage {
    var fraction: Double? { share?.doubleValue }
}

extension Error {
    /// The PrismError a PrismClient call threw (Kotlin's exception rides in
    /// the NSError).
    var prism: PrismError? { (self as NSError).userInfo["KotlinException"] as? PrismError }
    /// For the popout: the server's text, safe to show.
    var text: String { visible(prism?.message ?? localizedDescription) }
}

/// What the owner asks the server to do.
enum PrismAction: Equatable {
    /// Start an agent on the alert, with an instruction or without.
    case fix(String?)
    /// Start an agent for any task, on a machine or the cluster.
    case task(String, machine: String?)
}

/// This Mac's replica of Prism's Rocky database, from fleet.
struct RockyConfig: Decodable {
    /// The file with this Mac's provisioning (`prism-device-v1:…`), readable
    /// by the owner only. It enrolls the replica on its first open.
    let provisioningFile: String
    /// The workers' Rocky contacts (`prism-worker peer`).
    let peers: [String]
    /// The T3 host whose models count as the cluster's (placement, models);
    /// unset: the one the workers name.
    let clusterMachine: String?

    /// Where the replica's database lives.
    static var directory: URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/Application Support/Prism/rocky", isDirectory: true)
    }
}

@MainActor
final class PrismStore: ObservableObject {
    enum Connection: Equatable {
        case notConfigured
        case connecting
        case connected
        case failed(String)
    }

    enum ServerHealth: Equatable {
        case checking
        case reachable
        case unavailable
    }

    @Published private(set) var serverHealth = ServerHealth.checking
    @Published private(set) var alerts: [PrismAlert] = []
    /// Active agents that belong to no alert (started with "do").
    @Published private(set) var tasks: [Agent] = []
    @Published private(set) var connection = Connection.notConfigured
    @Published private(set) var updated: Date?
    /// Alerts with an action on the way to the server (0: the do field).
    @Published private(set) var busy: Set<Int64> = []
    /// The last action that failed, for the popout.
    @Published var actionError: String?
    @Published private(set) var pushConnected = false
    /// The model and reasoning level for new agents; nil until loaded.
    @Published private(set) var modelSettings: ModelSettings?
    /// The machines "Do…" can work on, from the server.
    @Published private(set) var machines: [Machine] = []
    @Published private(set) var loadingMachines = false
    /// A model or reasoning change on the way to the server.
    @Published private(set) var savingModel = false
    /// Where "Do…" starts its agent: a machine's name, or nil for the cluster.
    @Published var doTarget: String?
    /// The hosts' performance, for the Machines tab; nil until loaded.
    @Published private(set) var hostMetrics: [HostMetrics]?
    /// The server's time of the metrics.
    @Published private(set) var metricsTime: Date?
    @Published private(set) var loadingMetrics = false
    @Published private(set) var metricsError: String?

    @Published private(set) var placement: Placement?
    @Published private(set) var savingProfiles = false
    @Published private(set) var profilesError: String?

    let config: RockyConfig?
    private let device: String
    private let relayURL: String
    private let relayTokenFile: String
    private var replica: PrismReplica?
    private var client: PrismClient?
    private var relay: RelayStream?
    private var started = false
    private var refreshing = false
    private var refreshAgain = false
    private var pollTimer: Timer?
    private var healthTimer: Timer?
    private var checkingHealth = false
    private var healthFailures = 0
    private static let healthURL = URL(string: "https://prism.quickme.at/v1/health")!
    private static let outageNotification = "prism-server-unreachable"
    private var modelLoaded: Date?
    private var machinesLoaded: Date?

    init(config: RockyConfig?, device: String = "", relayURL: String = "", relayTokenFile: String = "") {
        self.config = config
        self.device = device
        self.relayURL = relayURL
        self.relayTokenFile = relayTokenFile
    }

    var openCritical: Int { alerts.filter { $0.level == .critical }.count }
    var connected: Bool { connection == .connected }

    /// Alerts by category in the contract's order, within one the most
    /// severe and then the newest first.
    var grouped: [AlertGroup] { ApiKt.groupAlerts(alerts: alerts, now: epochNow()) }

    /// `push: false` (preview) reads no push and leaves its catch-up point alone.
    func start(push usePush: Bool = true) {
        guard !started, let config else { return }
        started = true
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        if usePush {
            startPush()
            checkServerHealth()
            healthTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
                MainActor.assumeIsolated { self?.checkServerHealth() }
            }
        }
        // A backstop for lost pushes and syncs; the list is local.
        pollTimer = Timer.scheduledTimer(withTimeInterval: 300, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        }
        connection = .connecting
        Task { await open(config) }
    }

    // MARK: - The replica

    /// Opens the replica (enrolling it from the provisioning on the first
    /// open), then refreshes whenever a sync or a write changes it.
    private func open(_ config: RockyConfig) async {
        do {
            guard let provisioning = (try? String(contentsOfFile: config.provisioningFile, encoding: .utf8))?
                .trimmingCharacters(in: .whitespacesAndNewlines), !provisioning.isEmpty
            else {
                connection = .failed("No Rocky provisioning in \(config.provisioningFile).")
                return
            }
            let directory = RockyConfig.directory
            try FileManager.default.createDirectory(
                at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            let replica = try await PrismReplica.companion.openProvisioned(
                directory: directory.path, provisioning: provisioning, peers: config.peers,
                clusterMachine: config.clusterMachine ?? "")
            self.replica = replica
            client = replica.client(device: device, waitSeconds: 20, prismUrl: "https://prism.quickme.at")
            log("replica open, syncing with \(config.peers.count) worker(s)")
            refresh()
            Task { await watchPlacement(replica) }
            for try await _ in stream(replica.updates(tables: PrismStore.shownTables)) {
                refresh()
                if modelLoaded != nil { loadModel() }
            }
        } catch {
            log("replica: \(error.text)")
            connection = .failed(error.text)
        }
    }

    private func watchPlacement(_ replica: PrismReplica) async {
        do {
            for try await view in stream(replica.placementUpdates()) {
                if !savingProfiles { placement = view }
            }
        } catch {
            profilesError = error.text
        }
    }

    func changeProfiles(_ change: (PrismReplica) async throws -> Placement) async -> Bool {
        guard let replica, !savingProfiles else { return false }
        savingProfiles = true
        profilesError = nil
        defer { savingProfiles = false }
        do {
            placement = try await change(replica)
            return true
        } catch {
            profilesError = error.text
            if let current = try? await replica.placement() { placement = current }
            return false
        }
    }

    /// The replica's tables the popout shows (rocky-prism's schema).
    private static let shownTables = [
        "alerts", "alert_actions", "agents", "approvals", "machines", "settings", "hosts",
    ]

    private func log(_ text: String) {
        FileHandle.standardError.write(Data("\(Date()) prism: \(text)\n".utf8))
    }

    /// Probe the public API independently of the local replica and relay.
    /// Two failed probes keep a brief network change from posting an alert.
    func checkServerHealth() {
        guard started, !checkingHealth else { return }
        checkingHealth = true
        Task {
            defer { checkingHealth = false }
            let request = URLRequest(url: Self.healthURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 5)
            struct Health: Decodable { let ok: Bool }
            if let (data, response) = try? await URLSession.shared.data(for: request),
               (response as? HTTPURLResponse)?.statusCode == 200,
               (try? JSONDecoder().decode(Health.self, from: data))?.ok == true {
                healthFailures = 0
                if serverHealth == .unavailable {
                    UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [Self.outageNotification])
                }
                serverHealth = .reachable
            } else if serverHealth != .unavailable {
                healthFailures += 1
                guard healthFailures >= 2 else { return }
                serverHealth = .unavailable
                let content = UNMutableNotificationContent()
                content.title = "Prism server unreachable"
                content.body = "This Mac could not reach prism.quickme.at. New alerts may be late; this Mac sends fixes, agents and approvals to T3 directly."
                content.threadIdentifier = "prism"
                content.sound = .default
                try? await UNUserNotificationCenter.current().add(
                    UNNotificationRequest(identifier: Self.outageNotification, content: content, trigger: nil))
            }
        }
    }

    // MARK: - The list

    /// Loads the open alerts and the active agents. Coalesces: a refresh
    /// asked for while one runs runs once more after it.
    func refresh() {
        guard let client else { return }
        if refreshing {
            refreshAgain = true
            return
        }
        refreshing = true
        Task {
            defer {
                refreshing = false
                if refreshAgain {
                    refreshAgain = false
                    refresh()
                }
            }
            do {
                async let alerts = client.alerts(state: "open")
                async let agents = client.agents(state: "active")
                let (freshAlerts, freshAgents) = try await (alerts, agents)
                self.alerts = freshAlerts
                tasks = freshAgents.filter { $0.alertId == 0 }
                if let approvals = try? await client.approvals() {
                    AppDelegate.approver?.syncRemote(approvals, client: client)
                }
                updated = Date()
                let first = connection != .connected
                connection = .connected
                if first { loadChoices() }
            } catch {
                connection = .failed(error.text)
            }
        }
    }

    // MARK: - Actions

    func perform(_ action: PrismAction, on alert: PrismAlert?) {
        guard let client else { return }
        let busyID = alert?.id ?? 0
        guard !busy.contains(busyID) else { return }
        busy.insert(busyID)
        actionError = nil
        // One ID per tap: the server acts once however often it is retried.
        let requestID = PrismClient.companion.requestId()
        Task {
            defer { busy.remove(busyID) }
            do {
                switch (action, alert) {
                case (.fix(let instruction), let alert?):
                    _ = try await client.fix(id: alert.id, instruction: instruction, requestId: requestID)
                case (.task(let text, let machine), _):
                    _ = try await client.startAgent(task: "do", target: machine, request: text, requestId: requestID)
                default:
                    return
                }
                refresh()
            } catch {
                actionError = error.text
                refresh()
            }
        }
    }

    // MARK: - Choices for new agents

    /// Loads the model settings and the machines when the popout opens.
    /// The machine list asks every machine's T3 (up to 10 s), so it is never
    /// polled: at most every two minutes unless `force`.
    func loadChoices(force: Bool = false) {
        guard client != nil, connected else { return }
        if force || modelLoaded.map({ Date().timeIntervalSince($0) > 60 }) ?? true { loadModel() }
        if force || machinesLoaded.map({ Date().timeIntervalSince($0) > 120 }) ?? true { loadMachines() }
    }

    private func loadModel() {
        guard let client else { return }
        modelLoaded = Date()
        Task {
            do {
                modelSettings = try await client.modelSettings()
            } catch {
                modelLoaded = nil
                log("model settings: \(error.text)")
            }
        }
    }

    func loadMachines() {
        guard let client, !loadingMachines else { return }
        loadingMachines = true
        machinesLoaded = Date()
        Task {
            defer { loadingMachines = false }
            do {
                machines = try await client.machines().machines
                // A machine that left the config is no target any more.
                if let target = doTarget, !machines.contains(where: { $0.name == target }) { doTarget = nil }
            } catch {
                machinesLoaded = nil
                actionError = "Machines: \(error.text)"
            }
        }
    }

    /// GET /machines/metrics, while the Machines tab is open (the server
    /// shares its answer for 15 seconds).
    func loadHostMetrics() {
        guard let client, connected, !loadingMetrics else { return }
        loadingMetrics = true
        Task {
            defer { loadingMetrics = false }
            do {
                let list = try await client.hostMetrics()
                hostMetrics = list.hosts
                metricsTime = list.time > 0 ? Date(epoch: list.time) : Date()
                metricsError = nil
            } catch {
                metricsError = error.text
            }
        }
    }

    /// PUT /settings/model: the model future agents get.
    func select(model: ModelChoice) {
        guard model != modelSettings?.model else { return }
        saveModel { try await $0.setModel(model: model) }
    }

    /// PUT /settings/reasoning: a level, or "default".
    func select(reasoning level: String) {
        saveModel { try await $0.setReasoning(level: level) }
    }

    private func saveModel(_ change: @escaping (PrismClient) async throws -> ModelSettings) {
        guard let client, !savingModel else { return }
        savingModel = true
        actionError = nil
        Task {
            defer { savingModel = false }
            do {
                modelSettings = try await change(client)
                modelLoaded = Date()
            } catch {
                actionError = error.text
            }
        }
    }

    // MARK: - Push

    /// prism-events on the relay (shared/'s RelayStream): a message refreshes
    /// the list and may notify; the stream resumes after the last message
    /// seen, across restarts.
    private func startPush() {
        guard !relayURL.isEmpty, !relayTokenFile.isEmpty else { return }
        let url = relayURL
        let tokenFile = relayTokenFile
        let relay = RelayStream(
            base: { @Sendable in url },
            token: { @Sendable in
                (try? String(contentsOfFile: tokenFile, encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines)
            },
            since: UserDefaults.standard.string(forKey: "prismEventsSince") ?? "",
            onSince: { @Sendable in UserDefaults.standard.set($0, forKey: "prismEventsSince") })
        self.relay = relay
        // Messages already handled, by their key.
        let seen = SeenKeys(
            initial: UserDefaults.standard.stringArray(forKey: "prismEventsSeen") ?? [], limit: 500,
            onChange: { @Sendable in UserDefaults.standard.set($0, forKey: "prismEventsSeen") })
        // Both run for the app's life: the stream reconnects by itself.
        Task {
            do {
                for try await message in stream(relay.watch()) where seen.firstTime(key: message.key) {
                    pushed(message)
                }
            } catch {
                log("push: \(error.localizedDescription)")
            }
        }
        Task {
            do {
                for try await status in stream(relay.watchStatus()) {
                    log("push: \(status.text)")
                    let connected = status.connected && !pushConnected
                    pushConnected = status.connected
                    // Whatever came while the stream was down.
                    if connected { refresh() }
                }
            } catch {
                log("push status: \(error.localizedDescription)")
            }
        }
        // After sleep, or back in the session, the old stream is likely dead:
        // start over.
        for name in [NSWorkspace.didWakeNotification, NSWorkspace.sessionDidBecomeActiveNotification] {
            NSWorkspace.shared.notificationCenter.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.relay?.reconnect() }
            }
        }
    }

    private func pushed(_ message: PushMessage) {
        // Old news from the catch-up refreshes the list but notifies nobody.
        let fresh = message.fresh(now: epochNow())
        let tag = message.notificationTag
        switch message.type {
        case "alert.firing":
            if fresh {
                notify(id: tag, message, loud: Severity.companion.of(text: message.severity) == .critical, link: nil)
            }
        case "alert.ended", "question.closed":
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [tag])
        case "agent.question", "agent.finished":
            if fresh {
                let link = T3Links.shared.app(environmentId: message.environmentId, threadId: message.threadId)
                notify(id: tag, message, loud: message.type == "agent.question", link: link)
            }
        case "notice":
            if fresh { notify(id: tag, message, loud: false, link: nil) }
        default:
            break
        }
        refresh()
    }

    private func notify(id: String, _ message: PushMessage, loud: Bool, link: String?) {
        let content = UNMutableNotificationContent()
        content.title = visible(message.title)
        content.body = visibleShort(message.body, 300)
        content.threadIdentifier = "prism"
        content.interruptionLevel = loud ? .active : .passive
        if loud { content.sound = .default }
        if let link { content.userInfo = ["link": link] }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
    }

    // MARK: - Preview

    /// Sample alerts for `prism preview`, which shows the popout in a window
    /// for screenshots; the app itself never shows them.
    static func sample() -> PrismStore {
        let store = PrismStore(config: nil)
        let now = epochNow()
        func ago(_ minutes: Double) -> Int64 { now - Int64(minutes * 60) }
        func agent(_ id: String, _ title: String, _ state: String, _ alert: Int64, _ target: String, _ minutes: Double) -> Agent {
            Agent(id: id, task: alert == 0 ? "do" : "fix", title: title, alertId: alert, target: target, state: state,
                  runStatus: "", questionPending: state == "waiting", lastMessage: "", createdAt: ago(minutes),
                  updatedAt: ago(minutes), requestedBy: "",
                  t3: AgentThread(environmentId: "sample", threadId: id, host: "", link: ""))
        }
        func alert(_ id: Int64, _ kind: String, _ title: String, _ category: String, _ severity: String, _ host: String,
                   _ summary: String, _ minutes: Double, _ agents: [Agent] = [], output: String = "") -> PrismAlert {
            PrismAlert(
                id: id, kind: kind, name: kind == "machine_event" ? title : "", alertname: kind == "alert" ? title : "",
                category: category, brief: "", severity: severity, host: host, check: "", summary: summary,
                description: "", command: "", output: output, labels: [:], state: "open", startsAt: ago(minutes),
                createdAt: ago(minutes), updatedAt: ago(minutes), endedAt: 0, agents: agents)
        }
        let review = PlacementProfile(id: "review", label: "Review", entries: [], configuredEntryCount: nil)
        store.placement = Placement(
            profiles: [
                PlacementProfile(
                    id: "coding", label: "Coding",
                    entries: [PlacementEntry.companion.reference(profile: review, enabled: true)], configuredEntryCount: 1),
                review,
            ], models: [], alertFixProfile: "coding",
            actions: PlacementActions(alertFix: "coding", textGeneration: nil), roles: [],
            policy: PlacementPolicy(
                switchAtPercent: 95, weeklySlackSessions: 1, activeHours: nil, roleProfiles: [:],
                weeklyMoveSlackSessions: nil,
                accountLimits: ["claude-alex": PlacementAccountLimit(
                    sessionPercent: KotlinDouble(double: 70), weeklyPercent: KotlinDouble(double: 90), until: "2026-11-28T08:00:00Z")]),
            moves: [])
        store.alerts = [
            alert(41, "alert", "KubePersistentVolumeFillingUp", "Storage", "critical", "",
                  "PVC data-postgres-0 in namespace databases is 91% full and fills up within 4 hours.", 23,
                  [agent("t1", "Investigate: #41 KubePersistentVolumeFillingUp", "running", 41, "cluster", 2)]),
            alert(40, "machine_event", "brew-update", "Machines", "warning", "david-mb",
                  "brew-update failed on david-mb (exit code 1)", 180,
                  [agent("t2", "Update Xcode's command line tools first", "waiting", 40, "david-mb", 15),
                   agent("t3", "Investigate: #40 HostCheckFailed", "finished", 40, "david-mb", 170)],
                  output: "Error: Your Command Line Tools (CLT) does not support macOS 27.\nUpdate them from Software Update."),
            alert(38, "machine_event", "disk-space", "Machines", "warning", "david-tp", "/ is 87% full on david-tp.", 26 * 60),
            alert(37, "alert", "PublicProbeFailed", "Public websites", "critical", "",
                  "https://status.quickme.at answered 502 for 10 minutes.", 9),
            alert(35, "alert", "BackupTooOld", "Backups", "info", "", "The last ClickHouse backup is 30 hours old.", 300),
        ]
        store.tasks = [agent("t4", "Clean up the Nix store on david-tp", "running", 0, "david-tp", 4)]
        let models = [
            ModelChoice(instanceId: "claudeAgent", provider: "claudeAgent", model: "claude-alex/claude-opus-5-5"),
            ModelChoice(instanceId: "claudeAgent", provider: "claudeAgent", model: "claude-alex/claude-sonnet-5-5"),
            ModelChoice(instanceId: "codex", provider: "codex", model: "codex-david/gpt-6.2-sol"),
        ]
        store.modelSettings = ModelSettings(
            model: models[0], reasoning: Reasoning(current: "high", defaultLevel: "medium", levels: ["low", "medium", "high", "xhigh"]),
            models: models)
        func machine(_ name: String, _ route: String, _ reachable: Bool, _ state: String) -> Machine {
            Machine(name: name, aliases: [], description: "", t3: route != "ssh", route: route, via: "", reachable: reachable,
                    state: state, detail: "", sshRunner: route == "ssh", openAlerts: [], t3WebOrigin: "")
        }
        store.machines = [
            machine("david-mb", "direct", true, "reachable"), machine("david-mm", "link", true, "connected"),
            machine("david-tp", "direct", false, "not reachable"), machine("ccs-oracle-1", "ssh", true, "reachable"),
        ]
        store.doTarget = "david-tp"
        func wave(_ base: Double, _ swing: Double, _ phase: Double) -> [KotlinDouble] {
            (0...60).map { i in
                KotlinDouble(value: min(1, max(0, base + swing * sin(Double(i) / 6 + phase) + swing / 2 * sin(Double(i) / 1.7))))
            }
        }
        let gb = 1024.0 * 1024 * 1024
        func bytes(_ n: Double) -> Int64 { Int64(n * gb) }
        func host(_ name: String, _ os: String, _ cpu: Double, _ cores: Int32, _ load: [Double], _ memory: (Double, Double),
                  _ disks: [(String, Double, Double)], _ rx: Double, _ tx: Double, up hours: Double,
                  battery: HostMetrics.Battery? = nil) -> HostMetrics {
            let fs = disks.map { HostMetrics.Filesystem(mount: $0.0, used: bytes($0.1), total: bytes($0.2)) }
            return HostMetrics(
                name: name, online: true, lastSeen: now - 20, os: os, arch: "", bootTime: now - Int64(hours * 3600),
                cpu: KotlinDouble(value: cpu), cores: cores, load: load.map { KotlinDouble(value: $0) },
                memory: HostMetrics.Usage(used: bytes(memory.0), total: bytes(memory.1)), swap: nil, disk: fs.first,
                filesystems: fs, network: HostMetrics.Network(receive: rx, transmit: tx), battery: battery,
                history: HostMetrics.History(
                    start: now - 7200, step: 120, cpu: wave(cpu * 0.8, 0.18, Double(name.count)),
                    memory: wave(memory.0 / memory.1, 0.03, 1)))
        }
        store.hostMetrics = [
            host("ccs-oracle-1", "Linux 7.3.4", 0.49, 4, [4.09, 3.92, 3.93], (5.7, 23.4), [("/", 131, 195)], 9.9e6, 5.7e6, up: 36),
            host("ccs-oracle-2", "Linux 7.3.4", 0.71, 4, [12, 12.1, 12.3], (7.5, 23.4), [("/", 182, 195)], 7.2e6, 9.7e6, up: 36),
            host("david-mb", "Darwin 27.2.0", 0.64, 18, [17.6, 26.1, 31.7], (29.6, 48), [("/System/Volumes/Data", 595, 926)],
                 325e3, 967e3, up: 145, battery: HostMetrics.Battery(level: 0.59, charging: true)),
            host("david-server", "Linux 7.3.4", 0.93, 12, [9.88, 10.4, 9.7], (5.2, 11.7),
                 [("/", 254, 393), ("/mnt/backup", 110, 125)], 11e6, 82e3, up: 20),
            HostMetrics(
                name: "david-tp", online: false, lastSeen: now - 5 * 3600, os: "", arch: "", bootTime: 0, cpu: nil, cores: 0,
                load: [], memory: nil, swap: nil, disk: nil, filesystems: [], network: nil, battery: nil, history: nil),
        ]
        store.metricsTime = Date(epoch: now)
        store.connection = .connected
        store.serverHealth = .reachable
        store.updated = Date()
        return store
    }
}
