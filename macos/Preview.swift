// `prism preview [--menu-bar] [--dark|--light] [--approval|--approvals|--machines|--profiles|--wifi] [--expand N]
// [--live --config FILE] [--snapshot PNG]`: the popout in an ordinary window,
// for screenshots (`screencapture -l <id>`; it prints the window id), or with
// --menu-bar as a live menu-bar icon next to the installed app.
// --approvals opens it on the Approvals tab, --approval shows the approval
// dialog instead. --snapshot draws the window itself into PNG after a few
// seconds and quits: no screen recording permission needed, so it works over
// SSH. Without --live it shows sample alerts; with --live it opens this Mac's
// replica like the app (quit the app first: one process opens it). The approvals are always samples: an answer goes
// nowhere, and the stored history is left alone.

import AppKit
import SwiftUI

@MainActor
enum Preview {
    private static var window: NSWindow?

    static func run(_ options: [String]) -> Never {
        let dark = options.contains("--dark")
        let light = options.contains("--light")
        var config = Config(host: Host.current().localizedName ?? "this-mac", relayUrl: "", relayTokenFile: "", rocky: nil)
        var store = PrismStore.sample()
        if options.contains("--live") {
            guard let index = options.firstIndex(of: "--config"), index + 1 < options.count,
                  let loaded = try? Config.load(options[index + 1])
            else { fail("preview --live needs --config FILE") }
            config = Config(host: loaded.host, relayUrl: "", relayTokenFile: "", rocky: loaded.rocky)
            store = PrismStore(
                config: loaded.rocky, device: loaded.host, relayURL: loaded.relayUrl, relayTokenFile: loaded.relayTokenFile)
            store.start(push: false)
        }
        if options.contains("--wifi") {
            UserDefaults.standard.set(PopoutView.Tab.wifi.rawValue, forKey: "popoutTab")
        } else if options.contains("--approvals") {
            UserDefaults.standard.set(PopoutView.Tab.approvals.rawValue, forKey: "popoutTab")
        } else if options.contains("--profiles") {
            UserDefaults.standard.set(PopoutView.Tab.profiles.rawValue, forKey: "popoutTab")
        } else if options.contains("--machines") {
            UserDefaults.standard.set(PopoutView.Tab.machines.rawValue, forKey: "popoutTab")
        } else {
            UserDefaults.standard.set(PopoutView.Tab.alerts.rawValue, forKey: "popoutTab")
        }
        if let index = options.firstIndex(of: "--expand"), index + 1 < options.count {
            AlertsView.initiallyExpanded = Int64(options[index + 1])
        }
        if options.contains("--menu-bar") {
            AppDelegate.previewMode = true
            AppDelegate.approver = Approver.sample(config: config)
            AppDelegate.store = store
            AppDelegate.hotspot = sampleHotspot()
            PrismApp.main()
            exit(0)
        }
        let snapshot = options.firstIndex(of: "--snapshot").flatMap { $0 + 1 < options.count ? options[$0 + 1] : nil }
        let approver = Approver.sample(config: config)
        approver.loadKey()
        approver.listens = snapshot == nil
        approver.watch()

        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)
        if dark { app.appearance = NSAppearance(named: .darkAqua) }
        if light { app.appearance = NSAppearance(named: .aqua) }

        // A drawn snapshot has no window server behind it to blur.
        let background = snapshot == nil ? AnyShapeStyle(.regularMaterial) : AnyShapeStyle(Color(nsColor: .windowBackgroundColor))
        let view = options.contains("--approval")
            ? AnyView(DialogView(approver: approver).background(background))
            : AnyView(PopoutView(approver: approver, store: store, hotspot: sampleHotspot()).background(background))
        let host = NSHostingController(rootView: view)
        host.sizingOptions = [.preferredContentSize]
        host.safeAreaRegions = []
        let window = NSWindow(contentViewController: host)
        window.styleMask = [.titled, .fullSizeContentView, .closable]
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = true
        window.title = "Prism preview"
        for button in [NSWindow.ButtonType.closeButton, .miniaturizeButton, .zoomButton] {
            window.standardWindowButton(button)?.isHidden = true
        }
        window.center()
        window.makeKeyAndOrderFront(nil)
        app.activate()
        self.window = window
        print(window.windowNumber)
        fflush(stdout)
        if let snapshot {
            DispatchQueue.main.asyncAfter(deadline: .now() + 8) {
                guard let view = window.contentView, let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else {
                    fail("cannot draw the window")
                }
                view.cacheDisplay(in: view.bounds, to: rep)
                do {
                    try rep.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: snapshot))
                } catch {
                    fail("cannot write \(snapshot): \(error.localizedDescription)")
                }
                exit(0)
            }
        }
        app.run()
        exit(0)
    }

    /// The Wi-Fi tab's sample: made-up networks in a throwaway defaults
    /// domain, so the preview neither shows nor changes the app's lists.
    private static func sampleHotspot() -> Hotspot {
        let suite = "prism-preview-wifi"
        let defaults = UserDefaults(suiteName: suite) ?? .standard
        defaults.removePersistentDomain(forName: suite)
        let now = Date().timeIntervalSince1970
        defaults.set(["Home": now - 3600, "Office": now - 86400 * 2, "Library": 0.0], forKey: WiFiNetworks.reliableKey)
        defaults.set(["Library"], forKey: WiFiNetworks.addedKey)
        defaults.set(["Phone hotspot"], forKey: WiFiNetworks.hotspotsKey)
        let hotspot = Hotspot(keyFile: "/dev/null", networks: WiFiNetworks(defaults: defaults))
        hotspot.wifi.preview(
            current: WiFiSwitch.Current(ssid: "Phone hotspot", onWiFi: true, phoneHotspot: true, hotspot: true),
            nearby: ["Home": -58, "Phone hotspot": -35, "Cafe": -67])
        return hotspot
    }
}
