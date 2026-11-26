// The app shell: an agent-only (LSUIElement) SwiftUI app whose one scene is
// the menu-bar extra, Prism's glyph with the number of open critical alerts.
// Its popout has five tabs: Alerts (AlertsView.swift, from the Prism
// server), Machines (MachinesView.swift), Profiles (ProfilesView.swift),
// Approvals: what waits for the owner, the last decisions and this Mac's
// public key, and Wi-Fi (WiFiView.swift): the reliable networks and the
// phone's hotspot.

import AppKit
import Combine
import PrismShared
import SwiftUI
import UserNotifications

final class AppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    /// Set by main.swift before the app starts.
    @MainActor static var approver: Approver!
    @MainActor static var store: PrismStore!
    @MainActor static var hotspot: Hotspot!
    @MainActor static var calls: PhoneCalls!
    /// True for `prism preview --menu-bar`: sample data in a second menu-bar
    /// icon, without opening Rocky, Bluetooth, Wi-Fi switching, or calls.
    @MainActor static var previewMode = false
    private var panel: ApprovalPanel?
    private var callPanel: CallPanel?
    private var hotspotWatch: AnyCancellable?

    @MainActor
    func applicationDidFinishLaunching(_ notification: Notification) {
        let approver = Self.approver!
        let panel = ApprovalPanel(approver: approver)
        self.panel = panel
        if Self.previewMode {
            approver.onQueueChange = { [weak panel, weak approver] in
                if approver?.chosenID != nil || approver?.pending.isEmpty == true {
                    panel?.update()
                }
            }
            approver.loadKey()
            approver.watch()
            return
        }
        approver.onQueueChange = { [weak panel] in panel?.update() }
        UNUserNotificationCenter.current().delegate = self
        approver.start()
        Self.store.start()
        Self.hotspot.start()
        let calls = Self.calls!
        let callPanel = CallPanel(calls: calls)
        calls.onChange = { [weak callPanel] in callPanel?.update() }
        self.callPanel = callPanel
        // The call's Bluetooth advertisement steps aside for the hotspot's.
        let hotspot = Self.hotspot!
        calls.hotspotAdvertising = { hotspot.advertising != nil }
        hotspotWatch = hotspot.$advertising.sink { _ in
            // @Published tells before the change: read it after.
            DispatchQueue.main.async { calls.hotspotChanged() }
        }
        calls.start()
    }

    /// A notification about an agent opens its thread in T3 Code.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if let text = response.notification.request.content.userInfo["link"] as? String,
           let link = URL(string: text), link.scheme == "t3code" {
            DispatchQueue.main.async { NSWorkspace.shared.open(link) }
        }
        completionHandler()
    }

    /// Shown even while the app is frontmost (the popout is open).
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }
}

struct PrismApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    var body: some Scene {
        MenuBarExtra {
            PopoutView(approver: AppDelegate.approver, store: AppDelegate.store, hotspot: AppDelegate.hotspot)
        } label: {
            MenuIcon(approver: AppDelegate.approver, store: AppDelegate.store)
        }
        .menuBarExtraStyle(.window)
    }
}

private struct MenuIcon: View {
    @ObservedObject var approver: Approver
    @ObservedObject var store: PrismStore

    var body: some View {
        HStack(spacing: 2) {
            Image(nsImage: PrismGlyph.menuBar(filled: !approver.pending.isEmpty))
            if store.openCritical > 0 {
                Text("\(store.openCritical)")
                    .font(.system(size: 13, weight: .semibold).monospacedDigit())
            }
        }
        .accessibilityLabel(accessibilityLabel)
    }

    private var accessibilityLabel: String {
        var parts = ["Prism"]
        if store.openCritical > 0 {
            parts.append("\(store.openCritical) critical \(store.openCritical == 1 ? "alert" : "alerts")")
        }
        if !approver.pending.isEmpty {
            parts.append("\(approver.pending.count) pending \(approver.pending.count == 1 ? "approval" : "approvals")")
        }
        return parts.joined(separator: ", ")
    }

}

/// Prism's glyph: a beam into a triangular prism and three rays out of it,
/// drawn as a template image. Filled while an approval waits.
enum PrismGlyph {
    static func menuBar(filled: Bool) -> NSImage { image(height: 16, filled: filled, lineWidth: 1.4) }

    static func image(height: CGFloat, filled: Bool, lineWidth: CGFloat) -> NSImage {
        let scale = height / 16
        let size = NSSize(width: 21 * scale, height: height)
        let image = NSImage(size: size, flipped: false) { _ in
            let t = NSAffineTransform()
            t.scale(by: scale)
            t.concat()
            NSColor.black.setStroke()
            NSColor.black.setFill()
            let triangle = NSBezierPath()
            triangle.move(to: NSPoint(x: 4.5, y: 2.5))
            triangle.line(to: NSPoint(x: 15.5, y: 2.5))
            triangle.line(to: NSPoint(x: 10, y: 13.5))
            triangle.close()
            triangle.lineWidth = lineWidth / scale * scale
            triangle.lineJoinStyle = .round
            if filled { triangle.fill() }
            triangle.stroke()

            let beam = NSBezierPath()
            beam.move(to: NSPoint(x: 0.7, y: 6))
            beam.line(to: NSPoint(x: 7.25, y: 8))
            for y in [10.6, 8.0, 5.4] as [CGFloat] {
                beam.move(to: NSPoint(x: 12.75, y: 8))
                beam.line(to: NSPoint(x: 20.3, y: y))
            }
            beam.lineWidth = lineWidth
            beam.lineCapStyle = .round
            beam.stroke()
            return true
        }
        image.isTemplate = true
        image.accessibilityDescription = "Prism"
        return image
    }
}

struct PopoutView: View {
    enum Tab: String { case alerts, machines, profiles, approvals = "approval", wifi }

    @ObservedObject var approver: Approver
    @ObservedObject var store: PrismStore
    @ObservedObject var hotspot: Hotspot
    @AppStorage("popoutTab") private var tab = Tab.alerts
    @State private var refreshTrigger = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
                .padding(14)
            ForEach(approver.problems, id: \.self) { problem in
                Label(problem, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .padding(.horizontal, 14)
                    .padding(.bottom, 6)
            }
            if hotspot.keyFile != nil {
                HotspotBanner(hotspot: hotspot, wifi: hotspot.wifi, showWifiProblem: tab != .wifi)
            }
            Picker("", selection: $tab) {
                Text(store.alerts.isEmpty ? "Alerts" : "Alerts (\(store.alerts.count))").tag(Tab.alerts)
                Text("Machines").tag(Tab.machines)
                Text("Profiles").tag(Tab.profiles)
                Text(approver.pending.isEmpty ? "Approvals" : "Approvals (\(approver.pending.count))").tag(Tab.approvals)
                // An icon: five labels don't fit the popout's width.
                Image(systemName: "wifi").accessibilityLabel("Wi-Fi").help("Wi-Fi").tag(Tab.wifi)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 14)
            .padding(.bottom, 10)
            Divider()
            switch tab {
            case .alerts: AlertsView(store: store)
            case .machines: MachinesView(store: store)
            case .profiles: ProfilesView(store: store)
            case .approvals: ApprovalTab(approver: approver)
            case .wifi: WiFiView(hotspot: hotspot, wifi: hotspot.wifi, networks: hotspot.wifi.networks)
            }
        }
        .background { tabShortcuts }
        .frame(width: 400)
    }

    private var tabShortcuts: some View {
        Group {
            Button("") { tab = .alerts }.keyboardShortcut("1", modifiers: .command)
            Button("") { tab = .machines }.keyboardShortcut("2", modifiers: .command)
            Button("") { tab = .profiles }.keyboardShortcut("3", modifiers: .command)
            Button("") { tab = .approvals }.keyboardShortcut("4", modifiers: .command)
            Button("") { tab = .wifi }.keyboardShortcut("5", modifiers: .command)
        }
        .frame(width: 0, height: 0)
        .opacity(0)
        .accessibilityHidden(true)
    }

    private var header: some View {
        HStack(spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(headerTint.gradient)
                Image(nsImage: PrismGlyph.image(height: 17, filled: false, lineWidth: 1.6))
                    .renderingMode(.template)
                    .foregroundStyle(.white)
            }
            .frame(width: 32, height: 32)
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("Prism").font(.headline)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            ServerStatus(store: store, refreshTrigger: refreshTrigger, onRefresh: performRefresh)
            Menu {
                Button("Refresh", action: performRefresh)
                    .disabled(!store.connected && store.connection != .connecting && !isFailed)
                Button("Copy Public Key") {
                    if let key = approver.key?.publicKeyLine {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(key, forType: .string)
                    }
                }
                .disabled(approver.key == nil)
                Divider()
                Button("Quit Prism") { NSApp.terminate(nil) }
                    .keyboardShortcut("q", modifiers: .command)
            } label: {
                Image(systemName: "ellipsis.circle")
                    .font(.system(size: 13, weight: .medium))
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .fixedSize()
            .accessibilityLabel("More options")
            .help("More options")
        }
    }

    private func performRefresh() {
        refreshTrigger += 1
        store.refresh()
        store.checkServerHealth()
    }

    private var isFailed: Bool {
        if case .failed = store.connection { return true }
        return false
    }

    /// Red while a critical alert is open, else this Mac's colour.
    private var headerTint: Color {
        store.openCritical > 0 ? .red : HostColor.of(approver.config.host)
    }

    private var subtitle: String {
        if store.serverHealth == .unavailable { return "\(approver.config.host) · Prism server unreachable" }
        guard store.connection == .connected, store.serverHealth == .reachable else { return approver.config.host }
        let open = store.alerts.count
        if open == 0 { return "\(approver.config.host) · all clear" }
        let critical = store.openCritical
        return "\(approver.config.host) · \(open) open" + (critical > 0 ? ", \(critical) critical" : "")
    }
}

/// Active hotspot status above the tabs: shown only while asking, joining,
/// switching, on the hotspot, or when there is a problem to surface. The idle
/// "Ask the phone for its hotspot" action lives in the Wi-Fi tab.
private struct HotspotBanner: View {
    @ObservedObject var hotspot: Hotspot
    @ObservedObject var wifi: WiFiSwitch
    let showWifiProblem: Bool

    private var hasStatus: Bool {
        hotspot.advertising != nil || wifi.joining || wifi.state != .away
    }

    private var visibleWifiProblem: String? {
        showWifiProblem ? wifi.problem : nil
    }

    private var visible: Bool {
        hasStatus || hotspot.problem != nil || visibleWifiProblem != nil || (wifi.joinError != nil && wifi.joining)
    }

    var body: some View {
        if visible {
            VStack(alignment: .leading, spacing: 4) {
                if hasStatus {
                    row
                }
                if let problem = hotspot.problem {
                    Label(problem, systemImage: "exclamationmark.triangle.fill")
                        .font(.caption)
                        .foregroundStyle(.orange)
                        .lineLimit(2)
                }
                if let error = wifi.joinError, wifi.joining {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.caption)
                        .foregroundStyle(.orange)
                        .lineLimit(2)
                }
                if let problem = visibleWifiProblem {
                    Label(problem, systemImage: "exclamationmark.triangle.fill")
                        .font(.caption)
                        .foregroundStyle(.orange)
                        .lineLimit(2)
                }
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 10)
        }
    }

    private var row: some View {
        HStack(spacing: 6) {
            switch hotspot.advertising {
            case .release(let ssid):
                if case .switching = wifi.state {
                    status("On the phone's hotspot · switching to \(ssid)…")
                } else {
                    status("On \(ssid) · switching the phone's hotspot off…")
                }
            case .offline:
                status("Offline: asking the phone for its hotspot\(wifi.joining ? " and joining it" : "")…")
            case .asked(let until):
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    let left = max(0, Int(until.timeIntervalSince(context.date)))
                    status("Asking the phone for its hotspot\(wifi.joining ? " and joining it" : "")… \(left / 60):\(String(format: "%02d", left % 60))")
                }
            case nil:
                if case .checking(let ssid) = wifi.state {
                    status("Left the hotspot · checking the internet on \(ssid)…")
                } else if case .leaving(let ssid) = wifi.state {
                    status("On a hotspot · joining \(ssid)…")
                } else if wifi.joining {
                    status("Looking for the phone's hotspot to join it…")
                } else if wifi.state == .onHotspot {
                    status(wifi.current.phoneHotspot ? "On the phone's hotspot" : "On a hotspot (metered Wi-Fi)")
                }
            }
            Spacer(minLength: 0)
        }
    }

    private func status(_ text: String) -> some View {
        Label(text, systemImage: "antenna.radiowaves.left.and.right")
            .font(.caption.monospacedDigit())
            .foregroundStyle(.secondary)
    }
}

/// The Prism server's connection as a dot, with a refresh button.
private struct ServerStatus: View {
    @ObservedObject var store: PrismStore
    let refreshTrigger: Int
    let onRefresh: () -> Void
    @State private var spinTurns: Double = 0

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(tint)
                .frame(width: 7, height: 7)
                .accessibilityElement()
                .accessibilityLabel(text)
                .help(text)
            Button(action: onRefresh) {
                Image(systemName: "arrow.clockwise")
                    .font(.system(size: 12, weight: .medium))
                    .rotationEffect(.degrees(spinTurns * 360))
            }
            .buttonStyle(.borderless)
            .keyboardShortcut("r", modifiers: .command)
            .disabled(!store.connected && store.connection != .connecting && !isFailed)
            .accessibilityLabel("Refresh")
            .help("Refresh (⌘R)")
            .onChange(of: refreshTrigger) { _, _ in
                withAnimation(.easeInOut(duration: 0.45)) { spinTurns += 1 }
            }
        }
    }

    private var isFailed: Bool {
        if case .failed = store.connection { return true }
        return false
    }

    private var tint: Color {
        switch store.connection {
        case .connected:
            switch store.serverHealth {
            case .reachable: .green
            case .checking: .yellow
            case .unavailable: .orange
            }
        case .connecting: .yellow
        case .failed: .orange
        case .notConfigured: .secondary
        }
    }

    private var text: String {
        var text: String
        switch store.connection {
        case .connected:
            switch store.serverHealth {
            case .reachable: text = "Prism server reachable; local replica open"
            case .checking: text = "Checking Prism server; local replica open"
            case .unavailable: text = "Prism server unreachable; local data may be out of date"
            }
        case .connecting: text = "Opening Prism's replica…"
        case .failed(let error): text = "Prism: \(error)"
        case .notConfigured: text = "No Rocky provisioning configured"
        }
        if let updated = store.updated {
            text += "\nUpdated \(updated.formatted(date: .omitted, time: .standard))"
        }
        return text
    }
}

/// Approvals: what waits for the owner (a click shows it in the dialog), the
/// last decisions, this Mac's public key.
struct ApprovalTab: View {
    @ObservedObject var approver: Approver
    @State private var copiedKey = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if !approver.pending.isEmpty {
                pending
                Divider()
            }
            history
            Divider()
            publicKey
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
        }
    }

    private func heading(_ text: String, count: Int? = nil) -> some View {
        HStack(spacing: 6) {
            SectionLabel(text)
            if let count, count > 0 {
                Text("\(count)")
                    .font(.caption2.weight(.semibold).monospacedDigit())
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 10)
        .padding(.bottom, 4)
    }

    private var pending: some View {
        VStack(alignment: .leading, spacing: 0) {
            heading("Waiting for you", count: approver.pending.count)
            ForEach(approver.pending) { request in
                PendingRow(request: request) { approver.show(request.id) }
            }
            Spacer().frame(height: 6)
        }
    }

    private var history: some View {
        VStack(alignment: .leading, spacing: 0) {
            heading("Recent decisions", count: approver.history.isEmpty ? nil : approver.history.count)
            if approver.history.isEmpty {
                Text("No decisions yet.")
                    .font(.callout)
                    .foregroundStyle(.tertiary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 18)
            } else {
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(Array(approver.history.enumerated()), id: \.offset) { _, decision in
                            HistoryRow(decision: decision)
                        }
                    }
                    .padding(.bottom, 6)
                }
                .frame(maxHeight: 300)
                .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var publicKey: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                SectionLabel("This Mac's public key")
                Spacer()
                if let key = approver.key?.publicKeyLine {
                    Button {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(key, forType: .string)
                        copiedKey = true
                        Task {
                            try? await Task.sleep(for: .milliseconds(1500))
                            copiedKey = false
                        }
                    } label: {
                        Label(copiedKey ? "Copied" : "Copy", systemImage: copiedKey ? "checkmark" : "doc.on.doc")
                            .font(.caption2.weight(.medium))
                    }
                    .buttonStyle(.borderless)
                    .foregroundStyle(copiedKey ? .green : .secondary)
                    .accessibilityLabel("Copy public key")
                    .help("Copy this Mac's public key")
                }
            }
            Text(approver.key?.publicKeyLine ?? "No key")
                .font(.system(size: 11, design: .monospaced))
                .lineLimit(1)
                .truncationMode(.middle)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// A pending approval: what, from where, and the time left.
private struct PendingRow: View {
    @ObservedObject var request: PendingApproval
    let open: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: open) {
            HStack(alignment: .center, spacing: 9) {
                Image(systemName: "hand.raised.fill")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.orange)
                    .frame(width: 18)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        HostBadge(host: request.approval.source)
                        Spacer(minLength: 4)
                        TimelineView(.periodic(from: .now, by: 1)) { context in
                            let left = max(0, Int(request.expiresAt.timeIntervalSince(context.date)))
                            Text("\(left / 60):\(String(format: "%02d", left % 60)) left")
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(left < 60 ? AnyShapeStyle(.orange) : AnyShapeStyle(.secondary))
                        }
                    }
                    Text(visible(request.approval.title))
                        .font(.callout.weight(.medium))
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
            .background(hovering ? Color.primary.opacity(0.06) : .clear)
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
        .help("Show this approval")
    }
}

private struct HistoryRow: View {
    let decision: Decision
    @State private var hovering = false

    private var date: Date { Date(timeIntervalSince1970: TimeInterval(decision.time)) }

    var body: some View {
        HStack(alignment: .center, spacing: 9) {
            Image(systemName: symbol)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 18)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    HostBadge(host: decision.source)
                    Text(label)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(tint)
                    Spacer(minLength: 4)
                    Text(date, format: .relative(presentation: .named))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Text(visible(decision.title))
                    .font(.callout)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .foregroundStyle(.primary.opacity(0.85))
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .background(hovering ? Color.primary.opacity(0.06) : .clear)
        .onHover { hovering = $0 }
        .help("\(visible(decision.title))\n\(label) from \(visible(decision.source)), \(date.formatted(date: .abbreviated, time: .standard))")
    }

    private var label: String {
        switch decision.outcome {
        case .approved: "Approved"
        case .denied: "Denied"
        case .expired: "Expired"
        case .withdrawn: "Withdrawn"
        default: decision.outcome.name.capitalized
        }
    }

    private var symbol: String {
        switch decision.outcome {
        case .approved: "checkmark.circle.fill"
        case .denied: "xmark.circle.fill"
        case .expired: "clock.badge.xmark"
        default: "arrow.uturn.backward.circle"
        }
    }

    private var tint: Color {
        switch decision.outcome {
        case .approved: .green
        case .denied: .red
        default: .secondary
        }
    }
}
