import PrismShared
import SwiftUI

struct ProfilesView: View {
    @ObservedObject var store: PrismStore
    @State private var draft: Draft?
    @State private var confirmingDelete = false

    private struct Draft {
        var original: PlacementProfile?
        var label = ""
        var entries: [PlacementEntry] = []
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let error = store.profilesError {
                Label(error, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.red)
                    .font(.caption)
            }
            if let placement = store.placement {
                if draft != nil {
                    editor(placement)
                } else {
                    profileList(placement)
                }
            } else if store.connection == .connecting || (store.connected && store.profilesError == nil) {
                ProgressView("Loading profiles…")
            } else {
                Text("Profiles are unavailable.").foregroundStyle(.secondary)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .disabled(store.savingProfiles)
    }

    private func profileList(_ placement: Placement) -> some View {
        let profiles = placement.profiles
        return VStack(alignment: .leading, spacing: 8) {
            ScrollView {
                VStack(spacing: 8) {
                    ForEach(profiles.indices, id: \.self) { index in
                        let profile = profiles[index]
                        HStack {
                            Button {
                                draft = Draft(original: profile, label: profile.displayLabel, entries: profile.configuredEntries)
                            } label: {
                                VStack(alignment: .leading) {
                                    Text(profile.displayLabel).fontWeight(.medium)
                                    Text("\(profile.configuredEntries.count) models").font(.caption).foregroundStyle(.secondary)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            moveButton(up: true, disabled: index == 0) { reorder(profiles, index, index - 1) }
                            moveButton(up: false, disabled: index == profiles.count - 1) { reorder(profiles, index, index + 1) }
                        }
                    }
                }
            }
            .frame(maxHeight: 300)
            .fixedSize(horizontal: false, vertical: true)
            Button("New Profile", systemImage: "plus") { draft = Draft() }
        }
    }

    private func editor(_ placement: Placement) -> some View {
        let indices = (draft?.entries ?? []).enumerated().compactMap { index, entry in
            isVisible(entry, in: placement.models) ? index : nil
        }
        return VStack(alignment: .leading, spacing: 8) {
            TextField("Profile name", text: Binding(
                get: { draft?.label ?? "" }, set: { draft?.label = $0 }
            ))
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(indices.indices, id: \.self) { position in
                        entryRow(position, indices: indices, models: placement.models)
                        Divider()
                    }
                }
            }
            .frame(maxHeight: 280)
            .fixedSize(horizontal: false, vertical: true)
            Menu("Add Model") {
                ForEach(Array(placement.models.filter { !$0.hidden }.enumerated()), id: \.offset) { _, model in
                    Button("\(model.displayLabel) · \(model.displayProvider)") {
                        draft?.entries.append(PlacementEntry(
                            instanceId: model.instanceId, model: model.model, label: model.label,
                            driver: model.driver, provider: model.provider, reasoning: "", enabled: true,
                            sessionShareOfWeekly: nil, state: "", reason: "", runningThreads: 0))
                    }
                    .disabled(draft?.entries.contains { $0.instanceId == model.instanceId && $0.model == model.model } == true)
                }
            }
            .disabled(!placement.models.contains { !$0.hidden })
            HStack {
                if let id = draft?.original?.id, id != placement.effectiveAlertFixProfile,
                   id != placement.effectiveTextGenerationProfile,
                   !placement.policy.roleProfiles.values.contains(id) {
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
                Button("Cancel") { draft = nil }
                Button("Save") { save() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(draft?.label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty != false)
            }
        }
    }

    private func isVisible(_ entry: PlacementEntry, in models: [PlacementModel]) -> Bool {
        !models.contains { $0.hidden && $0.instanceId == entry.instanceId && $0.model == entry.model }
    }

    @ViewBuilder
    private func entryRow(_ position: Int, indices: [Int], models: [PlacementModel]) -> some View {
        let index = indices[position]
        if let draft, draft.entries.indices.contains(index) {
            let entry = draft.entries[index]
            let levels = models.first {
                $0.instanceId == entry.instanceId && $0.model == entry.model
            }?.reasoning?.levels ?? []
            VStack(alignment: .leading, spacing: 5) {
                HStack {
                    Toggle(isOn: Binding(get: { entry.enabled }, set: { enabled in
                        update(index, enabled: enabled)
                    })) {
                        VStack(alignment: .leading) {
                            Text(entry.displayLabel)
                            Text(entry.displayProvider).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    moveButton(up: true, disabled: position == 0) {
                        self.draft?.entries.swapAt(index, indices[position - 1])
                    }
                    moveButton(up: false, disabled: position == indices.count - 1) {
                        self.draft?.entries.swapAt(index, indices[position + 1])
                    }
                    Button("Remove model", systemImage: "minus.circle") {
                        self.draft?.entries.remove(at: index)
                    }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderless)
                }
                if !levels.isEmpty || !entry.reasoning.isEmpty {
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
