// Shared popout chrome utilities: overlay-only scroll views capped to the
// screen's visible height, smooth animated window height, mouse back/forward
// navigation, and downward-only disclosure reveal transitions.

import AppKit
import SwiftUI

// MARK: - Navigation (Back / Forward)

@MainActor
final class PopoutNavigation: ObservableObject {
    private var backStack: [PopoutView.Tab] = []
    private var forwardStack: [PopoutView.Tab] = []
    /// Non-zero while an in-tab disclosure (alert/machine expand or collapse)
    /// is actively driving height via `RevealLayout`, so the window frame tracks
    /// it directly on the exact same frame instead of starting a second animation.
    @Published private(set) var inlineResizeCount = 0
    /// Registered by the active tab when an in-tab editor, inline field, or
    /// expanded row should be dismissed before popping tab history.
    var dismissSubstate: (() -> Bool)?

    func performInlineResize(_ updates: () -> Void) {
        inlineResizeCount += 1
        withAnimation(.snappy(duration: 0.24, extraBounce: 0), completionCriteria: .removed) {
            updates()
        } completion: { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.inlineResizeCount = max(0, self.inlineResizeCount - 1)
            }
        }
    }

    func select(_ next: PopoutView.Tab, current: PopoutView.Tab, apply: (PopoutView.Tab) -> Void) {
        guard next != current else { return }
        if backStack.last != current {
            backStack.append(current)
            if backStack.count > 30 { backStack.removeFirst(backStack.count - 30) }
        }
        forwardStack.removeAll()
        withAnimation(.snappy(duration: 0.2, extraBounce: 0)) {
            apply(next)
        }
    }

    @discardableResult
    func goBack(current: PopoutView.Tab, apply: (PopoutView.Tab) -> Void) -> Bool {
        if dismissSubstate?() == true { return true }
        guard let previous = backStack.popLast() else { return false }
        forwardStack.append(current)
        withAnimation(.snappy(duration: 0.2, extraBounce: 0)) {
            apply(previous)
        }
        return true
    }

    @discardableResult
    func goForward(current: PopoutView.Tab, apply: (PopoutView.Tab) -> Void) -> Bool {
        guard let next = forwardStack.popLast() else { return false }
        backStack.append(current)
        withAnimation(.snappy(duration: 0.2, extraBounce: 0)) {
            apply(next)
        }
        return true
    }
}

private struct PopoutMaxContentHeightKey: EnvironmentKey {
    static let defaultValue: CGFloat = 680
}

private struct PopoutNavigationKey: EnvironmentKey {
    static let defaultValue: PopoutNavigation? = nil
}

extension EnvironmentValues {
    var popoutMaxContentHeight: CGFloat {
        get { self[PopoutMaxContentHeightKey.self] }
        set { self[PopoutMaxContentHeightKey.self] = newValue }
    }

    var popoutNavigation: PopoutNavigation? {
        get { self[PopoutNavigationKey.self] }
        set { self[PopoutNavigationKey.self] = newValue }
    }
}

// MARK: - Overlay ScrollView (only scrolls once screen height is full)

/// Vertical scroll container that forces AppKit's overlay scrollbar style
/// (never taking up layout width, even when a mouse is attached) and hides
/// scroll indicators until the content actually exceeds its maximum height.
struct PopoutScrollView<Content: View>: View {
    var maxHeight: CGFloat? = nil
    var defaultAnchor: UnitPoint? = nil
    @ViewBuilder var content: () -> Content
    @Environment(\.popoutMaxContentHeight) private var popoutMaxContentHeight
    @State private var contentHeight: CGFloat = 0

    private var effectiveMaxHeight: CGFloat { maxHeight ?? popoutMaxContentHeight }
    private var needsScroll: Bool { contentHeight > effectiveMaxHeight + 0.5 }

    var body: some View {
        ScrollView(.vertical) {
            content()
                .background(OverlayScrollbars())
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height in
                    if abs(height - contentHeight) > 0.5 {
                        contentHeight = height
                    }
                }
        }
        .defaultScrollAnchor(defaultAnchor)
        .scrollBounceBehavior(.basedOnSize)
        .scrollIndicators(needsScroll ? .automatic : .never)
        .frame(maxHeight: effectiveMaxHeight)
        .fixedSize(horizontal: false, vertical: true)
    }
}

/// Forces every ancestor NSScrollView to use `.overlay` scrollers even when
/// macOS prefers `.legacy` scrollers because an external mouse is connected.
private struct OverlayScrollbars: NSViewRepresentable {
    final class Probe: NSView {
        private var observer: NSObjectProtocol?

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            applyOverlayStyle()
            DispatchQueue.main.async { [weak self] in self?.applyOverlayStyle() }
            if observer == nil {
                observer = NotificationCenter.default.addObserver(
                    forName: NSScroller.preferredScrollerStyleDidChangeNotification,
                    object: nil, queue: .main
                ) { [weak self] _ in
                    self?.applyOverlayStyle()
                }
            }
        }

        func applyOverlayStyle() {
            var current: NSView? = self
            while let view = current {
                if let scroll = view as? NSScrollView {
                    if scroll.scrollerStyle != .overlay {
                        scroll.scrollerStyle = .overlay
                    }
                    scroll.autohidesScrollers = true
                }
                current = view.superview
            }
        }

        deinit {
            if let observer { NotificationCenter.default.removeObserver(observer) }
        }
    }

    func makeNSView(context: Context) -> Probe { Probe() }
    func updateNSView(_ probe: Probe, context: Context) { probe.applyOverlayStyle() }
}

// MARK: - Window Bridge (screen height + mouse Back/Forward buttons)

struct PopoutWindowBridge: NSViewRepresentable {
    let onScreenHeight: (CGFloat) -> Void
    let onBack: () -> Void
    let onForward: () -> Void

    final class Probe: NSView {
        var onScreenHeight: ((CGFloat) -> Void)?
        var onBack: (() -> Void)?
        var onForward: (() -> Void)?
        private var observers: [NSObjectProtocol] = []
        private var mouseMonitor: Any?

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            installObservers()
            installMouseMonitor()
            reportScreenHeight()
        }

        private func installObservers() {
            for observer in observers { NotificationCenter.default.removeObserver(observer) }
            observers.removeAll()
            guard let window else { return }
            let center = NotificationCenter.default
            observers.append(center.addObserver(
                forName: NSWindow.didChangeScreenNotification, object: window, queue: .main
            ) { [weak self] _ in self?.reportScreenHeight() })
            observers.append(center.addObserver(
                forName: NSWindow.didBecomeKeyNotification, object: window, queue: .main
            ) { [weak self] _ in self?.reportScreenHeight() })
            observers.append(center.addObserver(
                forName: NSApplication.didChangeScreenParametersNotification, object: nil, queue: .main
            ) { [weak self] _ in self?.reportScreenHeight() })
        }

        private func installMouseMonitor() {
            guard mouseMonitor == nil else { return }
            mouseMonitor = NSEvent.addLocalMonitorForEvents(matching: [.otherMouseDown, .otherMouseUp]) { [weak self] event in
                guard let self, let window = self.window, window.isVisible else { return event }
                guard event.buttonNumber == 3 || event.buttonNumber == 4 else { return event }
                let insideWindow = event.window === window || window.frame.contains(NSEvent.mouseLocation)
                guard insideWindow else { return event }
                if event.type == .otherMouseDown {
                    if event.buttonNumber == 3 {
                        DispatchQueue.main.async { [weak self] in self?.onBack?() }
                    } else if event.buttonNumber == 4 {
                        DispatchQueue.main.async { [weak self] in self?.onForward?() }
                    }
                }
                return nil
            }
        }

        func reportScreenHeight() {
            let screen = window?.screen ?? NSScreen.main
            let height = screen?.visibleFrame.height ?? 800
            DispatchQueue.main.async { [weak self] in
                self?.onScreenHeight?(height)
            }
        }

        func teardown() {
            for observer in observers { NotificationCenter.default.removeObserver(observer) }
            observers.removeAll()
            if let mouseMonitor {
                NSEvent.removeMonitor(mouseMonitor)
                self.mouseMonitor = nil
            }
        }

        deinit { teardown() }
    }

    func makeNSView(context: Context) -> Probe {
        let probe = Probe()
        probe.onScreenHeight = onScreenHeight
        probe.onBack = onBack
        probe.onForward = onForward
        return probe
    }

    func updateNSView(_ probe: Probe, context: Context) {
        probe.onScreenHeight = onScreenHeight
        probe.onBack = onBack
        probe.onForward = onForward
    }

    static func dismantleNSView(_ probe: Probe, coordinator: ()) {
        probe.teardown()
    }
}

// MARK: - Animated Popout Window Height

/// Animates the popout's intrinsic height smoothly on every frame so AppKit's
/// MenuBarExtra window grows and shrinks smoothly anchored to its top edge.
/// When `height` is `nil` (during an in-tab `RevealLayout` expand/collapse),
/// the window follows the disclosure layout directly on the exact same frame.
struct AnimatedPopoutHeight: ViewModifier, Animatable {
    var height: CGFloat?
    var maxHeight: CGFloat

    var animatableData: CGFloat {
        get { height ?? 0 }
        set { if height != nil { height = newValue } }
    }

    func body(content: Content) -> some View {
        content
            .frame(width: 400, height: height, alignment: .top)
            .frame(maxHeight: maxHeight, alignment: .top)
            .clipped()
    }
}

// MARK: - Downward Disclosure Reveal

/// Reveals a single child vertically from top to bottom as `progress` animates
/// `0 -> 1`, without shifting the child above its top edge or squashing text.
private struct RevealLayout: Layout {
    var progress: CGFloat

    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let child = subviews.first else { return .zero }
        let full = child.sizeThatFits(ProposedViewSize(width: proposal.width, height: nil))
        return CGSize(width: full.width, height: full.height * progress)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let child = subviews.first else { return }
        let full = child.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
        child.place(
            at: CGPoint(x: bounds.minX, y: bounds.minY),
            anchor: .topLeading,
            proposal: ProposedViewSize(width: bounds.width, height: full.height)
        )
    }
}

private struct VerticalRevealModifier: ViewModifier, Animatable {
    var progress: CGFloat

    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    func body(content: Content) -> some View {
        let opacity = Double(min(1, max(0, (progress - 0.08) / 0.92)))
        RevealLayout(progress: progress) {
            content.opacity(opacity)
        }
        .clipped()
    }
}

extension AnyTransition {
    static var verticalReveal: AnyTransition {
        .modifier(
            active: VerticalRevealModifier(progress: 0),
            identity: VerticalRevealModifier(progress: 1)
        )
    }
}
