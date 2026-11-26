// The approval dialog: a floating panel, centred and in front, showing a
// pending approval from shared/'s ApprovalQueue exactly as it will be signed:
// title, source, summary, the details and the T3 thread that asks. One touch
// of Touch ID approves: the sensor prompt is part of the dialog
// (LAAuthenticationView), not a second sheet. Deny is Esc. Both start a
// moment after an approval appears, so a keystroke or a finger meant for
// something else can't answer it. "Open in T3" only shows the thread in T3
// Code; the approval stays on screen, undecided.
//
// Everything in an approval is the asker's text, shown as plain text
// (visible()).

import AppKit
@preconcurrency import LocalAuthentication
import LocalAuthenticationEmbeddedUI
import PrismShared
import SwiftUI

final class ApprovalPanel {
    private let approver: Approver
    private var panel: NSPanel?

    init(approver: Approver) {
        self.approver = approver
    }

    /// Shows the panel while approvals are pending and closes it after.
    @MainActor
    func update() {
        if approver.pending.isEmpty {
            panel?.orderOut(nil)
            panel = nil
            return
        }
        if panel == nil {
            let panel = KeyPanel(
                contentRect: NSRect(x: 0, y: 0, width: 560, height: 420),
                styleMask: [.titled, .fullSizeContentView, .nonactivatingPanel],
                backing: .buffered, defer: false)
            panel.titleVisibility = .hidden
            panel.titlebarAppearsTransparent = true
            panel.isMovableByWindowBackground = true
            panel.level = .modalPanel
            panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
            panel.hidesOnDeactivate = false
            panel.isReleasedWhenClosed = false
            panel.animationBehavior = .alertPanel
            for button in [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton] {
                panel.standardWindowButton(button)?.isHidden = true
            }
            let host = NSHostingController(rootView: DialogView(approver: approver))
            host.sizingOptions = [.preferredContentSize]
            // The transparent title bar reserves no space above the content.
            host.safeAreaRegions = []
            panel.contentViewController = host
            panel.setContentSize(host.view.fittingSize)
            panel.center()
            self.panel = panel
        }
        // A non-activating panel takes the keyboard without pulling the app
        // forward, which macOS would refuse while the owner works elsewhere.
        panel?.orderFrontRegardless()
        panel?.makeKey()
    }

    /// A panel that takes the keyboard although the app has no main window.
    private final class KeyPanel: NSPanel {
        override var canBecomeKey: Bool { true }
        override var canBecomeMain: Bool { true }
    }
}

// MARK: - Dialog

struct DialogView: View {
    @ObservedObject var approver: Approver

    var body: some View {
        Group {
            if let request = approver.current {
                RequestView(
                    approver: approver, request: request,
                    position: approver.position(of: request), count: approver.pending.count
                )
                .id(request.id)
                .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .opacity))
            }
        }
        .animation(.smooth(duration: 0.25), value: approver.current?.id)
        .frame(width: 560)
    }
}

private struct RequestView: View {
    let approver: Approver
    @ObservedObject var request: PendingApproval
    let position: Int
    let count: Int
    @State private var armed = false
    @State private var nudgeSensor = false

    private var approval: Approval { request.approval }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            header
            if !approval.summary.isEmpty {
                Text(visibleLines(approval.summary))
                    .lineLimit(8)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if !approval.details.isEmpty {
                details
            }
            if !approval.options.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(approval.options.filter { $0.decision.hasPrefix("accept") }, id: \.decision) { option in
                        Button { approver.choose(request, decision: option.decision) } label: {
                            Label(visible(option.label), systemImage: approval.decision == option.decision ? "largecircle.fill.circle" : "circle")
                        }
                        .buttonStyle(.plain)
                        .disabled(request.phase == .signing)
                        if approval.decision == option.decision && !option.warning.isEmpty {
                            Text(visibleLines(option.warning)).font(.callout).foregroundStyle(.orange)
                        }
                    }
                }
            }
            if let thread = approval.thread {
                ThreadCard(thread: thread)
            }
            if let notice = request.notice {
                Label(notice, systemImage: "exclamationmark.circle.fill")
                    .font(.callout)
                    .foregroundStyle(.orange)
            }
            footer
        }
        .padding(.horizontal, 24)
        .padding(.top, 22)
        .padding(.bottom, 20)
        .background(alignment: .top) {
            LinearGradient(
                colors: [HostColor.of(approval.source).opacity(0.16), .clear],
                startPoint: .top, endPoint: .bottom
            )
            .frame(height: 170)
            .allowsHitTesting(false)
            .ignoresSafeArea()
        }
        // Each Touch ID attempt has its own context; "Try Again" makes a new one.
        .task(id: ObjectIdentifier(request.context)) {
            try? await Task.sleep(for: .milliseconds(700))
            armed = true
            approver.listenForTouch(request)
        }
    }

    // Icon, title, source and the queue position.
    private var header: some View {
        HStack(alignment: .top, spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(HostColor.of(approval.source).gradient)
                Image(systemName: "checkmark.shield.fill")
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(.white)
            }
            .frame(width: 48, height: 48)
            .shadow(color: HostColor.of(approval.source).opacity(0.35), radius: 6, y: 2)

            VStack(alignment: .leading, spacing: 6) {
                Text(visible(approval.title))
                    .font(.title3.weight(.semibold))
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 8) {
                    Text("from")
                        .foregroundStyle(.secondary)
                    HostBadge(host: approval.source, size: .large)
                    Text(request.requestedAt, format: .relative(presentation: .named))
                        .foregroundStyle(.secondary)
                        .help(request.requestedAt.formatted(date: .abbreviated, time: .standard))
                }
                .font(.callout)
            }
            Spacer(minLength: 8)
            if count > 1 {
                HStack(spacing: 2) {
                    Button { approver.step(-1) } label: {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 10, weight: .semibold))
                            .frame(width: 18, height: 18)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .keyboardShortcut("[", modifiers: .command)
                    .accessibilityLabel("Previous approval")
                    .help("Previous approval (⌘[)")
                    Text("\(position) of \(count)")
                        .font(.caption.weight(.semibold).monospacedDigit())
                        .padding(.horizontal, 3)
                    Button { approver.step(1) } label: {
                        Image(systemName: "chevron.right")
                            .font(.system(size: 10, weight: .semibold))
                            .frame(width: 18, height: 18)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .keyboardShortcut("]", modifiers: .command)
                    .accessibilityLabel("Next approval")
                    .help("Next approval (⌘])")
                }
                .padding(.horizontal, 5)
                .padding(.vertical, 3)
                .background(.quaternary, in: Capsule())
                .overlay(Capsule().strokeBorder(.separator, lineWidth: 1))
                .foregroundStyle(.secondary)
            }
        }
    }

    /// The details in their order, in one grid so the labels line up: label
    /// and value, or a code detail's value monospaced in a box across both.
    private var details: some View {
        Grid(alignment: .leadingFirstTextBaseline, horizontalSpacing: 12, verticalSpacing: 6) {
            ForEach(Array(approval.details.enumerated()), id: \.offset) { _, detail in
                if detail.code {
                    GridRow {
                        codeBlock(detail)
                            .gridCellColumns(2)
                            .padding(.vertical, 6)
                    }
                } else {
                    GridRow {
                        Text(visible(detail.label))
                            .foregroundStyle(.secondary)
                        Text(visible(detail.value))
                            .textSelection(.enabled)
                            .lineLimit(3)
                            .truncationMode(.middle)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .font(.callout)
                }
            }
        }
    }

    /// A code detail: its label, and the value monospaced in a box.
    private func codeBlock(_ detail: ApprovalDetail) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionLabel(visible(detail.label))
            ScrollView(.vertical) {
                Text(visibleLines(detail.value))
                    .font(.system(size: 13, design: .monospaced))
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
            }
            .scrollBounceBehavior(.basedOnSize)
            .frame(maxHeight: 190)
            .fixedSize(horizontal: false, vertical: true)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Color(nsColor: .textBackgroundColor).opacity(0.7))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(.separator, lineWidth: 1)
            )
        }
    }

    private var footer: some View {
        HStack(spacing: 10) {
            if approval.expiresAt != 0 { Countdown(requested: request.requestedAt, expires: request.expiresAt) }
            Spacer()
            Button(role: .cancel) {
                approver.deny(request)
            } label: {
                HStack(spacing: 7) {
                    Text("Deny")
                    KeyCap("esc")
                }
            }
            .buttonStyle(PillStyle())
            .keyboardShortcut(.cancelAction)
            .disabled(!armed)
            touchArea
        }
    }

    // The sensor prompt, always in the view tree so Touch ID shows here and
    // never as a system sheet: styled as a status indicator (not a clickable
    // button), or, when Touch ID is unavailable, the fallback buttons.
    private var touchArea: some View {
        HStack(spacing: request.phase == .needsAction ? 0 : 7) {
            ZStack {
                if request.phase == .queued {
                    Image(systemName: "touchid")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(.pink)
                }
                TouchIDPrompt(context: request.context)
                    .id(ObjectIdentifier(request.context))
                    .opacity(request.phase == .queued || request.phase == .needsAction ? 0 : 1)
            }
            .frame(width: request.phase == .needsAction ? 0 : 16, height: 16)
            .scaleEffect(nudgeSensor ? 1.18 : 1)
            switch request.phase {
            case .queued, .waitingForTouch:
                Text(nudgeSensor ? "Touch the sensor" : "Approve with Touch ID")
                    .font(.body.weight(.medium))
                    .foregroundStyle(nudgeSensor ? .primary : .secondary)
            case .signing:
                Text("Signing…")
                    .font(.body.weight(.medium))
                    .foregroundStyle(.secondary)
            case .needsAction:
                HStack(spacing: 10) {
                    Button("Use Password…") { approver.approveWithPassword(request) }
                        .buttonStyle(PillStyle(width: nil))
                    Button("Try Again") { request.renewContext() }
                        .buttonStyle(PillStyle(width: nil))
                }
            }
        }
        .padding(.horizontal, request.phase == .needsAction ? 0 : 12)
        .frame(height: 30)
        .overlay {
            if request.phase != .needsAction {
                Capsule()
                    .strokeBorder(
                        nudgeSensor ? AnyShapeStyle(Color.pink.opacity(0.6)) : AnyShapeStyle(.separator),
                        style: StrokeStyle(lineWidth: 1, dash: [3, 3])
                    )
            }
        }
        .contentShape(Capsule())
        .onTapGesture {
            guard request.phase == .queued || request.phase == .waitingForTouch else { return }
            withAnimation(.snappy(duration: 0.2)) { nudgeSensor = true }
            Task {
                try? await Task.sleep(for: .milliseconds(1800))
                withAnimation(.smooth(duration: 0.25)) { nudgeSensor = false }
            }
        }
        .accessibilityElement(children: request.phase == .needsAction ? .contain : .combine)
        .accessibilityHint(request.phase == .needsAction ? "" : "Rest your finger on the Touch ID sensor to approve")
        .help(request.phase == .needsAction ? "" : "Rest your finger on the Touch ID sensor to approve")
        .animation(.smooth(duration: 0.2), value: request.phase)
    }
}

/// The T3 Code thread that asks, with a button that shows it in T3 Code.
private struct ThreadCard: View {
    let thread: ThreadLink

    private var link: URL? {
        T3Links.shared.app(environmentId: thread.environmentId, threadId: thread.threadId).flatMap(URL.init(string:))
    }

    /// A few characters that tell threads apart when there is no title: the
    /// end of the ID, where T3's IDs differ.
    private var shortID: String {
        let tail = thread.threadId.unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) }.suffix(8)
        return String(String.UnicodeScalarView(tail))
    }

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            T3Icon()
            VStack(alignment: .leading, spacing: 3) {
                Text("Asked from T3 Code")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                if let title = thread.title, !title.isEmpty {
                    Text(visible(title))
                        .font(.callout.weight(.medium))
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                } else {
                    Text("\(Text("T3 thread").fontWeight(.medium)) \(Text(shortID).monospaced().foregroundStyle(.secondary))")
                        .font(.callout)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .help("Thread \(visible(thread.threadId)) in environment \(visible(thread.environmentId))")

            Button {
                if let link { NSWorkspace.shared.open(link) }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.up.forward.app")
                    Text("Open in T3")
                }
            }
            .buttonStyle(PillStyle(width: nil))
            .disabled(link == nil || T3Icon.app == nil)
            .help(T3Icon.app == nil ? "T3 Code is not installed" : "Show this thread in T3 Code; the approval stays open")
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(.quaternary.opacity(0.6))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(.separator, lineWidth: 1)
        )
    }
}

/// T3 Code's own icon, from the app that opens t3code:// links, or a symbol
/// when it is not installed.
struct T3Icon: View {
    static let app: URL? = URL(string: "t3code://threads").flatMap { NSWorkspace.shared.urlForApplication(toOpen: $0) }
    private static let image: NSImage? = app.map { NSWorkspace.shared.icon(forFile: $0.path) }

    var size: CGFloat = 36

    var body: some View {
        Group {
            if let image = Self.image {
                Image(nsImage: image).resizable().interpolation(.high)
            } else {
                Image(systemName: "bubble.left.and.text.bubble.right")
                    .font(.system(size: 18))
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: size, height: size)
    }
}

/// The dialog's two answers share one shape and size: a neutral capsule, so
/// the system's red fingerprint is the only colour in it.
struct PillShape: ViewModifier {
    var width: CGFloat?
    var visible = true
    /// The menu's smaller size.
    var compact = false

    func body(content: Content) -> some View {
        content
            .font(compact ? .callout.weight(.medium) : .body.weight(.medium))
            .padding(.horizontal, width == nil && visible ? (compact ? 10 : 16) : 0)
            .frame(width: width, height: compact ? 24 : 30)
            .background(Capsule().fill(.quaternary.opacity(visible ? 1 : 0)))
            .overlay(Capsule().strokeBorder(.separator.opacity(visible ? 1 : 0), lineWidth: 1))
            .contentShape(Capsule())
    }
}

struct PillStyle: ButtonStyle {
    static let width: CGFloat = 116
    var width: CGFloat? = PillStyle.width
    var compact = false
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .modifier(PillShape(width: width, compact: compact))
            .opacity(isEnabled ? (configuration.isPressed ? 0.6 : 1) : 0.5)
    }
}

/// A keyboard key as a hint, like the keycaps in macOS menus.
private struct KeyCap: View {
    let key: String
    init(_ key: String) { self.key = key }
    var body: some View {
        Text(key)
            .font(.system(size: 10, weight: .medium, design: .rounded))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 5)
            .padding(.vertical, 1.5)
            .overlay(RoundedRectangle(cornerRadius: 4, style: .continuous).strokeBorder(.tertiary, lineWidth: 1))
    }
}

/// Apple's embedded Touch ID prompt for `context`: while the context is being
/// evaluated, the fingerprint glyph here is the prompt.
private struct TouchIDPrompt: NSViewRepresentable {
    let context: LAContext

    func makeNSView(context _: Context) -> LAAuthenticationView {
        LAAuthenticationView(context: context, controlSize: .mini)  // 16 pt
    }

    func updateNSView(_: LAAuthenticationView, context _: Context) {}
}

/// A small caps label for a section.
struct SectionLabel: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text.uppercased())
            .font(.caption2.weight(.semibold))
            .tracking(0.6)
            .foregroundStyle(.secondary)
    }
}

/// The time left until the approval expires, as a ring (the share of its
/// whole lifetime) and m:ss.
private struct Countdown: View {
    let requested: Date
    let expires: Date

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let remaining = max(0, expires.timeIntervalSince(context.date))
            let lifetime = max(1, expires.timeIntervalSince(requested))
            let urgent = remaining < 60
            HStack(spacing: 7) {
                ZStack {
                    Circle().stroke(.quaternary, lineWidth: 3)
                    Circle()
                        .trim(from: 0, to: min(1, remaining / lifetime))
                        .stroke(urgent ? Color.orange : Color.accentColor, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .animation(.linear(duration: 1), value: remaining)
                }
                .frame(width: 16, height: 16)
                Text("Expires in \(Int(remaining) / 60):\(String(format: "%02d", Int(remaining) % 60))")
                    .font(.callout.monospacedDigit())
                    .foregroundStyle(urgent ? AnyShapeStyle(.orange) : AnyShapeStyle(.secondary))
            }
        }
    }
}

// MARK: - Hosts

/// A stable colour per host name, from a palette that reads well in light
/// and dark mode (red is left out: it would read as a warning).
enum HostColor {
    private static let palette: [Color] = [
        Color(red: 0.13, green: 0.39, blue: 0.85),  // blue
        Color(red: 0.47, green: 0.25, blue: 0.80),  // purple
        Color(red: 0.74, green: 0.31, blue: 0.04),  // orange
        Color(red: 0.03, green: 0.47, blue: 0.43),  // teal
        Color(red: 0.74, green: 0.17, blue: 0.44),  // pink
        Color(red: 0.14, green: 0.48, blue: 0.19),  // green
        Color(red: 0.29, green: 0.29, blue: 0.78),  // indigo
        Color(red: 0.58, green: 0.38, blue: 0.03),  // amber
        Color(red: 0.05, green: 0.44, blue: 0.67),  // cyan
        Color(red: 0.33, green: 0.46, blue: 0.05),  // olive
    ]

    static func of(_ host: String) -> Color {
        // FNV-1a: the same host gets the same colour on every Mac and run.
        var hash: UInt32 = 2_166_136_261
        for byte in host.utf8 { hash = (hash ^ UInt32(byte)) &* 16_777_619 }
        return palette[Int(hash % UInt32(palette.count))]
    }
}

struct HostBadge: View {
    enum Size { case small, large }
    let host: String
    var size: Size = .small

    var body: some View {
        HStack(spacing: size == .large ? 6 : 4) {
            Image(systemName: "server.rack")
                .font(size == .large ? .system(size: 11, weight: .bold) : .system(size: 8, weight: .bold))
            Text(visible(host))
                .font(size == .large ? .system(size: 14, weight: .semibold, design: .rounded) : .system(size: 11, weight: .semibold, design: .rounded))
                .lineLimit(1)
        }
        .foregroundStyle(.white)
        .padding(.horizontal, size == .large ? 10 : 6)
        .padding(.vertical, size == .large ? 4 : 2)
        .background(HostColor.of(host), in: Capsule())
    }
}
