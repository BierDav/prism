// The popout's Alerts tab: the open alerts and machine events grouped by
// category (most severe groups first), each with its severity, the agents it
// started (Open in T3), and the quick actions: fix and a free-text
// instruction. An alert leaves the list only when its condition clears.

import AppKit
import PrismShared
import SwiftUI

struct AlertsView: View {
    @ObservedObject var store: PrismStore
    /// The alert open at the start (`prism preview --expand N`).
    @MainActor static var initiallyExpanded: Int64?
    @State private var expanded: Int64? = AlertsView.initiallyExpanded
    @Environment(\.popoutNavigation) private var navigation

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            content
            if let error = store.actionError {
                HStack(alignment: .top, spacing: 6) {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.caption)
                        .foregroundStyle(.orange)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 4)
                    Button { store.actionError = nil } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(.secondary)
                            .frame(width: 16, height: 16)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Dismiss error")
                    .help("Dismiss")
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 6)
            }
        }
        .onAppear {
            navigation?.dismissSubstate = {
                guard expanded != nil else { return false }
                withAnimation(.snappy(duration: 0.22, extraBounce: 0)) { expanded = nil }
                return true
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch store.connection {
        case .notConfigured:
            EmptyState(symbol: "antenna.radiowaves.left.and.right.slash", title: "Not connected to Prism",
                       detail: "fleet sets this Mac's Rocky provisioning and the workers in its Prism configuration (rocky).")
        case .failed(let text) where store.alerts.isEmpty:
            EmptyState(symbol: "exclamationmark.icloud", title: "Prism's replica did not open", detail: text, tint: .orange)
        case .connecting where store.alerts.isEmpty:
            EmptyState(title: "Connecting…", detail: nil, loading: true)
        default:
            if store.alerts.isEmpty && store.tasks.isEmpty {
                EmptyState(symbol: "checkmark.seal.fill", title: "All clear", detail: "No open alerts or machine events.", tint: .green)
            } else {
                list
            }
        }
    }

    private var list: some View {
        PopoutScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if store.alerts.isEmpty {
                    EmptyState(symbol: "checkmark.seal.fill", title: "All clear", detail: nil, tint: .green)
                }
                ForEach(store.grouped, id: \.category) { group in
                    HStack(spacing: 6) {
                        SectionLabel(group.category)
                        Text("\(group.alerts.count)")
                            .font(.caption2.weight(.semibold).monospacedDigit())
                            .foregroundStyle(.tertiary)
                    }
                    .padding(.horizontal, 14)
                    .padding(.top, 10)
                    .padding(.bottom, 3)
                    ForEach(group.alerts) { alert in
                        AlertRow(
                            store: store, alert: alert, expanded: expanded == alert.id,
                            toggle: {
                                withAnimation(.snappy(duration: 0.24, extraBounce: 0)) {
                                    expanded = expanded == alert.id ? nil : alert.id
                                }
                            })
                    }
                }
                if !store.tasks.isEmpty {
                    HStack(spacing: 6) {
                        SectionLabel("Agents")
                        Text("\(store.tasks.count)")
                            .font(.caption2.weight(.semibold).monospacedDigit())
                            .foregroundStyle(.tertiary)
                    }
                    .padding(.horizontal, 14)
                    .padding(.top, 10)
                    .padding(.bottom, 5)
                    AgentList(agents: store.tasks, showTarget: true)
                        .padding(.horizontal, 14)
                }
            }
            .padding(.bottom, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct EmptyState: View {
    var symbol: String = ""
    let title: String
    let detail: String?
    var tint: Color = .secondary
    var loading = false

    var body: some View {
        VStack(spacing: 6) {
            if loading {
                ProgressView()
                    .controlSize(.regular)
                    .frame(height: 28)
            } else {
                Image(systemName: symbol)
                    .font(.system(size: 24, weight: .medium))
                    .foregroundStyle(tint)
            }
            Text(title).font(.callout.weight(.medium))
            if let detail {
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 24)
        .padding(.vertical, 22)
    }
}

// MARK: - One alert

struct SeverityIcon: View {
    let severity: Severity

    var body: some View {
        Image(systemName: Self.symbol(severity))
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(Self.tint(severity))
            .frame(width: 18)
            .accessibilityLabel(severity.name.capitalized)
            .help(severity.name.capitalized)
    }

    static func symbol(_ severity: Severity) -> String {
        switch severity {
        case .critical: "exclamationmark.octagon.fill"
        case .info: "info.circle.fill"
        default: "exclamationmark.triangle.fill"
        }
    }

    static func tint(_ severity: Severity) -> Color {
        switch severity {
        case .critical: .red
        case .info: .blue
        default: .orange
        }
    }
}

private struct AlertRow: View {
    @ObservedObject var store: PrismStore
    let alert: PrismAlert
    let expanded: Bool
    let toggle: () -> Void
    @State private var hovering = false
    @State private var instruction = ""

    private var busy: Bool { store.busy.contains(alert.id) }
    private var enabled: Bool { !busy && store.connected }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: toggle) { summaryLine }
                .buttonStyle(.plain)
                .accessibilityValue(expanded ? "Expanded" : "Collapsed")
                .accessibilityHint(expanded ? "Collapse alert details" : "Expand alert details")
                .zIndex(1)
            if expanded {
                details
                    .padding(.top, 8)
                    .transition(.verticalReveal)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 7)
        .background(
            expanded ? Color.primary.opacity(0.05) : hovering ? Color.primary.opacity(0.04) : .clear)
        .onHover { hovering = $0 }
    }

    private var summaryLine: some View {
        HStack(alignment: .top, spacing: 9) {
            SeverityIcon(severity: alert.level)
                .padding(.top, 1)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text("#\(alert.id)")
                        .font(.system(size: 11, weight: .semibold, design: .monospaced))
                        .foregroundStyle(.secondary)
                    Text(visible(alert.title))
                        .font(.callout.weight(.medium))
                        .lineLimit(1)
                        .truncationMode(.tail)
                    Spacer(minLength: 4)
                    if busy {
                        ProgressView().controlSize(.mini)
                    }
                    Text(alert.sinceDate, format: .relative(presentation: .numeric, unitsStyle: .narrow))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 9, weight: .semibold))
                        .foregroundStyle(.tertiary)
                        .rotationEffect(.degrees(expanded ? 90 : 0))
                        .accessibilityHidden(true)
                }
                HStack(spacing: 6) {
                    if !alert.host.isEmpty {
                        HostBadge(host: alert.host)
                    } else {
                        ClusterBadge()
                    }
                    Text(visible(alert.summary))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(expanded ? nil : 1)
                        .truncationMode(.tail)
                        .fixedSize(horizontal: false, vertical: expanded)
                    Spacer(minLength: 0)
                    if !alert.agents.isEmpty && !expanded {
                        AgentCount(agents: alert.agents)
                    }
                }
            }
        }
        .contentShape(Rectangle())
    }

    private var details: some View {
        VStack(alignment: .leading, spacing: 8) {
            if !alert.brief.isEmpty {
                Text(visible(alert.brief))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !alert.output.isEmpty {
                PopoutScrollView(maxHeight: 110, defaultAnchor: .bottom) {
                    Text(visibleLines(alert.output))
                        .font(.system(size: 10, design: .monospaced))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(8)
                }
                .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Color(nsColor: .textBackgroundColor).opacity(0.7)))
                .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).strokeBorder(.separator, lineWidth: 1))
            }
            if !alert.agents.isEmpty {
                AgentList(agents: alert.agents, showTarget: false)
            }
            HStack(spacing: 6) {
                Button { store.perform(.fix(nil), on: alert) } label: {
                    Label("Fix", systemImage: "wrench.and.screwdriver")
                }
                .help("Start an agent that investigates #\(alert.id) and fixes it where appropriate")
                Spacer(minLength: 0)
            }
            .buttonStyle(PillStyle(width: nil, compact: true))
            .disabled(!enabled)
            DoField(text: $instruction, placeholder: "Tell an agent what to do about #\(alert.id)…",
                    enabled: enabled) {
                let text = instruction.trimmingCharacters(in: .whitespacesAndNewlines)
                store.perform(.fix(text), on: alert) {
                    instruction = ""
                }
            }
        }
        .padding(.leading, 27)
    }
}

/// A free-text instruction with a send button; Return sends.
struct DoField: View {
    @Binding var text: String
    let placeholder: String
    let enabled: Bool
    let send: () -> Void
    @FocusState private var focused: Bool

    private var ready: Bool { enabled && !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "sparkles")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.secondary)
            TextField(placeholder, text: $text)
                .textFieldStyle(.plain)
                .font(.callout)
                .focused($focused)
                .onSubmit { if ready { send() } }
                .disabled(!enabled)
            Button(action: send) {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 17))
                    .foregroundStyle(ready ? Color.accentColor : Color.secondary.opacity(0.5))
            }
            .buttonStyle(.plain)
            .disabled(!ready)
            .accessibilityLabel("Start agent")
            .help("Start an agent")
        }
        .padding(.leading, 9)
        .padding(.trailing, 4)
        .frame(height: 28)
        .background(Capsule().fill(Color(nsColor: .textBackgroundColor).opacity(0.7)))
        .overlay(
            Capsule().strokeBorder(
                focused ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(.separator),
                lineWidth: focused ? 1.5 : 1
            )
        )
        .animation(.easeInOut(duration: 0.12), value: focused)
    }
}

/// Agents in a card, one row each.
private struct AgentList: View {
    let agents: [Agent]
    let showTarget: Bool

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(agents.enumerated()), id: \.element.id) { index, agent in
                if index > 0 { Divider().padding(.leading, 34) }
                AgentRow(agent: agent, showTarget: showTarget)
            }
        }
        .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(.quaternary.opacity(0.6)))
        .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).strokeBorder(.separator, lineWidth: 1))
    }
}

private struct AgentRow: View {
    let agent: Agent
    let showTarget: Bool

    var body: some View {
        HStack(spacing: 8) {
            T3Icon(size: 18)
            VStack(alignment: .leading, spacing: 1) {
                Text(visible(agent.displayTitle))
                    .font(.caption.weight(.medium))
                    .lineLimit(1)
                    .truncationMode(.tail)
                HStack(spacing: 4) {
                    Circle().fill(Self.tint(agent.status)).frame(width: 6, height: 6)
                    Text(Self.label(agent.status))
                    if showTarget {
                        Text("·").foregroundStyle(.tertiary)
                        Text(visible(agent.target))
                    }
                    Text("·").foregroundStyle(.tertiary)
                    Text(agent.updated, format: .relative(presentation: .named))
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                if let link = agent.url { NSWorkspace.shared.open(link) }
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: "arrow.up.forward.app")
                    Text("Open in T3")
                }
                .font(.caption.weight(.medium))
                .modifier(CapsuleChipShape())
            }
            .buttonStyle(.plain)
            .disabled(agent.url == nil || T3Icon.app == nil)
            .help(T3Icon.app == nil ? "T3 Code is not installed" : "Show this agent's thread in T3 Code")
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
    }

    /// Agent.status: running, waiting, finished, failed or stale.
    static func label(_ status: String) -> String {
        switch status {
        case "running": "Working"
        case "waiting": "Has a question"
        case "finished": "Finished"
        case "failed": "Failed"
        case "stale": "Idle"
        default: status.capitalized
        }
    }

    static func tint(_ status: String) -> Color {
        switch status {
        case "running": .blue
        case "waiting": .orange
        case "finished": .green
        case "failed": .red
        default: .secondary
        }
    }
}

/// How many agents an alert started, and whether one waits for the owner.
private struct AgentCount: View {
    let agents: [Agent]

    var body: some View {
        let waiting = agents.contains { $0.status == "waiting" }
        HStack(spacing: 3) {
            Image(systemName: waiting ? "questionmark.bubble.fill" : "person.2.wave.2.fill")
            Text("\(agents.count)")
        }
        .font(.caption2.weight(.semibold))
        .foregroundStyle(waiting ? Color.orange : Color.secondary)
        .help(waiting ? "An agent has a question" : "\(agents.count) agent\(agents.count == 1 ? "" : "s")")
    }
}

/// The badge for alerts about the cluster rather than one machine.
struct ClusterBadge: View {
    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "circle.hexagongrid.fill")
                .font(.system(size: 8, weight: .bold))
            Text("cluster")
                .font(.system(size: 11, weight: .semibold, design: .rounded))
        }
        .foregroundStyle(.secondary)
        .padding(.horizontal, 6)
        .padding(.vertical, 2)
        .background(Capsule().fill(.quaternary))
    }
}
