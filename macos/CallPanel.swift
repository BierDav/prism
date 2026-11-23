// The call panel (docs/calls.md): a small floating panel in the top right
// corner while the phone has a call. It shows who calls and where the call's
// audio is, with the buttons for the moment: answer or decline while it
// rings; mute, move it between the Mac and the phone, and hang up while it
// goes on. It doesn't take the keyboard or pull Prism forward.
//
// A caller's name and number are the phone's text, shown as plain text
// (visible()).

import AppKit
import SwiftUI

final class CallPanel {
    private let calls: PhoneCalls
    private var panel: NSPanel?

    init(calls: PhoneCalls) {
        self.calls = calls
    }

    /// Shows the panel while there is a call and closes it after.
    @MainActor
    func update() {
        guard calls.call != nil else {
            panel?.orderOut(nil)
            panel = nil
            return
        }
        if panel == nil {
            let panel = NSPanel(
                contentRect: NSRect(x: 0, y: 0, width: 340, height: 120),
                styleMask: [.titled, .fullSizeContentView, .nonactivatingPanel],
                backing: .buffered, defer: false)
            panel.titleVisibility = .hidden
            panel.titlebarAppearsTransparent = true
            panel.isMovableByWindowBackground = true
            panel.level = .floating
            panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
            panel.hidesOnDeactivate = false
            panel.isReleasedWhenClosed = false
            for button in [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton] {
                panel.standardWindowButton(button)?.isHidden = true
            }
            let host = NSHostingController(rootView: CallView(calls: calls))
            host.sizingOptions = [.preferredContentSize]
            host.safeAreaRegions = []
            panel.contentViewController = host
            panel.setContentSize(host.view.fittingSize)
            if let screen = NSScreen.main?.visibleFrame {
                panel.setFrameTopLeftPoint(NSPoint(x: screen.maxX - panel.frame.width - 16, y: screen.maxY - 16))
            }
            self.panel = panel
        }
        panel?.orderFrontRegardless()
    }
}

struct CallView: View {
    @ObservedObject var calls: PhoneCalls

    var body: some View {
        if let call = calls.call {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .center, spacing: 12) {
                    Image(systemName: call.state == .ringing ? "phone.arrow.down.left.fill" : "phone.fill")
                        .font(.system(size: 20))
                        .foregroundStyle(call.state == .ended ? Color.secondary : Color.green)
                        .frame(width: 28)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(visible(call.title))
                            .font(.headline)
                            .lineLimit(1)
                        if call.name != nil, let number = call.number {
                            Text(visible(number))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        status(call)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                }
                if let error = call.error {
                    Text(visible(error))
                        .font(.caption)
                        .foregroundStyle(.red)
                        .fixedSize(horizontal: false, vertical: true)
                }
                buttons(call)
            }
            .padding(16)
            .frame(width: 340)
        }
    }

    @ViewBuilder private func status(_ call: PhoneCalls.Call) -> some View {
        switch call.state {
        case .ringing:
            Text(calls.connected ? "Incoming call" : "Incoming call · reconnecting…")
        case .ended:
            Text("Call ended")
        case .active:
            TimelineView(.periodic(from: .now, by: 1)) { context in
                Text((call.audioOnMac ? "On this Mac" : "On the phone")
                     + (call.since.map { " · " + duration(context.date.timeIntervalSince($0)) } ?? "")
                     + (calls.connected ? "" : " · reconnecting…"))
                    .monospacedDigit()
            }
        }
    }

    @ViewBuilder private func buttons(_ call: PhoneCalls.Call) -> some View {
        switch call.state {
        case .ringing:
            HStack {
                Button(role: .destructive) { calls.decline() } label: {
                    Label("Decline", systemImage: "phone.down.fill").frame(maxWidth: .infinity)
                }
                .tint(.red)
                Button { calls.answer() } label: {
                    Label("Answer", systemImage: "phone.fill").frame(maxWidth: .infinity)
                }
                .tint(.green)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(!calls.connected)
        case .active:
            HStack {
                if call.audioOnMac {
                    Button { calls.muted.toggle() } label: {
                        Label(calls.muted ? "Unmute" : "Mute", systemImage: calls.muted ? "mic.slash.fill" : "mic.fill")
                    }
                    Button { calls.moveToPhone() } label: {
                        Label("To phone", systemImage: "iphone")
                    }
                } else {
                    Button { calls.moveToMac() } label: {
                        Label("Take on Mac", systemImage: "laptopcomputer")
                    }
                }
                Spacer(minLength: 0)
                Button(role: .destructive) { calls.hangUp() } label: {
                    Label("Hang up", systemImage: "phone.down.fill")
                }
                .buttonStyle(.borderedProminent)
                .tint(.red)
            }
            .controlSize(.regular)
            .disabled(!calls.connected)
        case .ended:
            EmptyView()
        }
    }

    private func duration(_ seconds: TimeInterval) -> String {
        let s = max(0, Int(seconds))
        return s >= 3600
            ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            : String(format: "%d:%02d", s / 60, s % 60)
    }
}
