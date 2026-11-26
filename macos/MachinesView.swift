// The popout's Machines tab: CPU, memory, disk, network, load and uptime of
// every fleet host and cluster node, with the last two hours of CPU, from the
// Prism server (GET /v1/machines/metrics). It reloads every 30 seconds while
// the popout shows it; click a host for memory history, load, swap and
// filesystems.

import AppKit
import PrismShared
import SwiftUI

struct MachinesView: View {
    @ObservedObject var store: PrismStore
    @State private var expanded: String?
    /// The popout's window. A closed menu-bar popout keeps its views, so
    /// polling asks whether the window is on screen.
    @State private var window: NSWindow?

    var body: some View {
        content
            .background(WindowReader { window = $0 })
            .task(id: store.connected) {
                // Fresh numbers every 30 seconds while the popout shows the
                // tab, and at once when it opens again; the server caches for
                // 15 seconds.
                var loaded = Date.distantPast
                while !Task.isCancelled {
                    if window?.isVisible != true {
                        loaded = .distantPast
                    } else if Date().timeIntervalSince(loaded) >= 30 {
                        store.loadHostMetrics()
                        loaded = Date()
                    }
                    try? await Task.sleep(for: .seconds(2))
                }
            }
    }

    @ViewBuilder
    private var content: some View {
        switch store.connection {
        case .notConfigured:
            EmptyState(symbol: "antenna.radiowaves.left.and.right.slash", title: "Not connected to Prism",
                       detail: "fleet sets this Mac's Rocky provisioning in its Prism configuration.")
        case .failed(let text) where store.hostMetrics == nil:
            EmptyState(symbol: "exclamationmark.icloud", title: "Prism's replica did not open", detail: text, tint: .orange)
        case .connecting where store.hostMetrics == nil:
            EmptyState(title: "Waiting for Prism's replica…", detail: nil, loading: true)
        default:
            if let hosts = store.hostMetrics {
                if hosts.isEmpty {
                    EmptyState(symbol: "server.rack", title: "No metrics", detail: "No machine has reported metrics in the last week.")
                } else {
                    list(hosts)
                }
            } else if let error = store.metricsError {
                EmptyState(symbol: "exclamationmark.icloud", title: "No metrics", detail: error, tint: .orange)
            } else {
                EmptyState(title: "Loading…", detail: nil, loading: true)
            }
        }
    }

    private func list(_ hosts: [HostMetrics]) -> some View {
        let now = store.metricsTime ?? Date()
        let online = hosts.filter(\.online)
        let attention = online
            .filter { Self.attentionLevel($0) >= 1 }
            .sorted { Self.severityScore($0) > Self.severityScore($1) }
        let healthy = online.filter { Self.attentionLevel($0) == 0 }
        let offline = hosts.filter { !$0.online }
        let groups: [(String, [HostMetrics])] = [
            ("Needs attention", attention),
            ("Online", healthy),
            ("Offline", offline),
        ].filter { !$0.1.isEmpty }
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if let error = store.metricsError {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.caption)
                        .foregroundStyle(.orange)
                        .padding(.horizontal, 14)
                        .padding(.top, 8)
                }
                ForEach(groups, id: \.0) { title, list in
                    HStack(spacing: 6) {
                        SectionLabel(title)
                        Text("\(list.count)")
                            .font(.caption2.weight(.semibold).monospacedDigit())
                            .foregroundStyle(.tertiary)
                    }
                    .padding(.horizontal, 14)
                    .padding(.top, 10)
                    .padding(.bottom, 3)
                    ForEach(list) { host in
                        HostRow(host: host, now: now, expanded: expanded == host.name) {
                            withAnimation(.smooth(duration: 0.2)) { expanded = expanded == host.name ? nil : host.name }
                        }
                    }
                }
            }
            .padding(.bottom, 8)
        }
        .scrollBounceBehavior(.basedOnSize)
        .frame(maxHeight: 520)
        .fixedSize(horizontal: false, vertical: true)
    }

    /// Worst metric severity level (0 normal, 1 high, 2 critical) across CPU, memory and filesystems.
    private static func attentionLevel(_ host: HostMetrics) -> Int {
        let shares = [host.cpuShare, host.memory?.fraction, host.disk?.usage.fraction]
            + host.filesystems.map(\.usage.fraction)
        return shares.compactMap { $0.map(HostText.level) }.max() ?? 0
    }

    /// Sort key for hosts needing attention: highest severity level first, then worst share.
    private static func severityScore(_ host: HostMetrics) -> (Int, Double) {
        let shares = ([host.cpuShare, host.memory?.fraction, host.disk?.usage.fraction]
            + host.filesystems.map(\.usage.fraction)).compactMap { $0 }
        let level = shares.map(HostText.level).max() ?? 0
        let worst = shares.max() ?? 0
        return (level, worst)
    }
}

private struct HostRow: View {
    let host: HostMetrics
    let now: Date
    let expanded: Bool
    let toggle: () -> Void
    @State private var hovering = false

    var body: some View {
        if host.online {
            Button(action: toggle) {
                rowContent
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityValue(expanded ? "Expanded" : "Collapsed")
            .accessibilityHint(expanded ? "Collapse host metrics" : "Expand host metrics and filesystems")
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(expanded ? Color.primary.opacity(0.05) : hovering ? Color.primary.opacity(0.04) : .clear)
            .onHover { hovering = $0 }
        } else {
            rowContent
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
        }
    }

    private var rowContent: some View {
        VStack(alignment: .leading, spacing: 6) {
            header
            if host.online {
                if expanded {
                    Meter(label: "CPU", share: host.cpuShare, detail: cpuDetail)
                    Meter(label: "Memory", share: host.memory?.fraction, detail: host.memory.map(HostText.usage) ?? "")
                    Meter(label: "Disk", share: host.disk?.usage.fraction, detail: host.disk.map { HostText.usage($0.usage) } ?? "")
                    facts
                    if host.cpuHistory.contains(where: { $0 != nil }) {
                        Sparkline(title: "CPU, last 2 hours", points: host.cpuHistory, color: .accentColor)
                    }
                    details
                        .transition(.opacity.combined(with: .move(edge: .top)))
                } else {
                    compactMeters
                }
            }
        }
    }

    private var header: some View {
        HStack(spacing: 6) {
            HostBadge(host: host.name)
            Circle()
                .fill(host.online ? Color.green : Color.secondary)
                .frame(width: 6, height: 6)
                .accessibilityHidden(true)
            Text(about)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.tail)
            Spacer(minLength: 4)
            if !expanded, let battery = host.battery {
                HStack(spacing: 2) {
                    Image(systemName: Self.batterySymbol(battery))
                    Text(HostText.percent(battery.level)).monospacedDigit()
                }
                .font(.caption2)
                .foregroundStyle(Self.batteryTint(battery))
            }
            if host.online {
                Image(systemName: "chevron.right")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(.tertiary)
                    .rotationEffect(.degrees(expanded ? 90 : 0))
                    .accessibilityHidden(true)
            }
        }
    }

    private var compactMeters: some View {
        let worstDisk = ([host.disk?.usage.fraction] + host.filesystems.map(\.usage.fraction))
            .compactMap { $0 }.max()
        return HStack(spacing: 10) {
            CompactMeter(label: "CPU", share: host.cpuShare)
            CompactMeter(label: "Mem", share: host.memory?.fraction)
            CompactMeter(label: "Disk", share: worstDisk)
        }
    }

    private var about: String {
        guard host.online else {
            return "last seen " + host.lastSeenDate.formatted(.relative(presentation: .numeric, unitsStyle: .narrow))
        }
        var parts: [String] = []
        if !host.os.isEmpty { parts.append(host.os) }
        if let boot = host.bootDate { parts.append("up " + HostText.duration(now.timeIntervalSince(boot))) }
        return parts.joined(separator: " · ")
    }

    private var cpuDetail: String {
        var parts: [String] = []
        if host.cores > 0 { parts.append("\(host.cores) cores") }
        if let load = host.loads.first { parts.append("load " + HostText.load(load)) }
        return parts.joined(separator: " · ")
    }

    @ViewBuilder
    private var facts: some View {
        HStack(spacing: 14) {
            if let network = host.network {
                Label("↓ \(HostText.rate(network.receive))  ↑ \(HostText.rate(network.transmit))", systemImage: "network")
            }
            if let battery = host.battery {
                Label(HostText.percent(battery.level) + (battery.charging ? " · charging" : ""),
                      systemImage: Self.batterySymbol(battery))
                    .foregroundStyle(Self.batteryTint(battery))
            }
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .labelStyle(.titleAndIcon)
    }

    private static func batterySymbol(_ battery: HostMetrics.Battery) -> String {
        if battery.charging { return "battery.100percent.bolt" }
        switch battery.level {
        case ..<0.125: return "battery.0percent"
        case ..<0.375: return "battery.25percent"
        case ..<0.625: return "battery.50percent"
        case ..<0.875: return "battery.75percent"
        default: return "battery.100percent"
        }
    }

    private static func batteryTint(_ battery: HostMetrics.Battery) -> Color {
        guard !battery.charging else { return .secondary }
        if battery.level < 0.10 { return .red }
        if battery.level < 0.20 { return .orange }
        return .secondary
    }

    private var details: some View {
        VStack(alignment: .leading, spacing: 4) {
            if host.memoryHistory.contains(where: { $0 != nil }) {
                Sparkline(title: "Memory, last 2 hours", points: host.memoryHistory, color: .purple)
                    .padding(.bottom, 3)
            }
            if host.loads.count == 3 {
                detail("Load", host.loads.map(HostText.load).joined(separator: "  ") + "  (1, 5, 15 min)")
            }
            if let swap = host.swap { detail("Swap", HostText.usage(swap)) }
            ForEach(host.filesystems, id: \.mount) { fs in
                detail(fs.mount, HostText.percent(fs.usage.fraction ?? 0) + " · " + HostText.usage(fs.usage))
            }
            if !host.arch.isEmpty { detail("Arch", host.arch) }
        }
    }

    private func detail(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(label)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.middle)
                .frame(width: 130, alignment: .leading)
            Text(value).monospacedDigit()
        }
        .font(.caption)
    }
}

/// Reports the window a view is in, whenever it moves to another.
private struct WindowReader: NSViewRepresentable {
    let found: (NSWindow?) -> Void

    final class Probe: NSView {
        var found: ((NSWindow?) -> Void)?

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            let window = window
            DispatchQueue.main.async { self.found?(window) }
        }
    }

    func makeNSView(context: Context) -> Probe {
        let probe = Probe()
        probe.found = found
        return probe
    }

    func updateNSView(_ probe: Probe, context: Context) { probe.found = found }
}

/// A compact single-column mini-meter for the collapsed host row.
private struct CompactMeter: View {
    let label: String
    let share: Double?

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 4) {
                Text(label)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Spacer(minLength: 2)
                Text(share.map(HostText.percent) ?? "–")
                    .font(.caption.weight(.semibold).monospacedDigit())
                    .foregroundStyle(level == 0 ? Color.primary : tint)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(.quaternary)
                    if let share, share > 0 {
                        Capsule()
                            .fill(tint)
                            .frame(width: geo.size.width * min(1, max(0.03, share)))
                    }
                }
            }
            .frame(height: 4)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label) \(share.map(HostText.percent) ?? "unknown")")
    }

    private var level: Int { share.map(HostText.level) ?? 0 }

    private var tint: Color {
        switch level {
        case 2: .red
        case 1: .orange
        default: .accentColor
        }
    }
}

/// A share as a labelled bar; orange when high, red when nearly full, the number always in text.
private struct Meter: View {
    let label: String
    let share: Double?
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                Text(label)
                    .font(.caption.weight(.medium))
                    .frame(width: 58, alignment: .leading)
                Text(share.map(HostText.percent) ?? "–")
                    .font(.caption.weight(.semibold).monospacedDigit())
                    .foregroundStyle(level == 0 ? Color.primary : tint)
                Spacer(minLength: 8)
                Text(detail)
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(.quaternary)
                    if let share, share > 0 {
                        Capsule()
                            .fill(tint)
                            .frame(width: geo.size.width * min(1, max(0.02, share)))
                    }
                }
            }
            .frame(height: 5)
            .accessibilityElement()
            .accessibilityLabel("\(label) \(share.map(HostText.percent) ?? "unknown")")
        }
    }

    private var level: Int { share.map(HostText.level) ?? 0 }

    private var tint: Color {
        switch level {
        case 2: .red
        case 1: .orange
        default: .accentColor
        }
    }
}

/// One series 0–1 over time: a 1.5 pt line over a faint area, with gaps where the host reported nothing.
private struct Sparkline: View {
    let title: String
    let points: [Double?]
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(title)
                Spacer()
                if let peak = points.compactMap({ $0 }).max() {
                    Text("peak " + HostText.percent(peak)).monospacedDigit()
                }
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
            Canvas { context, size in
                let n = points.count
                guard n > 1 else { return }
                func x(_ i: Int) -> CGFloat { size.width * CGFloat(i) / CGFloat(n - 1) }
                func y(_ v: Double) -> CGFloat { 1 + (size.height - 2) * CGFloat(1 - min(1, max(0, v))) }
                var base = Path()
                base.move(to: CGPoint(x: 0, y: y(0)))
                base.addLine(to: CGPoint(x: size.width, y: y(0)))
                context.stroke(base, with: .style(.quaternary), lineWidth: 1)
                var i = 0
                while i < n {
                    guard points[i] != nil else {
                        i += 1
                        continue
                    }
                    let start = i
                    var line = Path()
                    while i < n, let v = points[i] {
                        let p = CGPoint(x: x(i), y: y(v))
                        if i == start { line.move(to: p) } else { line.addLine(to: p) }
                        i += 1
                    }
                    var area = line
                    area.addLine(to: CGPoint(x: x(i - 1), y: y(0)))
                    area.addLine(to: CGPoint(x: x(start), y: y(0)))
                    area.closeSubpath()
                    context.fill(area, with: .color(color.opacity(0.15)))
                    context.stroke(line, with: .color(color), style: StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round))
                }
            }
            .frame(height: 30)
            .accessibilityLabel("\(title), peak \(points.compactMap { $0 }.max().map(HostText.percent) ?? "unknown")")
        }
    }
}

/// Sizes, rates, shares and durations, short: shared/'s HostFormat, which the
/// phone uses too, in Swift's types.
enum HostText {
    private static var shared: HostFormat { HostFormat.shared }

    static func bytes(_ n: Double) -> String { shared.bytes(n: n) }

    static func usage(_ u: HostMetrics.Usage) -> String { bytes(Double(u.used)) + " of " + bytes(Double(u.total)) }

    static func rate(_ bytesPerSecond: Double) -> String { shared.rate(bytesPerSecond: bytesPerSecond) }

    static func percent(_ share: Double) -> String { shared.percent(share: share) }

    static func duration(_ seconds: TimeInterval) -> String { shared.duration(seconds: Int64(max(0, seconds))) }

    static func load(_ v: Double) -> String { shared.load(v: v) }

    static func level(_ share: Double) -> Int { Int(shared.level(share: share)) }
}
