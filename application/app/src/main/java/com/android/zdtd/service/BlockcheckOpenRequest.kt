package com.android.zdtd.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Navigation request raised when the user taps the ongoing strategy-scan
 * notification. The activity owns the Intent; the Compose navigation state
 * lives in the UI tree, so the tap is forwarded through this process-scoped
 * signal instead of being read off the Intent twice.
 */
object BlockcheckOpenRequest {
    private val mutableRequests = MutableStateFlow(0L)
    val requests: StateFlow<Long> = mutableRequests.asStateFlow()

    fun raise() {
        mutableRequests.update { it + 1 }
    }
}