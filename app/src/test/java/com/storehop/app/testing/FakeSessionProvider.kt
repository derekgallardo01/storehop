package com.storehop.app.testing

import com.storehop.app.data.util.UserSessionProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test stand-in for [UserSessionProvider]. Construct with an initial uid;
 * call [setUserId] to simulate a sign-in/sign-out mid-test.
 */
internal class FakeSessionProvider(
    initial: String? = TEST_USER_ID,
    private var displayName: String? = null,
) : UserSessionProvider {
    private val _userId = MutableStateFlow(initial)
    override val userId: StateFlow<String?> = _userId.asStateFlow()
    fun setUserId(uid: String?) { _userId.value = uid }
    override fun currentDisplayName(): String? = displayName
    fun setDisplayName(name: String?) { displayName = name }
}
