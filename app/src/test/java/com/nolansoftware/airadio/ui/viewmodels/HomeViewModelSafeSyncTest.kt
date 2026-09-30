// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.ui.viewmodels

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bug #2 regression guard: `RadioRepository.runSync()` re-throws on API
 * failure (`RadioRepository.kt:350`). When `HomeViewModel.retrySync()`
 * launches the call on `viewModelScope` without a try/catch, the
 * `UnknownHostException` propagates to the main thread and crashes the
 * app (`FATAL EXCEPTION: main` in logcat — verified on emulator
 * 192.168.1.184:5555 at 13:34:16.384).
 *
 * Fix: wrap the sync call in `safePerformSync(...)`, a top-level
 * coroutine helper that swallows `Exception`s and logs them. `runSync`
 * already sets `_syncState.value = SyncState.Failed(...)` before
 * throwing, so the banner surfaces the failure — the exception just
 * needs to not kill the process.
 */
class HomeViewModelSafeSyncTest {

    @Test
    fun safePerformSync_swallowsRuntimeException() = runBlocking {
        // The whole point: this must NOT throw. If it does, the test
        // fails — that's the regression guard for the crash bug.
        var reached = false
        try {
            com.nolansoftware.airadio.ui.viewmodels.safePerformSync {
                throw RuntimeException("simulated network failure")
            }
            reached = true
        } catch (e: Throwable) {
            throw AssertionError(
                "safePerformSync must swallow Exception; got ${e.javaClass.simpleName}",
                e,
            )
        }
        assertEquals(true, reached)
    }

    @Test
    fun safePerformSync_runsActionToCompletion_whenActionSucceeds() = runBlocking {
        var counter = 0
        com.nolansoftware.airadio.ui.viewmodels.safePerformSync {
            counter++
        }
        assertEquals(1, counter)
    }

    @Test
    fun safePerformSync_doesNotSwallowCancellationException() = runBlocking {
        // Cancellation must propagate — silently swallowing cancellation
        // would break viewModelScope cancellation semantics and could
        // cause the auto-retry loop to keep running after the user
        // navigates away.
        var caught = false
        try {
            com.nolansoftware.airadio.ui.viewmodels.safePerformSync {
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            caught = true
        }
        assertEquals("CancellationException must NOT be swallowed", true, caught)
    }
}
