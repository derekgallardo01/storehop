import Foundation
import Observation
import SwiftUI

/// v0.7.0 Phase 3b: drives the Settings → Household screen.
///
/// State combines three sources: current uid, current household id, and
/// the live members list. UI events (generate invite, join with code,
/// leave) delegate to `HouseholdRepository` and surface results through
/// transient view-model fields that the SwiftUI view consumes via
/// bindings.
///
/// Mirrors Android's `HouseholdViewModel` 1:1.
@MainActor
@Observable
final class HouseholdViewModel {

    /// The live combined uiState — currentUid, householdId, members list,
    /// and the derived isPersonalHousehold flag.
    var currentUid: String?
    var householdId: String?
    var members: [HouseholdMember] = []
    var isPersonalHousehold: Bool { currentUid != nil && currentUid == householdId }

    /// Form state for the Join card.
    var tokenInput: String = ""
    /// Localized-string key for the join-card inline error. nil = no error.
    var joinErrorKey: String.LocalizationValue?

    /// Pending invite — set to the freshly-generated code so the sheet shows.
    /// Cleared by `dismissPendingInvite()` or after the sheet's Copy / Done.
    var pendingInvite: InviteCode?

    /// Leave-flow alert. The destructive button on the screen flips this
    /// true; the confirm dialog handler flips it back and calls
    /// `confirmLeave()`.
    var showLeaveConfirmation: Bool = false

    /// Failure-alert content. Both invite-network errors and leave errors
    /// surface here as a localized message.
    var failureMessage: String?

    private let repository: any HouseholdRepository
    private let userSession: any UserSessionProvider
    private let householdSession: any HouseholdSessionProvider

    private var uidTask: Task<Void, Never>?
    private var hidTask: Task<Void, Never>?
    private var membersTask: Task<Void, Never>?

    init(
        repository: any HouseholdRepository,
        userSession: any UserSessionProvider,
        householdSession: any HouseholdSessionProvider
    ) {
        self.repository = repository
        self.userSession = userSession
        self.householdSession = householdSession
    }

    func bind() {
        uidTask = Task { [weak self] in
            guard let self else { return }
            for await uid in self.userSession.userIdStream {
                await MainActor.run { self.currentUid = uid }
            }
        }
        hidTask = Task { [weak self] in
            guard let self else { return }
            for await hid in self.householdSession.householdIdStream {
                await MainActor.run { self.householdId = hid }
            }
        }
        membersTask = Task { [weak self] in
            guard let self else { return }
            for await list in self.repository.observeMembers() {
                await MainActor.run { self.members = list }
            }
        }
    }

    func teardown() {
        uidTask?.cancel(); uidTask = nil
        hidTask?.cancel(); hidTask = nil
        membersTask?.cancel(); membersTask = nil
    }

    // MARK: - Invite generate

    func generateInvite() {
        Task { [weak self] in
            guard let self else { return }
            do {
                let code = try await self.repository.generateInvite()
                await MainActor.run { self.pendingInvite = code }
            } catch {
                await MainActor.run {
                    self.failureMessage = error.localizedDescription
                }
            }
        }
    }

    func dismissPendingInvite() {
        pendingInvite = nil
    }

    // MARK: - Invite accept

    /// Client-side 8-char alphanumeric validation, then dispatch to repo.
    func acceptInvite() {
        let normalized = tokenInput
            .uppercased()
            .filter { $0.isLetter || $0.isNumber }
        guard normalized.count == Self.inviteTokenLength else {
            joinErrorKey = "household_error_invalid_token"
            return
        }
        joinErrorKey = nil
        Task { [weak self] in
            guard let self else { return }
            switch await self.repository.acceptInvite(token: normalized) {
            case .success:
                await MainActor.run {
                    self.tokenInput = ""
                    self.joinErrorKey = nil
                }
            case .notFound:
                await MainActor.run { self.joinErrorKey = "household_error_invite_not_found" }
            case .expired:
                await MainActor.run { self.joinErrorKey = "household_error_invite_expired" }
            case .alreadyUsed:
                await MainActor.run { self.joinErrorKey = "household_error_invite_used" }
            case .failed(let reason):
                await MainActor.run { self.failureMessage = reason }
            }
        }
    }

    // MARK: - Leave flow

    func requestLeaveConfirmation() { showLeaveConfirmation = true }
    func cancelLeave() { showLeaveConfirmation = false }

    func confirmLeave() {
        showLeaveConfirmation = false
        Task { [weak self] in
            guard let self else { return }
            do {
                try await self.repository.leaveHousehold()
            } catch {
                await MainActor.run {
                    self.failureMessage = error.localizedDescription
                }
            }
        }
    }

    func acknowledgeFailure() { failureMessage = nil }

    // MARK: - SwiftUI binding helpers

    var pendingInviteBinding: Binding<InviteCode?> {
        Binding(
            get: { self.pendingInvite },
            set: { newValue in
                if newValue == nil { self.pendingInvite = nil }
            }
        )
    }

    var leaveConfirmationBinding: Binding<Bool> {
        Binding(
            get: { self.showLeaveConfirmation },
            set: { self.showLeaveConfirmation = $0 }
        )
    }

    var failedEventBinding: Binding<Bool> {
        Binding(
            get: { self.failureMessage != nil },
            set: { showing in
                if !showing { self.failureMessage = nil }
            }
        )
    }

    private static let inviteTokenLength = 8
}

extension InviteCode: Identifiable {
    /// SwiftUI `sheet(item:)` requires Identifiable. The token already is
    /// unique per invite, so reuse it as the identity.
    var id: String { token }
}
