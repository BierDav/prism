import PrismShared
import SwiftUI

struct ProfilesView: View {
    @ObservedObject var store: PrismStore
    @State private var draft: Draft?
    @State private var confirmingDelete = false
    @State private var limitDraft: LimitDraft?

    private struct Draft {
        var original: PlacementProfile?
        var label = ""
        var entries: [PlacementEntry] = []
    }

    /// A temporary account limit being added or edited, as the shares to keep.
    private struct LimitDraft {
        var adding: Bool
        var account = ""
        var sessionKept = ""
        var weeklyKept = ""
        var hasEnd = false
        var end = Date().addingTimeInterval(24 * 3600)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let error = store.profilesError {
                HStack(alignment: .top, spacing: 6) {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.caption)
                        .foregroundStyle(.orange)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 4)
                    Button { store.profilesError = nil } label: {
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
                .padding(.vertical, 8)
                Divider()
            }
            if let placement = store.placement {
                if draft != nil {
                    editor(placement)
                } else if limitDraft != nil {
                    limitEditor(placement)
                } else {
                    profileList(placement)
                    Divider()
                    accountLimits(placement)
                }
            } else {
                unavailableState
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .disabled(store.savingProfiles)
    }

    @ViewBuilder
    private var unavailableState: some View {
        switch store.connection {
        case .notConfigured:
            EmptyState(
                symbol: "square.stack.3d.up",
                title: "Not connected to Prism",
                detail: "fleet sets this Mac's Rocky provisioning in its Prism configuration."
            )
        case .connecting:
            EmptyState(title: "Loading profiles…", detail: nil, loading: true)
        case .connected where store.profilesError == nil:
            EmptyState(title: "Loading profiles…", detail: nil, loading: true)
        case .failed(let text):
            VStack(spacing: 10) {
                EmptyState(symbol: "exclamationmark.icloud", title: "Profiles unavailable", detail: text, tint: .orange)
                Button { store.refresh() } label: {
                    Label("Retry", systemImage: "arrow.clockwise")
                }
                .buttonStyle(PillStyle(width: nil, compact: true))
                .padding(.bottom, 16)
            }
            .frame(maxWidth: .infinity)
        default:
            VStack(spacing: 10) {
                EmptyState(
                    symbol: "exclamationmark.icloud",
                    title: "Profiles unavailable",
                    detail: store.profilesError ?? "Could not load placement profiles from Prism's replica.",
                    tint: .orange
                )
                Button {
                    store.profilesError = nil
                    store.refresh()
                } label: {
                    Label("Retry", systemImage: "arrow.clockwise")
                }
                .buttonStyle(PillStyle(width: nil, compact: true))
                .padding(.bottom, 16)
            }
            .frame(maxWidth: .infinity)
        }
    }

    private func sectionHeader(_ title: String, count: Int, saving: Bool = false) -> some View {
        HStack(spacing: 6) {
            SectionLabel(title)
            if count > 0 {
                Text("\(count)")
                    .font(.caption2.weight(.semibold).monospacedDigit())
                    .foregroundStyle(.tertiary)
            }
            Spacer(minLength: 4)
            if saving {
                ProgressView()
                    .controlSize(.mini)
                    .accessibilityLabel("Saving")
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 10)
        .padding(.bottom, 4)
    }

    private func profileList(_ placement: Placement) -> some View {
        let profiles = placement.profiles
        return VStack(alignment: .leading, spacing: 0) {
            sectionHeader("Profiles", count: profiles.count, saving: store.savingProfiles)
            if profiles.isEmpty {
                Text("No profiles yet.")
                    .font(.callout)
                    .foregroundStyle(.tertiary)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
            } else {
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(profiles.indices, id: \.self) { index in
                            let profile = profiles[index]
                            ProfileRow(
                                title: profile.displayLabel,
                                subtitle: entryCount(profile),
                                canMoveUp: index > 0,
                                canMoveDown: index < profiles.count - 1,
                                open: { draft = Draft(original: profile, label: profile.displayLabel, entries: profile.configuredEntries) },
                                moveUp: { reorder(profiles, index, index - 1) },
                                moveDown: { reorder(profiles, index, index + 1) }
                            )
                        }
                    }
                }
                .scrollBounceBehavior(.basedOnSize)
                .frame(maxHeight: 260)
                .fixedSize(horizontal: false, vertical: true)
            }
            HStack {
                Button { draft = Draft() } label: {
                    Label("New Profile", systemImage: "plus")
                }
                .buttonStyle(PillStyle(width: nil, compact: true))
                Spacer()
            }
            .padding(.horizontal, 14)
            .padding(.top, 8)
            .padding(.bottom, 12)
        }
    }

    private func editorHeader(_ title: String, back: @escaping () -> Void) -> some View {
        HStack(spacing: 8) {
            Button(action: back) {
                HStack(spacing: 3) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 11, weight: .semibold))
                    Text("Back")
                        .font(.callout.weight(.medium))
                }
                .foregroundStyle(.secondary)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Back to profiles")
            Text(title)
                .font(.callout.weight(.semibold))
                .lineLimit(1)
                .truncationMode(.tail)
            Spacer()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    private func editor(_ placement: Placement) -> some View {
        let indices = (draft?.entries ?? []).enumerated().compactMap { index, entry in
            isVisible(entry, in: placement.models) ? index : nil
        }
        let headerTitle = draft?.original.map { "Edit \($0.displayLabel)" } ?? "New Profile"
        return VStack(alignment: .leading, spacing: 0) {
            editorHeader(headerTitle) { draft = nil }
            Divider()
            VStack(alignment: .leading, spacing: 10) {
                TextField("Profile name", text: Binding(
                    get: { draft?.label ?? "" }, set: { draft?.label = $0 }
                ))
                .textFieldStyle(.roundedBorder)
                ScrollView {
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(indices.indices, id: \.self) { position in
                            entryRow(position, indices: indices, placement: placement)
                            Divider()
                        }
                    }
                }
                .scrollBounceBehavior(.basedOnSize)
                .frame(maxHeight: 260)
                .fixedSize(horizontal: false, vertical: true)
                let models = placement.models.filter { !$0.hidden && $0.instanceId != PlacementEntry.companion.PROFILE_INSTANCE }
                // Profiles it may use: not itself, and none that uses it (the server refuses cycles).
                let profiles = placement.referenceCandidates(profileId: draft?.original?.id ?? "", held: draft?.entries ?? [])
                Menu {
                    Section("Models") {
                        ForEach(Array(models.enumerated()), id: \.offset) { _, model in
                            Button("\(model.displayLabel) · \(model.displayProvider)") {
                                draft?.entries.append(PlacementEntry.companion.of(model: model))
                            }
                            .disabled(draft?.entries.contains { $0.instanceId == model.instanceId && $0.model == model.model } == true)
                        }
                    }
                    if !profiles.isEmpty {
                        Section("Profiles") {
                            ForEach(profiles, id: \.id) { profile in
                                Button("Profile · \(profile.displayLabel)", systemImage: "square.stack.3d.up") {
                                    draft?.entries.append(PlacementEntry.companion.reference(profile: profile, enabled: true))
                                }
                            }
                        }
                    }
                } label: {
                    Label(profiles.isEmpty ? "Add Model" : "Add Entry", systemImage: "plus")
                        .modifier(PillShape(width: nil, compact: true))
                }
                .menuStyle(.button)
                .buttonStyle(.plain)
                .menuIndicator(.hidden)
                .fixedSize()
                .disabled(models.isEmpty && profiles.isEmpty)
                HStack {
                    if let id = draft?.original?.id, !placement.profileUsedBy(profileId: id).isEmpty {
                        Text("Used by \(placement.profileUsedBy(profileId: id).joined(separator: ", ")), so it can't be deleted.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    } else if let id = draft?.original?.id {
                        Button("Delete…", role: .destructive) { confirmingDelete = true }
                            .confirmationDialog("Delete this profile?", isPresented: $confirmingDelete, titleVisibility: .visible) {
                                Button("Delete Profile", role: .destructive) {
                                    Task {
                                        if await store.changeProfiles({ try await $0.deletePlacementProfile(id: id) }) { draft = nil }
                                    }
                                }
                            }
                    }
                    Spacer()
                    if store.savingProfiles {
                        ProgressView()
                            .controlSize(.mini)
                            .accessibilityLabel("Saving")
                    }
                    Button("Cancel") { draft = nil }
                        .keyboardShortcut(.cancelAction)
                    Button("Save") { save() }
                        .keyboardShortcut(.defaultAction)
                        .disabled(draft?.label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty != false)
                }
            }
            .padding(14)
        }
    }

    private func isVisible(_ entry: PlacementEntry, in models: [PlacementModel]) -> Bool {
        !models.contains { $0.hidden && $0.instanceId == entry.instanceId && $0.model == entry.model }
    }

    @ViewBuilder
    private func entryRow(_ position: Int, indices: [Int], placement: Placement) -> some View {
        let index = indices[position]
        if let draft, draft.entries.indices.contains(index) {
            let entry = draft.entries[index]
            let levels = entry.isProfileReference ? [] : placement.models.first {
                $0.instanceId == entry.instanceId && $0.model == entry.model
            }?.reasoning?.levels ?? []
            VStack(alignment: .leading, spacing: 5) {
                HStack {
                    Toggle(isOn: Binding(get: { entry.enabled }, set: { enabled in
                        update(index, enabled: enabled)
                    })) {
                        if entry.isProfileReference {
                            // Another profile, used whole: its enabled models rank here, in its order.
                            Label {
                                VStack(alignment: .leading) {
                                    Text("Profile · \(placement.profiles.first { $0.id == entry.model }?.displayLabel ?? entry.displayLabel)")
                                    if !entry.reason.isEmpty {
                                        Text(entry.reason).font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                            } icon: {
                                Image(systemName: "square.stack.3d.up").foregroundStyle(.tint)
                            }
                        } else {
                            VStack(alignment: .leading) {
                                Text(entry.displayLabel)
                                Text(entry.displayProvider).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    Spacer()
                    moveButton(up: true, disabled: position == 0) {
                        self.draft?.entries.swapAt(index, indices[position - 1])
                    }
                    moveButton(up: false, disabled: position == indices.count - 1) {
                        self.draft?.entries.swapAt(index, indices[position + 1])
                    }
                    Button(entry.isProfileReference ? "Remove profile" : "Remove model", systemImage: "minus.circle") {
                        self.draft?.entries.remove(at: index)
                    }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
                }
                if !entry.isProfileReference && (!levels.isEmpty || !entry.reasoning.isEmpty) {
                    Picker("Reasoning", selection: Binding(get: { entry.reasoning }, set: { update(index, reasoning: $0) })) {
                        Text("Default").tag("")
                        if !entry.reasoning.isEmpty && !levels.contains(entry.reasoning) {
                            Text(entry.reasoning).tag(entry.reasoning)
                        }
                        ForEach(levels.filter { !$0.isEmpty }, id: \.self) { Text($0).tag($0) }
                    }
                }
            }
        }
    }

    private func entryCount(_ profile: PlacementProfile) -> String {
        let entries = profile.configuredEntries
        let profiles = entries.filter(\.isProfileReference).count
        let models = entries.count - profiles
        var parts = ["\(models) \(models == 1 ? "model" : "models")"]
        if profiles > 0 { parts.append("\(profiles) \(profiles == 1 ? "profile" : "profiles")") }
        return parts.joined(separator: ", ")
    }

    // MARK: Account limits

    private var now: Int64 { Int64(Date().timeIntervalSince1970) }

    private func accountLimits(_ placement: Placement) -> some View {
        let limits = placement.policy.accountLimits.sorted { $0.key < $1.key }
        let free = placement.accounts.filter { placement.policy.accountLimits[$0] == nil }
        return VStack(alignment: .leading, spacing: 0) {
            sectionHeader("Account Limits", count: limits.count)
            if limits.isEmpty {
                Text("No account is held back.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 6)
            }
            ForEach(limits, id: \.key) { item in
                let (account, limit) = (item.key, item.value)
                let ended = limit.ended(now: now)
                AccountLimitRow(
                    account: account,
                    summary: limit.summary,
                    endText: endText(limit, ended: ended),
                    ended: ended,
                    open: {
                        limitDraft = LimitDraft(
                            adding: false, account: account,
                            sessionKept: limit.sessionKept.map { "\($0.intValue)" } ?? "",
                            weeklyKept: limit.weeklyKept.map { "\($0.intValue)" } ?? "",
                            hasEnd: limit.untilEpochSeconds != nil,
                            end: limit.untilEpochSeconds.map { Date(timeIntervalSince1970: TimeInterval($0.int64Value)) }
                                ?? Date().addingTimeInterval(24 * 3600))
                    },
                    remove: {
                        Task { _ = await store.changeProfiles { try await $0.clearAccountLimit(account: account) } }
                    }
                )
            }
            HStack {
                Button {
                    limitDraft = LimitDraft(adding: true, account: free.first ?? "")
                } label: {
                    Label("Add Limit", systemImage: "plus")
                }
                .buttonStyle(PillStyle(width: nil, compact: true))
                .disabled(free.isEmpty)
                .help(free.isEmpty ? "Every account already has a limit" : "Hold back part of an account's quota")
                Spacer()
            }
            .padding(.horizontal, 14)
            .padding(.top, 8)
            .padding(.bottom, 12)
        }
    }

    private func endText(_ limit: PlacementAccountLimit, ended: Bool) -> String {
        guard !limit.until.isEmpty else { return "No end" }
        guard let at = limit.untilEpochSeconds else { return "Until \(limit.until)" }
        let date = Date(timeIntervalSince1970: TimeInterval(at.int64Value))
        return (ended ? "Ended " : "Until ") + date.formatted(.dateTime.weekday().day().month().hour().minute())
    }

    private func keptValue(_ text: String) -> Int? {
        guard let value = Int(text.trimmingCharacters(in: .whitespaces)), (0...99).contains(value) else { return nil }
        return value
    }

    private func limitEditor(_ placement: Placement) -> some View {
        let draft = limitDraft ?? LimitDraft(adding: true)
        let session = keptValue(draft.sessionKept)
        let weekly = keptValue(draft.weeklyKept)
        let invalid = (!draft.sessionKept.isEmpty && session == nil) || (!draft.weeklyKept.isEmpty && weekly == nil)
        let free = placement.accounts.filter { placement.policy.accountLimits[$0] == nil }
        let headerTitle = draft.adding ? "New Account Limit" : "Edit \(draft.account)"
        return VStack(alignment: .leading, spacing: 0) {
            editorHeader(headerTitle) { limitDraft = nil }
            Divider()
            VStack(alignment: .leading, spacing: 10) {
                if draft.adding {
                    Picker("Account", selection: Binding(get: { limitDraft?.account ?? "" }, set: { limitDraft?.account = $0 })) {
                        ForEach(free, id: \.self) { Text($0).tag($0) }
                    }
                }
                Text("Placement counts a window as full once only this share is left, so threads move before then. Empty: no limit on that window.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                LabeledContent("Keep of the 5-hour window") {
                    HStack(spacing: 4) {
                        TextField("None", text: Binding(get: { limitDraft?.sessionKept ?? "" }, set: { limitDraft?.sessionKept = $0 }))
                            .frame(width: 50)
                            .multilineTextAlignment(.trailing)
                        Text("%")
                    }
                }
                LabeledContent("Keep of the week") {
                    HStack(spacing: 4) {
                        TextField("None", text: Binding(get: { limitDraft?.weeklyKept ?? "" }, set: { limitDraft?.weeklyKept = $0 }))
                            .frame(width: 50)
                            .multilineTextAlignment(.trailing)
                        Text("%")
                    }
                }
                if invalid {
                    Label("Enter a share from 0 to 99.", systemImage: "exclamationmark.triangle.fill")
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
                Toggle("Ends", isOn: Binding(get: { limitDraft?.hasEnd ?? false }, set: { limitDraft?.hasEnd = $0 }))
                if draft.hasEnd {
                    DatePicker("Until", selection: Binding(get: { limitDraft?.end ?? Date() }, set: { limitDraft?.end = $0 }),
                               in: Date()...)
                }
                HStack {
                    Spacer()
                    if store.savingProfiles {
                        ProgressView()
                            .controlSize(.mini)
                            .accessibilityLabel("Saving")
                    }
                    Button("Cancel") { limitDraft = nil }
                        .keyboardShortcut(.cancelAction)
                    Button("Save") {
                        let until = draft.hasEnd
                            ? PlacementAccountLimit.companion.formatRfc3339(epoch: Int64(draft.end.timeIntervalSince1970)) : ""
                        let limit = PlacementAccountLimit.companion.keeping(
                            sessionKept: session.map { KotlinInt(int: Int32($0)) },
                            weeklyKept: weekly.map { KotlinInt(int: Int32($0)) },
                            until: until)
                        let account = draft.account
                        Task {
                            if await store.changeProfiles({ try await $0.setAccountLimit(account: account, limit: limit) }) {
                                limitDraft = nil
                            }
                        }
                    }
                    .keyboardShortcut(.defaultAction)
                    .disabled(draft.account.isEmpty || invalid || (session == nil && weekly == nil))
                }
            }
            .padding(14)
        }
    }

    private func moveButton(up: Bool, disabled: Bool, action: @escaping () -> Void) -> some View {
        Button(up ? "Move up" : "Move down", systemImage: up ? "chevron.up" : "chevron.down", action: action)
            .labelStyle(.iconOnly)
            .buttonStyle(.borderless)
            .disabled(disabled)
    }

    private func update(_ index: Int, enabled: Bool? = nil, reasoning: String? = nil) {
        guard let draft, draft.entries.indices.contains(index) else { return }
        let entry = draft.entries[index]
        self.draft?.entries[index] = PlacementEntry(
            instanceId: entry.instanceId, model: entry.model, label: entry.label, driver: entry.driver,
            provider: entry.provider, reasoning: reasoning ?? entry.reasoning, enabled: enabled ?? entry.enabled,
            sessionShareOfWeekly: entry.sessionShareOfWeekly, state: entry.state,
            reason: entry.reason, runningThreads: entry.runningThreads)
    }

    private func reorder(_ profiles: [PlacementProfile], _ index: Int, _ destination: Int) {
        var ids = profiles.map(\.id)
        ids.swapAt(index, destination)
        Task { _ = await store.changeProfiles { try await $0.setPlacementOrder(ids: ids) } }
    }

    private func sameSettings(_ lhs: PlacementEntry, _ rhs: PlacementEntry) -> Bool {
        lhs.instanceId == rhs.instanceId && lhs.model == rhs.model &&
            lhs.enabled == rhs.enabled && lhs.reasoning == rhs.reasoning &&
            lhs.sessionShareOfWeekly == rhs.sessionShareOfWeekly
    }

    private struct ProfileChanged: LocalizedError {
        var errorDescription: String? {
            "This profile changed on another device. Cancel and reopen it to edit the latest version."
        }
    }

    private func save() {
        guard let draft else { return }
        let label = draft.label.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !label.isEmpty else { return }
        let id = draft.original?.id ?? UUID().uuidString.lowercased()
        Task {
            if await store.changeProfiles({ replica in
                if let original = draft.original {
                    let latest = try await replica.placement()
                    guard let current = latest.profiles.first(where: { $0.id == id }),
                          current.label == original.label,
                          current.configuredEntries.count == original.configuredEntries.count,
                          zip(current.configuredEntries, original.configuredEntries).allSatisfy({ sameSettings($0.0, $0.1) })
                    else { throw ProfileChanged() }
                }
                return try await replica.savePlacementProfile(id: id, label: label, entries: draft.entries)
            }) {
                self.draft = nil
            }
        }
    }
}

private struct ProfileRow: View {
    let title: String
    let subtitle: String
    let canMoveUp: Bool
    let canMoveDown: Bool
    let open: () -> Void
    let moveUp: () -> Void
    let moveDown: () -> Void
    @State private var hovering = false

    var body: some View {
        HStack(spacing: 8) {
            Button(action: open) {
                HStack(spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.callout.weight(.medium))
                            .lineLimit(1)
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 4)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Edit profile \(title)")
            .help("Edit \(title)")
            Divider()
                .frame(height: 14)
            HStack(spacing: 4) {
                Button("Move \(title) up", systemImage: "chevron.up", action: moveUp)
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
                    .disabled(!canMoveUp)
                    .help("Move up")
                Button("Move \(title) down", systemImage: "chevron.down", action: moveDown)
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
                    .disabled(!canMoveDown)
                    .help("Move down")
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 7)
        .background(hovering ? Color.primary.opacity(0.05) : .clear)
        .onHover { hovering = $0 }
        .contextMenu {
            Button("Edit \(title)", action: open)
            Divider()
            Button("Move Up", action: moveUp).disabled(!canMoveUp)
            Button("Move Down", action: moveDown).disabled(!canMoveDown)
        }
    }
}

private struct AccountLimitRow: View {
    let account: String
    let summary: String
    let endText: String
    let ended: Bool
    let open: () -> Void
    let remove: () -> Void
    @State private var hovering = false

    var body: some View {
        HStack(spacing: 8) {
            Button(action: open) {
                HStack(spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(account)
                            .font(.callout.weight(.medium))
                            .lineLimit(1)
                        Text(summary)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(endText)
                            .font(.caption)
                            .foregroundStyle(ended ? AnyShapeStyle(Color.orange) : AnyShapeStyle(HierarchicalShapeStyle.secondary))
                    }
                    .opacity(ended ? 0.65 : 1)
                    Spacer(minLength: 4)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(.tertiary)
                        .accessibilityHidden(true)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Edit limit for \(account)")
            .help("Edit limit for \(account)")
            Button("Remove limit for \(account)", systemImage: "minus.circle", action: remove)
                .labelStyle(.iconOnly)
                .buttonStyle(.borderless)
                .help("Remove limit")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 7)
        .background(hovering ? Color.primary.opacity(0.05) : .clear)
        .onHover { hovering = $0 }
    }
}
