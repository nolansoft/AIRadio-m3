// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.ui.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.domain.model.SyncState
import com.nolansoftware.airadio.domain.usecase.GetFavoriteStationsUseCase
import com.nolansoftware.airadio.domain.usecase.GetPopularStationsUseCase
import com.nolansoftware.airadio.domain.usecase.GetRecentlyPlayedStationsUseCase
import com.nolansoftware.airadio.domain.usecase.GetStationsByCountryUseCase
import com.nolansoftware.airadio.domain.usecase.GetSyncStateUseCase
import com.nolansoftware.airadio.domain.usecase.IsFavoriteUseCase
import com.nolansoftware.airadio.domain.usecase.SyncNowUseCase
import com.nolansoftware.airadio.domain.usecase.ToggleFavoriteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val getPopularStationsUseCase: GetPopularStationsUseCase,
    private val getRecentlyPlayedStationsUseCase: GetRecentlyPlayedStationsUseCase,
    private val getStationsByCountryUseCase: GetStationsByCountryUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val isFavoriteUseCase: IsFavoriteUseCase,
    getSyncStateUseCase: GetSyncStateUseCase,
    private val syncNowUseCase: SyncNowUseCase
) : AndroidViewModel(application) {

    // StateFlow (not cold Flow) so that when HomeScreen is re-composed after
    // returning from Player, `collectAsState()` reads the previously-emitted
    // value synchronously — there is no "initialValue = null" window during
    // which the grid would have to fall back to skeleton items. With a Flow,
    // the producer restarts with null on every composition recreation, and the
    // grid's MeasurePolicy clamps the saved `firstVisibleItemIndex` against
    // the smaller skeleton item count, mutating the state and losing the
    // user's scroll position. StateFlow's retained value keeps the grid's
    // item count stable across the composition recreation, so the saved index
    // anchors correctly against the same real items that were there before
    // navigation.
    //
    // SharingStarted.Eagerly is fine here: HomeViewModel is scoped to the
    // HomeScreen NavBackStackEntry, so the upstream Flow is only collected
    // while the Home tab is in the back stack. Room's invalidation tracker
    // handles query freshness — eager collection just keeps the cache warm.
    val popularStations: StateFlow<List<Station>?> = getPopularStationsUseCase()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val recentlyPlayedStations: StateFlow<List<Station>?> = getRecentlyPlayedStationsUseCase()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val localStations: StateFlow<List<Station>?> = getStationsByCountryUseCase(
        Locale.getDefault().displayCountry
    ).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Public seam for the banner retry CTA. Idempotent if a sync is already
     * running — the repository's Mutex serializes the calls.
     */
    val syncState: StateFlow<SyncState> = getSyncStateUseCase()

    init {
        // Bug #3: drive the sync ourselves while the grid is empty so the
        // user never sees a "blank for a few seconds" gap before the CTA
        // renders (Bug #1), and so that on networks where the API host is
        // unreachable we keep retrying until either data lands or the
        // MAX_AUTO_RETRY_ATTEMPTS cap is hit. Safe to start here — the
        // loop checks `popularStations.value` on every iteration and exits
        // as soon as the database is populated.
        startAutoRetryLoop()
    }

    fun retrySync() {
        // Bug #2: the repository re-throws on API failure
        // (`RadioRepository.runSync`, line ~350) so WorkManager can convert
        // the exception into `Result.retry()`. That throw used to propagate
        // uncaught through `viewModelScope.launch` and crash the main
        // thread (`FATAL EXCEPTION: main` in logcat). `safePerformSync`
        // catches the exception locally — the repository has already
        // published a `SyncState.Failed` via `_syncState` before throwing,
        // so the banner surfaces the failure; the throw just must not kill
        // the process.
        viewModelScope.launch { safePerformSync { syncNowUseCase() } }
    }

    /**
     * Background loop: keeps firing `syncNowUseCase` while the popular
     * stations flow is empty, so the home grid populates as soon as the
     * API becomes reachable without the user having to tap anything.
     *
     * Bounded by [MAX_AUTO_RETRY_ATTEMPTS] to cap resource use on a
     * device that is permanently offline — after the cap the loop exits
     * and the user is left with whatever the banner surfaces. The sync
     * itself remains retryable via `retrySync()` (the failed-CTA branch
     * in `SyncStatusBanner`) or via the next cold-start, which kicks the
     * initial `OneTimeWorkRequest` again.
     *
     * First delay is short (3 s) so the user perceives the loop as
     * "kicked in automatically" rather than after some quiet interval.
     * Subsequent retries use a longer 10 s delay to give OkHttp time to
     * resolve DNS / establish TLS without busy-looping the radio.
     */
    private fun startAutoRetryLoop() {
        viewModelScope.launch {
            delay(INITIAL_AUTO_RETRY_DELAY_MS)
            var attempts = 0
            while (
                isActive &&
                popularStations.value.isNullOrEmpty() &&
                attempts < MAX_AUTO_RETRY_ATTEMPTS
            ) {
                if (syncState.value !is SyncState.Syncing) {
                    safePerformSync { syncNowUseCase() }
                }
                attempts++
                delay(AUTO_RETRY_DELAY_MS)
            }
        }
    }

    fun toggleFavorite(station: Station) {
        viewModelScope.launch {
            toggleFavoriteUseCase(station)
        }
    }

    fun isFavorite(stationId: String): Flow<Boolean> {
        return isFavoriteUseCase(stationId)
    }
}

/**
 * Bug #2 fix: a top-level helper that wraps a suspend [syncAction] and
 * swallows any non-cancellation [Exception] it throws.
 *
 * `RadioRepository.runSync` re-throws on API failure so WorkManager's
 * `SyncWorker` can convert the exception into `Result.retry()`. When the
 * same call is driven from `HomeViewModel.retrySync` /
 * `startAutoRetryLoop` we want the throw to be logged but not to bubble
 * up — an uncaught `UnknownHostException` on the main dispatcher kills
 * the process via Android's default uncaught-exception handler.
 *
 * `CancellationException` is re-thrown intentionally so structured
 * concurrency still works: if `viewModelScope` cancels (e.g. the user
 * navigates away from Home), the loop and any in-flight retry exit
 * cleanly rather than silently swallowing the cancellation and
 * continuing to retry.
 */
internal suspend fun safePerformSync(syncAction: suspend () -> Unit) {
    try {
        syncAction()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // `Log.w` throws "Method ... in android.util.Log not mocked." in
        // JVM unit tests (the project's app/build.gradle.kts does not set
        // `testOptions.unitTests.returnDefaultValues = true`). Wrap the
        // call so a Log-side failure can't escape `safePerformSync` and
        // break the "swallow Exception" contract this helper exists to
        // uphold — see HomeViewModelSafeSyncTest.
        try {
            Log.w("HomeViewModel", "sync attempt failed", e)
        } catch (ignored: Exception) {
            // Intentionally swallowed: the sync failure has already been
            // recorded by the repository (SyncState.Failed is published
            // before the throw), so the only thing a Log-side failure
            // could cost us is the secondary diagnostic line.
        }
    }
}

/** First auto-retry fires after this delay so the user perceives the
 *  loop as automatic, not as something they had to wait for. */
private const val INITIAL_AUTO_RETRY_DELAY_MS = 3_000L

/** Gap between auto-retry attempts after the first one. Long enough to
 *  give OkHttp room to resolve DNS / establish TLS without busy-looping. */
private const val AUTO_RETRY_DELAY_MS = 10_000L

/** Hard cap on auto-retry attempts to bound resource use on a device
 *  that is permanently offline. The user can still trigger another
 *  retry manually via `retrySync()` / the failed-CTA branch of the
 *  banner, and the next cold start re-arms the loop. */
private const val MAX_AUTO_RETRY_ATTEMPTS = 6
