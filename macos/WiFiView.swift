// The popout's Wi-Fi tab: the reliable networks the Mac leaves a hotspot for
// and the phone's hotspot it joins (WiFiNetworks.swift, used by
// WiFiSwitch.swift). Each list adds the current network, a nearby or known
// one, or a typed name, and removes entries; the phone's hotspot can keep a
// password in the keychain for when macOS can't join it on its own.

import SwiftUI

struct WiFiView: View {
    @ObservedObject var hotspot: Hotspot
    @ObservedObject var wifi: WiFiSwitch
    @ObservedObject var networks: WiFiNetworks

    @State private var addingReliable = false
    @State private var typed = ""
    @State private var addingHotspot = false
    @State private var typedHotspot = ""
    @State private var known: [String] = []
    @State private var passwordFor: String?
    @State private var password = ""
    @State private var savedPasswords: Set<String> = []

    private var hotspotConfigured: Bool { hotspot.keyFile != nil }

    private var canAskForHotspot: Bool {
        hotspotConfigured && hotspot.advertising == nil && !wifi.joining && wifi.state == .away && !wifi.current.phoneHotspot
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            currentRow
            if let problem = wifi.problem {
                Label(problem, systemImage: "location.slash")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !hotspotConfigured {
                Text("The phone's hotspot isn't set up for this Mac (no hotspot key), so Prism doesn't switch networks; the lists are kept for when it is.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Divider()
            reliableSection
            Divider()
            hotspotSection
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .onAppear {
            wifi.refreshCurrent()
            known = wifi.knownNetworks()
            savedPasswords = Set(networks.hotspots.filter(HotspotPassword.has))
        }
    }

    // MARK: - Now

    private var currentRow: some View {
        HStack(spacing: 8) {
            Image(systemName: wifi.current.hotspot ? "personalhotspot" : wifi.current.onWiFi ? "wifi" : "wifi.slash")
                .foregroundStyle(.secondary)
                .frame(width: 18)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                Text(currentTitle).font(.callout.weight(.medium)).lineLimit(1).truncationMode(.middle)
                Text(currentDetail).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            Spacer(minLength: 4)
            if canAskForHotspot {
                Button {
                    wifi.askedFromMenu()
                } label: {
                    Label("Ask phone", systemImage: "personalhotspot")
                }
                .buttonStyle(PillStyle(width: nil, compact: true))
                .accessibilityLabel("Ask the phone for its hotspot")
                .help("Advertise over Bluetooth for 3 minutes; the phone starts its hotspot, and this Mac joins it")
            }
        }
    }

    private var currentTitle: String {
        guard wifi.current.onWiFi else { return "Not on Wi-Fi" }
        return wifi.current.ssid ?? "On Wi-Fi (name unknown)"
    }

    private var currentDetail: String {
        if let error = wifi.joinError, wifi.joining { return error }
        if wifi.joining { return "Looking for the phone's hotspot to join it…" }
        switch wifi.state {
        case .leaving(let ssid): return "Joining \(ssid)…"
        case .switching(let ssid): return "Releasing the phone's hotspot for \(ssid)…"
        case .checking(let ssid): return "Checking the internet on \(ssid)…"
        case .onHotspot, .away: break
        }
        guard wifi.current.onWiFi else { return "Prism joins the phone's hotspot when the Mac is offline." }
        if wifi.current.phoneHotspot { return "The phone's hotspot · leaves it for a reliable network in range" }
        if wifi.current.hotspot { return "A hotspot (metered Wi-Fi) · leaves it for a reliable network in range" }
        if let ssid = wifi.current.ssid, networks.reliable[ssid] != nil { return "A reliable network" }
        return "Not in the lists"
    }

    // MARK: - Reliable networks

    private var reliableSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                SectionLabel("Reliable networks")
                    .help("The Mac leaves a hotspot for one of these when it is in range at -70 dBm or better.")
                if !networks.list.isEmpty {
                    Text("\(networks.list.count)")
                        .font(.caption2.weight(.semibold).monospacedDigit())
                        .foregroundStyle(.tertiary)
                }
                Spacer(minLength: 4)
                addMenu(
                    label: "Add",
                    accessibilityLabel: "Add reliable network",
                    exclude: Set(networks.reliable.keys),
                    onManual: { addingReliable = true },
                    add: { networks.addReliable($0) }
                )
            }
            if networks.list.isEmpty {
                Text("None yet. Add the networks that have working internet.")
                    .font(.callout)
                    .foregroundStyle(.tertiary)
                    .padding(.vertical, 4)
            } else {
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(networks.list) { network in
                            reliableRow(network)
                        }
                    }
                }
                .scrollBounceBehavior(.basedOnSize)
                .frame(maxHeight: 200)
                .fixedSize(horizontal: false, vertical: true)
            }
            if addingReliable {
                HStack(spacing: 6) {
                    TextField("Network name", text: $typed)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit(addTyped)
                    Button("Add", action: addTyped)
                        .disabled(WiFiNetworks.clean(typed) == nil)
                    Button {
                        typed = ""
                        addingReliable = false
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Cancel adding network")
                }
            }
            Toggle("Add networks with working internet automatically", isOn: $networks.learns)
                .font(.caption)
                .toggleStyle(.checkbox)
                .help("A network on which Prism saw working internet joins this list, unless you removed it before.")
        }
    }

    private func reliableRow(_ network: WiFiNetworks.Reliable) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "wifi")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .frame(width: 16)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                Text(network.ssid).font(.callout).lineLimit(1).truncationMode(.middle)
                Text(detail(network)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 4)
            if let rssi = wifi.nearby[network.ssid] {
                Text("\(rssi) dBm").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
            Button("Remove \(network.ssid)", systemImage: "minus.circle") { networks.removeReliable(network.ssid) }
                .labelStyle(.iconOnly)
                .buttonStyle(.borderless)
                .accessibilityLabel("Remove \(network.ssid)")
                .help("Remove; Prism won't add it again on its own")
        }
        .padding(.vertical, 4)
        .contextMenu {
            Button("This is the phone's hotspot") { networks.addHotspot(network.ssid) }
            Divider()
            Button("Remove", role: .destructive) { networks.removeReliable(network.ssid) }
        }
    }

    private func detail(_ network: WiFiNetworks.Reliable) -> String {
        var parts: [String] = []
        if let worked = network.worked {
            parts.append("worked \(worked.formatted(.relative(presentation: .named)))")
        } else {
            parts.append("not seen working yet")
        }
        parts.append(network.addedByUser ? "added by you" : "learned")
        return parts.joined(separator: " · ")
    }

    private func addTyped() {
        guard let ssid = WiFiNetworks.clean(typed) else { return }
        networks.addReliable(ssid)
        typed = ""
        addingReliable = false
    }

    // MARK: - The phone's hotspot

    private var hotspotSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                SectionLabel("The phone's hotspot")
                    .help("Prism joins one of these itself after asking the phone, and never treats them as reliable networks.")
                if !networks.hotspots.isEmpty {
                    Text("\(networks.hotspots.count)")
                        .font(.caption2.weight(.semibold).monospacedDigit())
                        .foregroundStyle(.tertiary)
                }
                Spacer(minLength: 4)
                addMenu(
                    label: "Add",
                    accessibilityLabel: "Add phone hotspot",
                    exclude: Set(networks.hotspots),
                    onManual: { addingHotspot = true },
                    add: { networks.addHotspot($0) }
                )
            }
            if networks.hotspots.isEmpty {
                Text("Not known yet. Add its name, or join it once: Prism recognises an Android hotspot.")
                    .font(.callout)
                    .foregroundStyle(.tertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.vertical, 4)
            }
            ForEach(networks.hotspots, id: \.self) { ssid in
                hotspotRow(ssid)
            }
            if addingHotspot {
                HStack(spacing: 6) {
                    TextField("Hotspot name", text: $typedHotspot)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit(addTypedHotspot)
                    Button("Add", action: addTypedHotspot)
                        .disabled(WiFiNetworks.clean(typedHotspot) == nil)
                    Button {
                        typedHotspot = ""
                        addingHotspot = false
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Cancel adding hotspot")
                }
            }
        }
    }

    private func hotspotRow(_ ssid: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: "personalhotspot")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .frame(width: 16)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 1) {
                    Text(ssid).font(.callout).lineLimit(1).truncationMode(.middle)
                    Text(savedPasswords.contains(ssid) ? "Password saved in Prism" : "Joined with the password macOS knows")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 4)
                if let rssi = wifi.nearby[ssid] {
                    Text("\(rssi) dBm").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                }
                Button("Password for \(ssid)", systemImage: "key") {
                    password = ""
                    passwordFor = passwordFor == ssid ? nil : ssid
                }
                .labelStyle(.iconOnly)
                .buttonStyle(.borderless)
                .accessibilityLabel("Password for \(ssid)")
                .help("Save the hotspot's password in Prism, for when macOS can't join it on its own")
                Button("Remove \(ssid)", systemImage: "minus.circle") {
                    networks.removeHotspot(ssid)
                    savedPasswords.remove(ssid)
                }
                .labelStyle(.iconOnly)
                .buttonStyle(.borderless)
                .accessibilityLabel("Remove \(ssid)")
                .help("Remove")
            }
            if passwordFor == ssid {
                HStack(spacing: 6) {
                    SecureField("Password", text: $password)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit { savePassword(ssid) }
                    Button("Save") { savePassword(ssid) }
                        .disabled(password.isEmpty)
                    if savedPasswords.contains(ssid) {
                        Button("Forget") {
                            HotspotPassword.set(nil, for: ssid)
                            savedPasswords.remove(ssid)
                            passwordFor = nil
                        }
                    }
                }
                .padding(.leading, 24)
            }
        }
        .padding(.vertical, 2)
    }

    private func savePassword(_ ssid: String) {
        guard !password.isEmpty else { return }
        if HotspotPassword.set(password, for: ssid) { savedPasswords.insert(ssid) }
        password = ""
        passwordFor = nil
    }

    private func addTypedHotspot() {
        guard let ssid = WiFiNetworks.clean(typedHotspot) else { return }
        networks.addHotspot(ssid)
        typedHotspot = ""
        addingHotspot = false
    }

    // MARK: - Shared

    /// A single "Add" pull-down offering the current network, nearby ones
    /// (after a scan), networks macOS knows, or typing a name manually.
    private func addMenu(
        label: String,
        accessibilityLabel: String,
        exclude: Set<String>,
        onManual: @escaping () -> Void,
        add: @escaping (String) -> Void
    ) -> some View {
        let nearby = wifi.nearby.sorted { ($0.value, $1.key) > ($1.value, $0.key) }.map(\.key).filter { !exclude.contains($0) }
        let known = known.filter { !exclude.contains($0) && !nearby.contains($0) }
        return Menu {
            if let ssid = wifi.current.ssid, !exclude.contains(ssid) {
                Button("Current network · \(ssid)") { add(ssid) }
            }
            Section("Nearby") {
                ForEach(nearby, id: \.self) { ssid in
                    Button("\(ssid) · \(wifi.nearby[ssid] ?? 0) dBm") { add(ssid) }
                }
                Button(wifi.scanningNearby ? "Scanning…" : "Scan for networks") { wifi.scanNearby() }
                    .disabled(wifi.scanningNearby)
            }
            if !known.isEmpty {
                Section("Known to this Mac") {
                    ForEach(known, id: \.self) { ssid in
                        Button(ssid) { add(ssid) }
                    }
                }
            }
            Divider()
            Button("Enter name…", action: onManual)
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "plus")
                    .font(.system(size: 9, weight: .semibold))
                Text(label)
                    .font(.caption.weight(.medium))
                Image(systemName: "chevron.down")
                    .font(.system(size: 7, weight: .bold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 8)
            .frame(height: 20)
            .background(Capsule().fill(.quaternary))
            .overlay(Capsule().strokeBorder(.separator, lineWidth: 1))
            .contentShape(Capsule())
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .menuIndicator(.hidden)
        .fixedSize()
        .accessibilityLabel(accessibilityLabel)
        .help("Add a current, nearby, known, or typed network")
    }
}
