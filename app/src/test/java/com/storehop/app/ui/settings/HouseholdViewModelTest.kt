package com.storehop.app.ui.settings

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.storehop.app.data.entity.HouseholdMember
import com.storehop.app.data.repository.HouseholdRepository
import com.storehop.app.data.repository.InviteCode
import com.storehop.app.data.repository.InviteResult
import com.storehop.app.data.util.FakeHouseholdSessionProvider
import com.storehop.app.testing.FakeSessionProvider
import com.storehop.app.testing.MainDispatcherRule
import com.storehop.app.testing.TEST_USER_ID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Pins HouseholdViewModel's event-translation behaviour: the typed
 * [InviteResult] variants from the repo map cleanly onto the
 * one-shot [HouseholdUiEvent] values the screen consumes, and the
 * combined uiState reacts to session/household flips.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HouseholdViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val repository: HouseholdRepository = mockk(relaxed = true)
    private val userSession = FakeSessionProvider(TEST_USER_ID)
    private val householdSession = FakeHouseholdSessionProvider(TEST_USER_ID)

    private fun vm() = HouseholdViewModel(repository, userSession, householdSession)

    @Test fun `uiState reports isPersonalHousehold = true when household equals uid`() = runTest {
        coEvery { repository.observeMembers() } returns flowOf(emptyList())
        val viewModel = vm()
        // stateIn(WhileSubscribed) starts with the initialValue then transitions
        // to the combined value once a subscriber is attached. Drop the initial
        // and read the first real emission via Turbine.
        viewModel.uiState.test {
            // First emission is the initialValue (HouseholdUiState() — all
            // null/empty), second is the combined state we care about.
            skipItems(1)
            val s = awaitItem()
            assertThat(s.currentUid).isEqualTo(TEST_USER_ID)
            assertThat(s.householdId).isEqualTo(TEST_USER_ID)
            assertThat(s.isPersonalHousehold).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `uiState reports isPersonalHousehold = false when household differs from uid`() = runTest {
        householdSession.setHouseholdId("mike-hid")
        coEvery { repository.observeMembers() } returns flowOf(
            listOf(member("mike-uid", "mike-hid", isOwner = true)),
        )
        vm().uiState.test {
            skipItems(1)
            val s = awaitItem()
            assertThat(s.currentUid).isEqualTo(TEST_USER_ID)
            assertThat(s.householdId).isEqualTo("mike-hid")
            assertThat(s.isPersonalHousehold).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `generateInvite surfaces an InviteGenerated event on success`() = runTest {
        coEvery { repository.observeMembers() } returns flowOf(emptyList())
        coEvery { repository.generateInvite() } returns InviteCode("ABCDEFGH", expiresAt = 100L)
        val viewModel = vm()
        viewModel.generateInvite()
        advanceUntilIdle()
        val event = viewModel.uiEvent.value
        assertThat(event).isInstanceOf(HouseholdUiEvent.InviteGenerated::class.java)
        assertThat((event as HouseholdUiEvent.InviteGenerated).invite.token).isEqualTo("ABCDEFGH")
    }

    @Test fun `generateInvite surfaces Failed event on repo exception`() = runTest {
        coEvery { repository.observeMembers() } returns flowOf(emptyList())
        coEvery { repository.generateInvite() } throws RuntimeException("network")
        val viewModel = vm()
        viewModel.generateInvite()
        advanceUntilIdle()
        val event = viewModel.uiEvent.value
        assertThat(event).isInstanceOf(HouseholdUiEvent.Failed::class.java)
        assertThat((event as HouseholdUiEvent.Failed).reason).contains("network")
    }

    @Test fun `acceptInvite rejects a non-8-char token client-side without hitting the repo`() = runTest {
        coEvery { repository.observeMembers() } returns flowOf(emptyList())
        val viewModel = vm()
        viewModel.acceptInvite("SHORT")
        advanceUntilIdle()
        assertThat(viewModel.uiEvent.value).isEqualTo(HouseholdUiEvent.InvalidTokenFormat)
        coVerify(exactly = 0) { repository.acceptInvite(any()) }
    }

    @Test fun `acceptInvite uppercases and strips whitespace before submitting`() = runTest {
        coEvery { repository.observeMembers() } returns flowOf(emptyList())
        coEvery { repository.acceptInvite("ABCDEFGH") } returns InviteResult.Success("mike-hid")
        val viewModel = vm()
        viewModel.acceptInvite("  abcdefgh  ")
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.acceptInvite("ABCDEFGH") }
        val event = viewModel.uiEvent.value
        assertThat(event).isInstanceOf(HouseholdUiEvent.JoinedHousehold::class.java)
        assertThat((event as HouseholdUiEvent.JoinedHousehold).newHouseholdId).isEqualTo("mike-hid")
    }

    @Test fun `acceptInvite maps every typed InviteResult variant to its event`() = runTest {
        coEvery { repository.observeMembers() } returns flowOf(emptyList())

        coEvery { repository.acceptInvite("AAAAAAAA") } returns InviteResult.NotFound
        coEvery { repository.acceptInvite("BBBBBBBB") } returns InviteResult.Expired
        coEvery { repository.acceptInvite("CCCCCCCC") } returns InviteResult.AlreadyUsed
        coEvery { repository.acceptInvite("DDDDDDDD") } returns
            InviteResult.Failed(RuntimeException("perm denied"))

        val viewModel = vm()

        viewModel.acceptInvite("AAAAAAAA"); advanceUntilIdle()
        assertThat(viewModel.uiEvent.value).isEqualTo(HouseholdUiEvent.InviteNotFound)

        viewModel.acknowledgeEvent()
        viewModel.acceptInvite("BBBBBBBB"); advanceUntilIdle()
        assertThat(viewModel.uiEvent.value).isEqualTo(HouseholdUiEvent.InviteExpired)

        viewModel.acknowledgeEvent()
        viewModel.acceptInvite("CCCCCCCC"); advanceUntilIdle()
        assertThat(viewModel.uiEvent.value).isEqualTo(HouseholdUiEvent.InviteAlreadyUsed)

        viewModel.acknowledgeEvent()
        viewModel.acceptInvite("DDDDDDDD"); advanceUntilIdle()
        val failed = viewModel.uiEvent.value
        assertThat(failed).isInstanceOf(HouseholdUiEvent.Failed::class.java)
        assertThat((failed as HouseholdUiEvent.Failed).reason).contains("perm denied")
    }

    @Test fun `leaveHousehold surfaces LeftHousehold event on success`() = runTest {
        coEvery { repository.observeMembers() } returns flowOf(emptyList())
        val viewModel = vm()
        viewModel.leaveHousehold()
        advanceUntilIdle()
        assertThat(viewModel.uiEvent.value).isEqualTo(HouseholdUiEvent.LeftHousehold)
    }

    @Test fun `acknowledgeEvent clears the uiEvent so the screen can rebind`() = runTest {
        coEvery { repository.observeMembers() } returns flowOf(emptyList())
        val viewModel = vm()
        viewModel.acceptInvite("SHORT")
        advanceUntilIdle()
        assertThat(viewModel.uiEvent.value).isNotNull()
        viewModel.acknowledgeEvent()
        assertThat(viewModel.uiEvent.value).isNull()
    }

    private fun member(uid: String, hid: String, isOwner: Boolean) = HouseholdMember(
        uid = uid, householdId = hid, displayName = uid,
        joinedAt = 1L, isOwner = isOwner,
        createdAt = 1L, updatedAt = 1L, deletedAt = null,
    )
}
