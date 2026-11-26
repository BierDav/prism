import AppKit
import PrismShared
import SwiftUI

struct ProfilesView: View {
    @ObservedObject var store: PrismStore
    @Environment(\.popoutNavigation) private var navigation
    @State private var draft: Draft?
    @State private var confirmingDelete = false
    @State private var limitDraft: LimitDraft?
    @State private var draggedProfileID: String?
    @State private var liveProfileOrder: [String]?
    @State private var profileDragOffset: CGFloat = 0
    @State private var profileDragBaseY: CGFloat = 0
    @State private var profileRowHeights: [String: CGFloat] = [:]
    @State private var draggedEntryKey: String?
    @State private var entryDragOffset: CGFloat = 0
    @State private var entryDragBaseY: CGFloat = 0
    @State private var entryRowHeights: [String: CGFloat] = [:]

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
                    PopoutScrollView {
                        VStack(alignment: .leading, spacing: 0) {
                            profileList(placement)
                            Divider()
                            accountLimits(placement)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            } else {
                unavailableState
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .disabled(store.savingProfiles)
        .onAppear {
            navigation?.dismissSubstate = {
                if draft != nil {
                    withAnimation(.snappy(duration: 0.22, extraBounce: 0)) { draft = nil }
                    return true
                }
                if limitDraft != nil {
                    withAnimation(.snappy(duration: 0.22, extraBounce: 0)) { limitDraft = nil }
                    return true
                }
                return false
            }
        }
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

    private func orderedProfiles(from profiles: [PlacementProfile]) -> [PlacementProfile] {
        guard let liveProfileOrder else { return profiles }
        let byID = Dictionary(uniqueKeysWithValues: profiles.map { ($0.id, $0) })
        let ordered = liveProfileOrder.compactMap { byID[$0] }
        let seen = Set(ordered.map(\.id))
        return ordered + profiles.filter { !seen.contains($0.id) }
    }

    private func profileList(_ placement: Placement) -> some View {
        let profiles = orderedProfiles(from: placement.profiles)
        return VStack(alignment: .leading, spacing: 0) {
            sectionHeader("Profiles", count: profiles.count, saving: store.savingProfiles)
            if profiles.isEmpty {
                Text("No profiles yet.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(profiles.enumerated()), id: \.element.id) { index, profile in
                        let dragging = draggedProfileID == profile.id
                        ProfileRow(
                            title: profile.displayLabel,
                            subtitle: entryCount(profile),
                            canMoveUp: index > 0,
                            canMoveDown: index < profiles.count - 1,
                            dragging: dragging,
                            open: { draft = Draft(original: profile, label: profile.displayLabel, entries: profile.configuredEntries) },
                            moveUp: { reorder(profiles, index, index - 1) },
                            moveDown: { reorder(profiles, index, index + 1) },
                            dragGesture: profileDragGesture(for: profile.id, in: placement.profiles)
                        )
                        .background {
                            Color.clear.onGeometryChange(for: CGFloat.self) { $0.size.height } action: { h in
                                if h > 0 { profileRowHeights[profile.id] = h }
                            }
                        }
                        .offset(y: dragging ? profileDragOffset : 0)
                        .zIndex(dragging ? 1 : 0)
                    }
                }
                .coordinateSpace(name: "profileList")
            }
            HStack {
                Button { draft = Draft() } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "plus")
                            .font(.system(size: 9, weight: .semibold))
                        Text("New Profile")
                            .font(.caption.weight(.medium))
                    }
                    .modifier(CapsuleChipShape())
                }
                .buttonStyle(.plain)
                Spacer()
            }
            .padding(.horizontal, 14)
            .padding(.top, 8)
            .padding(.bottom, 12)
        }
    }

    private func profileDragGesture(for id: String, in baseProfiles: [PlacementProfile]) -> some Gesture {
        DragGesture(minimumDistance: 2, coordinateSpace: .named("profileList"))
            .onChanged { value in
                if draggedProfileID == nil {
                    draggedProfileID = id
                    liveProfileOrder = orderedProfiles(from: baseProfiles).map(\.id)
                    profileDragBaseY = value.startLocation.y
                }
                guard draggedProfileID == id, var order = liveProfileOrder,
                      var index = order.firstIndex(of: id) else { return }
                var offset = value.location.y - profileDragBaseY
                while index < order.count - 1 {
                    let nextHeight = profileRowHeights[order[index + 1]] ?? 44
                    guard offset > nextHeight * 0.55 else { break }
                    withAnimation(.snappy(duration: 0.18, extraBounce: 0)) {
                        order.swapAt(index, index + 1)
                        liveProfileOrder = order
                    }
                    profileDragBaseY += nextHeight
                    offset -= nextHeight
                    index += 1
                }
                while index > 0 {
                    let prevHeight = profileRowHeights[order[index - 1]] ?? 44
                    guard offset < -prevHeight * 0.55 else { break }
                    withAnimation(.snappy(duration: 0.18, extraBounce: 0)) {
                        order.swapAt(index, index - 1)
                        liveProfileOrder = order
                    }
                    profileDragBaseY -= prevHeight
                    offset += prevHeight
                    index -= 1
                }
                profileDragOffset = offset
            }
            .onEnded { _ in
                let finalOrder = liveProfileOrder
                let originalOrder = baseProfiles.map(\.id)
                withAnimation(.snappy(duration: 0.18, extraBounce: 0)) {
                    profileDragOffset = 0
                    draggedProfileID = nil
                }
                guard let finalOrder, finalOrder != originalOrder else { return }
                Task {
                    let persisted = await store.changeProfiles { try await $0.setPlacementOrder(ids: finalOrder) }
                    if persisted { liveProfileOrder = nil }
                }
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

    private func entryKey(_ entry: PlacementEntry) -> String {
        "\(entry.instanceId)|\(entry.model)"
    }

    private func editor(_ placement: Placement) -> some View {
        let indices = (draft?.entries ?? []).enumerated().compactMap { index, entry in
            isVisible(entry, in: placement.models) ? index : nil
        }
        let headerTitle = draft?.original.map { "Edit \($0.displayLabel)" } ?? "New Profile"
        return PopoutScrollView {
            VStack(alignment: .leading, spacing: 0) {
                editorHeader(headerTitle) { draft = nil }
                Divider()
                VStack(alignment: .leading, spacing: 10) {
                    TextField("Profile name", text: Binding(
                        get: { draft?.label ?? "" }, set: { draft?.label = $0 }
                    ))
                    .textFieldStyle(.roundedBorder)
                    .font(.callout)
                    if indices.isEmpty {
                        Text("No models or profiles yet. Add one below to set the priority order.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.vertical, 6)
                    } else {
                        VStack(alignment: .leading, spacing: 0) {
                            ForEach(Array(indices.enumerated()), id: \.element) { position, index in
                                if let entries = draft?.entries, entries.indices.contains(index) {
                                    let key = entryKey(entries[index])
                                    let dragging = draggedEntryKey == key
                                    entryRow(position, indices: indices, placement: placement, dragging: dragging, key: key)
                                        .background {
                                            Color.clear.onGeometryChange(for: CGFloat.self) { $0.size.height } action: { h in
                                                if h > 0 { entryRowHeights[key] = h }
                                            }
                                        }
                                        .offset(y: dragging ? entryDragOffset : 0)
                                        .zIndex(dragging ? 1 : 0)
                                }
                            }
                        }
                        .coordinateSpace(name: "profileEntries")
                    }
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
                        HStack(spacing: 4) {
                            Image(systemName: "plus")
                                .font(.system(size: 9, weight: .semibold))
                            Text(profiles.isEmpty ? "Add Model" : "Add Entry")
                                .font(.caption.weight(.medium))
                            Image(systemName: "chevron.down")
                                .font(.system(size: 7, weight: .bold))
                                .foregroundStyle(.tertiary)
                        }
                        .modifier(CapsuleChipShape())
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
                .font(.callout)
                .controlSize(.small)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func isVisible(_ entry: PlacementEntry, in models: [PlacementModel]) -> Bool {
        !models.contains { $0.hidden && $0.instanceId == entry.instanceId && $0.model == entry.model }
    }

    private func entryDragGesture(for key: String, placement: Placement) -> some Gesture {
        DragGesture(minimumDistance: 2, coordinateSpace: .named("profileEntries"))
            .onChanged { value in
                if draggedEntryKey == nil {
                    draggedEntryKey = key
                    entryDragBaseY = value.startLocation.y
                }
                guard draggedEntryKey == key, let entries = draft?.entries else { return }
                let visibleIndices = entries.enumerated().compactMap { idx, entry in
                    isVisible(entry, in: placement.models) ? idx : nil
                }
                guard var pos = visibleIndices.firstIndex(where: { entryKey(entries[$0]) == key }) else { return }
                var offset = value.location.y - entryDragBaseY
                while pos < visibleIndices.count - 1 {
                    let nextEntry = entries[visibleIndices[pos + 1]]
                    let nextHeight = entryRowHeights[entryKey(nextEntry)] ?? 46
                    guard offset > nextHeight * 0.55 else { break }
                    let from = visibleIndices[pos]
                    let to = visibleIndices[pos + 1]
                    withAnimation(.snappy(duration: 0.18, extraBounce: 0)) {
                        self.draft?.entries.swapAt(from, to)
                    }
                    entryDragBaseY += nextHeight
                    offset -= nextHeight
                    pos += 1
                }
                while pos > 0 {
                    let prevEntry = entries[visibleIndices[pos - 1]]
                    let prevHeight = entryRowHeights[entryKey(prevEntry)] ?? 46
                    guard offset < -prevHeight * 0.55 else { break }
                    let from = visibleIndices[pos]
                    let to = visibleIndices[pos - 1]
                    withAnimation(.snappy(duration: 0.18, extraBounce: 0)) {
                        self.draft?.entries.swapAt(from, to)
                    }
                    entryDragBaseY -= prevHeight
                    offset += prevHeight
                    pos -= 1
                }
                entryDragOffset = offset
            }
            .onEnded { _ in
                withAnimation(.snappy(duration: 0.18, extraBounce: 0)) {
                    entryDragOffset = 0
                    draggedEntryKey = nil
                }
            }
    }

    @ViewBuilder
    private func entryRow(_ position: Int, indices: [Int], placement: Placement, dragging: Bool, key: String) -> some View {
        let index = indices[position]
        if let draft, draft.entries.indices.contains(index) {
            let entry = draft.entries[index]
            let levels = entry.isProfileReference ? [] : placement.models.first {
                $0.instanceId == entry.instanceId && $0.model == entry.model
            }?.reasoning?.levels ?? []
            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 8) {
                    DragHandle(dragging: dragging)
                        .gesture(entryDragGesture(for: key, placement: placement))
                        .accessibilityLabel("Reorder \(entry.displayLabel)")
                        .help("Drag to reorder")
                    Toggle(isOn: Binding(get: { entry.enabled }, set: { enabled in
                        update(index, enabled: enabled)
                    })) {
                        if entry.isProfileReference {
                            // Another profile, used whole: its enabled models rank here, in its order.
                            Label {
                                VStack(alignment: .leading, spacing: 1) {
                                    Text("Profile · \(placement.profiles.first { $0.id == entry.model }?.displayLabel ?? entry.displayLabel)")
                                        .font(.callout.weight(.medium))
                                    if !entry.reason.isEmpty {
                                        Text(entry.reason).font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                            } icon: {
                                Image(systemName: "square.stack.3d.up").foregroundStyle(.tint)
                            }
                        } else {
                            VStack(alignment: .leading, spacing: 1) {
                                Text(entry.displayLabel).font(.callout.weight(.medium))
                                Text(entry.displayProvider).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .toggleStyle(.checkbox)
                    Spacer(minLength: 4)
                    Button(entry.isProfileReference ? "Remove profile" : "Remove model", systemImage: "minus.circle") {
                        self.draft?.entries.remove(at: index)
                    }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
                    .help(entry.isProfileReference ? "Remove profile" : "Remove model")
                }
                if !entry.isProfileReference && (!levels.isEmpty || !entry.reasoning.isEmpty) {
                    Picker("Reasoning", selection: Binding(get: { entry.reasoning }, set: { update(index, reasoning: $0) })) {
                        Text("Default").tag("")
                        if !entry.reasoning.isEmpty && !levels.contains(entry.reasoning) {
                            Text(entry.reasoning).tag(entry.reasoning)
                        }
                        ForEach(levels.filter { !$0.isEmpty }, id: \.self) { Text($0).tag($0) }
                    }
                    .padding(.leading, 22)
                }
                Divider()
                    .padding(.top, 3)
            }
            .padding(.vertical, 4)
            .background(
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(dragging ? Color(nsColor: .controlBackgroundColor).opacity(0.95) : .clear)
                    .shadow(color: dragging ? Color.black.opacity(0.18) : .clear, radius: 4, y: 2)
            )
            .contextMenu {
                Button("Move Up") { self.draft?.entries.swapAt(index, indices[position - 1]) }
                    .disabled(position == 0)
                Button("Move Down") { self.draft?.entries.swapAt(index, indices[position + 1]) }
                    .disabled(position == indices.count - 1)
                Divider()
                Button("Remove", role: .destructive) { self.draft?.entries.remove(at: index) }
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
                    HStack(spacing: 4) {
                        Image(systemName: "plus")
                            .font(.system(size: 9, weight: .semibold))
                        Text("Add Limit")
                            .font(.caption.weight(.medium))
                    }
                    .modifier(CapsuleChipShape())
                }
                .buttonStyle(.plain)
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
            .font(.callout)
            .controlSize(.small)
        }
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
        liveProfileOrder = ids
        Task {
            let persisted = await store.changeProfiles { try await $0.setPlacementOrder(ids: ids) }
            if persisted { liveProfileOrder = nil }
        }
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

private struct DragHandle: View {
    let dragging: Bool
    @State private var hovering = false

    var body: some View {
        Image(systemName: "line.3.horizontal")
            .font(.system(size: 11, weight: .medium))
            .foregroundStyle(dragging ? AnyShapeStyle(.primary) : hovering ? AnyShapeStyle(.secondary) : AnyShapeStyle(.tertiary))
            .frame(width: 18, height: 22)
            .contentShape(Rectangle())
            .onHover { over in
                hovering = over
                if !dragging {
                    (over ? NSCursor.openHand : NSCursor.arrow).set()
                }
            }
            .onChange(of: dragging) { _, isDragging in
                (isDragging ? NSCursor.closedHand : hovering ? NSCursor.openHand : NSCursor.arrow).set()
            }
            .onDisappear {
                if hovering || dragging { NSCursor.arrow.set() }
            }
    }
}

private struct ProfileRow<Drag: Gesture>: View {
    let title: String
    let subtitle: String
    let canMoveUp: Bool
    let canMoveDown: Bool
    let dragging: Bool
    let open: () -> Void
    let moveUp: () -> Void
    let moveDown: () -> Void
    let dragGesture: Drag
    @State private var hovering = false

    var body: some View {
        HStack(spacing: 6) {
            DragHandle(dragging: dragging)
                .gesture(dragGesture)
                .accessibilityLabel("Reorder \(title)")
                .help("Drag to reorder")
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
                    Image(systemName: "chevron.right")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(.tertiary)
                        .accessibilityHidden(true)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Edit profile \(title)")
            .help("Edit \(title)")
        }
        .padding(.leading, 10)
        .padding(.trailing, 14)
        .padding(.vertical, 7)
        .background(
            dragging
                ? AnyShapeStyle(Color(nsColor: .controlBackgroundColor).opacity(0.95))
                : hovering ? AnyShapeStyle(Color.primary.opacity(0.05)) : AnyShapeStyle(.clear)
        )
        .shadow(color: dragging ? Color.black.opacity(0.18) : .clear, radius: 5, y: 2)
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
