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
    enum Tab: String, CaseIterable, Identifiable {
        case alerts, machines, profiles, approvals = "approval", wifi

        var id: String { rawValue }

        var title: String {
            switch self {
            case .alerts: "Alerts"
            case .machines: "Machines"
            case .profiles: "Profiles"
            case .approvals: "Approvals"
            case .wifi: "Wi-Fi"
            }
        }

        func symbol(selected: Bool) -> String {
            switch self {
            case .alerts: selected ? "bell.fill" : "bell"
            case .machines: "server.rack"
            case .profiles: selected ? "square.stack.3d.up.fill" : "square.stack.3d.up"
            case .approvals: selected ? "checkmark.shield.fill" : "checkmark.shield"
            case .wifi: "wifi"
            }
        }

        var shortcutKey: KeyEquivalent {
            switch self {
            case .alerts: "1"
            case .machines: "2"
            case .profiles: "3"
            case .approvals: "4"
            case .wifi: "5"
            }
        }

        var shortcutNumber: Int {
            switch self {
            case .alerts: 1
            case .machines: 2
            case .profiles: 3
            case .approvals: 4
            case .wifi: 5
            }
        }
    }

    @ObservedObject var approver: Approver
    @ObservedObject var store: PrismStore
    @ObservedObject var hotspot: Hotspot
    @AppStorage("popoutTab") private var tab = Tab.alerts
    @StateObject private var navigation = PopoutNavigation()
    @State private var refreshTrigger = 0
    @State private var screenHeight: CGFloat = NSScreen.main?.visibleFrame.height ?? 800
    @State private var chromeHeight: CGFloat = 124
    @State private var animatedHeight: CGFloat?

    private var maxWindowHeight: CGFloat { max(360, screenHeight - 20) }
    private var maxContentHeight: CGFloat { max(220, maxWindowHeight - chromeHeight) }

    private var tabBinding: Binding<Tab> {
        Binding(
            get: { tab },
            set: { next in navigation.select(next, current: tab) { tab = $0 } }
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                header
                    .padding(.horizontal, 14)
                    .padding(.top, 12)
                    .padding(.bottom, 10)
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
                PopoutTabBar(selection: tabBinding, approver: approver, store: store, hotspot: hotspot, wifi: hotspot.wifi)
                    .padding(.horizontal, 14)
                    .padding(.bottom, 10)
                Divider()
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height in
                if abs(height - chromeHeight) > 0.5 { chromeHeight = height }
            }
            Group {
                switch tab {
                case .alerts: AlertsView(store: store)
                case .machines: MachinesView(store: store)
                case .profiles: ProfilesView(store: store)
                case .approvals: ApprovalTab(approver: approver)
                case .wifi: WiFiView(hotspot: hotspot, wifi: hotspot.wifi, networks: hotspot.wifi.networks)
                }
            }
            .environment(\.popoutMaxContentHeight, maxContentHeight)
            .environment(\.popoutNavigation, navigation)
        }
        .frame(width: 400)
        .fixedSize(horizontal: false, vertical: true)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height in
            let target = min(max(120, height), maxWindowHeight)
            if animatedHeight == nil {
                animatedHeight = target
            } else if abs(target - (animatedHeight ?? 0)) > 0.5 {
                withAnimation(.snappy(duration: 0.24, extraBounce: 0)) {
                    animatedHeight = target
                }
            }
        }
        .modifier(AnimatedPopoutHeight(height: animatedHeight))
        .background {
            PopoutWindowBridge(
                onScreenHeight: { height in
                    if abs(height - screenHeight) > 1 { screenHeight = height }
                },
                onBack: { navigation.goBack(current: tab) { tab = $0 } },
                onForward: { navigation.goForward(current: tab) { tab = $0 } }
            )
            tabShortcuts
        }
    }

    private var tabShortcuts: some View {
        Group {
            ForEach(Tab.allCases) { item in
                Button("") {
                    navigation.select(item, current: tab) { tab = $0 }
                }
                .keyboardShortcut(item.shortcutKey, modifiers: .command)
            }
            Button("") {
                navigation.goBack(current: tab) { tab = $0 }
            }
            .keyboardShortcut("[", modifiers: .command)
            Button("") {
                navigation.goForward(current: tab) { tab = $0 }
            }
            .keyboardShortcut("]", modifiers: .command)
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

/// Uniform 5-destination navigation bar: every section uses the same
/// icon-over-label layout with equal flexible widths so counts and labels can
/// never push the popout wider than its 400pt frame.
private struct PopoutTabBar: View {
    @Binding var selection: PopoutView.Tab
    @ObservedObject var approver: Approver
    @ObservedObject var store: PrismStore
    @ObservedObject var hotspot: Hotspot
    @ObservedObject var wifi: WiFiSwitch
    @Environment(\.colorScheme) private var colorScheme
    @Namespace private var activePill

    var body: some View {
        HStack(spacing: 2) {
            ForEach(PopoutView.Tab.allCases) { item in
                PopoutTabItem(
                    item: item,
                    selected: selection == item,
                    badge: badge(for: item),
                    accessibilityLabel: accessibilityLabel(for: item),
                    activePill: activePill
                ) {
                    withAnimation(.snappy(duration: 0.2)) {
                        selection = item
                    }
                }
            }
        }
        .padding(3)
        .background {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(colorScheme == .dark ? Color.white.opacity(0.06) : Color.black.opacity(0.05))
                .overlay {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .strokeBorder(Color.primary.opacity(colorScheme == .dark ? 0.08 : 0.06), lineWidth: 0.5)
                }
        }
        .frame(maxWidth: .infinity)
    }

    private func badge(for item: PopoutView.Tab) -> PopoutTabItem.Badge? {
        switch item {
        case .alerts:
            let count = store.alerts.count
            guard count > 0 else { return nil }
            let tint: Color = store.openCritical > 0
                ? .red
                : store.alerts.contains { $0.level != .info } ? .orange : .blue
            return .count(count, tint)
        case .approvals:
            let count = approver.pending.count
            return count > 0 ? .count(count, .orange) : nil
        case .wifi:
            if hotspot.problem != nil || wifi.problem != nil || (wifi.joinError != nil && wifi.joining) {
                return .dot(.orange)
            }
            if hotspot.advertising != nil || wifi.joining || wifi.state != .away {
                return .dot(.accentColor)
            }
            return nil
        case .machines, .profiles:
            return nil
        }
    }

    private func accessibilityLabel(for item: PopoutView.Tab) -> String {
        switch item {
        case .alerts where !store.alerts.isEmpty:
            let critical = store.openCritical
            let detail = critical > 0 ? "\(store.alerts.count) open, \(critical) critical" : "\(store.alerts.count) open"
            return "\(item.title), \(detail)"
        case .approvals where !approver.pending.isEmpty:
            return "\(item.title), \(approver.pending.count) pending"
        case .wifi where hotspot.advertising != nil || wifi.joining || wifi.state != .away:
            return "\(item.title), hotspot active"
        case .wifi where hotspot.problem != nil || wifi.problem != nil:
            return "\(item.title), needs attention"
        default:
            return item.title
        }
    }
}

private struct PopoutTabItem: View {
    enum Badge {
        case count(Int, Color)
        case dot(Color)
    }

    let item: PopoutView.Tab
    let selected: Bool
    let badge: Badge?
    let accessibilityLabel: String
    let activePill: Namespace.ID
    let action: () -> Void
    @Environment(\.colorScheme) private var colorScheme
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            VStack(spacing: 3) {
                ZStack {
                    Image(systemName: item.symbol(selected: selected))
                        .font(.system(size: 14, weight: selected ? .semibold : .medium))
                        .foregroundStyle(iconStyle)
                }
                .frame(width: 28, height: 18)
                .overlay(alignment: .topTrailing) {
                    badgeView
                }
                Text(item.title)
                    .font(.system(size: 10, weight: selected ? .semibold : .medium))
                    .foregroundStyle(labelStyle)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 6)
            .padding(.bottom, 5)
            .padding(.horizontal, 2)
            .background {
                if selected {
                    RoundedRectangle(cornerRadius: 7.5, style: .continuous)
                        .fill(colorScheme == .dark ? Color.white.opacity(0.14) : Color(nsColor: .controlBackgroundColor))
                        .overlay {
                            RoundedRectangle(cornerRadius: 7.5, style: .continuous)
                                .strokeBorder(
                                    colorScheme == .dark ? Color.white.opacity(0.10) : Color.black.opacity(0.06),
                                    lineWidth: 0.5
                                )
                        }
                        .shadow(color: colorScheme == .dark ? .clear : Color.black.opacity(0.07), radius: 1.5, y: 0.5)
                        .matchedGeometryEffect(id: "activeTab", in: activePill)
                } else if hovering {
                    RoundedRectangle(cornerRadius: 7.5, style: .continuous)
                        .fill(Color.primary.opacity(0.05))
                }
            }
            .contentShape(RoundedRectangle(cornerRadius: 7.5, style: .continuous))
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
        .accessibilityLabel(accessibilityLabel)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .help("\(item.title) (⌘\(item.shortcutNumber))")
    }

    private var iconStyle: AnyShapeStyle {
        if selected { return AnyShapeStyle(.tint) }
        return AnyShapeStyle(hovering ? Color.primary.opacity(0.85) : Color.secondary)
    }

    private var labelStyle: AnyShapeStyle {
        if selected { return AnyShapeStyle(.primary) }
        return AnyShapeStyle(hovering ? Color.primary.opacity(0.85) : Color.secondary)
    }

    @ViewBuilder
    private var badgeView: some View {
        switch badge {
        case .count(let n, let tint):
            Text(n > 99 ? "99+" : "\(n)")
                .font(.system(size: 9, weight: .bold).monospacedDigit())
                .foregroundStyle(.white)
                .padding(.horizontal, 3.5)
                .frame(minWidth: 14, minHeight: 14)
                .background(Capsule().fill(tint))
                .offset(x: 6, y: -3)
                .accessibilityHidden(true)
        case .dot(let tint):
            Circle()
                .fill(tint)
                .frame(width: 6, height: 6)
                .offset(x: 2, y: -2)
                .accessibilityHidden(true)
        case nil:
            EmptyView()
        }
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

/// Approvals: what waits for the owner (a click shows it in the dialog) and
/// the last decisions.
struct ApprovalTab: View {
    @ObservedObject var approver: Approver
    @Environment(\.popoutNavigation) private var navigation

    var body: some View {
        PopoutScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if !approver.pending.isEmpty {
                    pending
                    Divider()
                }
                history
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .onAppear { navigation?.dismissSubstate = nil }
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
                VStack(spacing: 0) {
                    ForEach(Array(approver.history.enumerated()), id: \.offset) { _, decision in
                        HistoryRow(decision: decision)
                    }
                }
                .padding(.bottom, 8)
            }
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
