import Foundation
import PrismShared

final class ServerApprovalChannel: NSObject, ApprovalChannel {
    private let client: PrismClient
    private let refresh: @MainActor () async -> Void

    init(client: PrismClient, refresh: @escaping @MainActor () async -> Void) {
        self.client = client
        self.refresh = refresh
    }

    func answer(approval: Approval, outcome: Outcome, signature: String?, completionHandler: @escaping (Error?) -> Void) {
        guard outcome == .approved || outcome == .denied else { completionHandler(nil); return }
        let answer: Approval
        if outcome == .approved {
            answer = approval
        } else {
            guard let option = approval.options.first(where: { $0.decision == "decline" || $0.decision == "cancel" }) else {
                completionHandler(nil)
                return
            }
            answer = Approvals.shared.choosing(approval: approval, decision: option.decision)
        }
        Task {
            do {
                try await client.answerApproval(approval: answer, signature: signature)
                completionHandler(nil)
            } catch { completionHandler(error) }
            await refresh()
        }
    }
}
