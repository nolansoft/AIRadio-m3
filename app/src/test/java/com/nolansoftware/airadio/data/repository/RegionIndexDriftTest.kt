// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RB-005 regression guard. The persisted `regionStore.currentIndex` is
 * just an `Int`, but the *meaning* of that index depends on the mirror
 * list the registry returned when it was written. If the live registry
 * later changes shape (a host goes offline, a new host appears),
 * `currentIndex=1` may point at a *different* host than it did before.
 *
 * The audit confirmed that without validation, `coerceIn(0, list.lastIndex)`
 * is the only guard, and it cannot tell "stale index pointing at a
 * different host" from "valid index pointing at the same host".
 *
 * This test file pins the minimum safe behavior we ship:
 *  1. Valid index is preserved as-is.
 *  2. Index that falls outside the current list (drift) resets to 0
 *     rather than crashing or silently pointing at the wrong host.
 *
 * Host-identity-aware validation (e.g. tracking `currentHost` alongside
 * `currentIndex`) is intentionally NOT implemented in M1 — it would
 * require changing the `RegionStore` persistence format, which the
 * task brief excluded.
 */
class RegionIndexDriftTest {

    @Test
    fun indexWithinBounds_isPreserved() {
        assertEquals(0, validatedRegionIndex(rawIndex = 0, listSize = 2))
        assertEquals(1, validatedRegionIndex(rawIndex = 1, listSize = 2))
        assertEquals(2, validatedRegionIndex(rawIndex = 2, listSize = 3))
    }

    @Test
    fun indexAtLastPosition_isPreserved() {
        // Boundary: last valid index == listSize - 1 must NOT reset.
        assertEquals(1, validatedRegionIndex(rawIndex = 1, listSize = 2))
    }

    @Test
    fun indexBeyondListSize_resetsToZero() {
        // Old list = [de1, at1], currentIndex = 1 (last successful = at1).
        // New list = [de1] (at1 went offline).
        // Naive coerceIn(0, 0) would still produce 0, but we want a
        // *named* helper so the behavior is obvious at the call site.
        assertEquals(0, validatedRegionIndex(rawIndex = 1, listSize = 1))
    }

    @Test
    fun negativeIndex_resetsToZero() {
        // Defensive: never trust an external int blindly.
        assertEquals(0, validatedRegionIndex(rawIndex = -1, listSize = 3))
    }

    @Test
    fun emptyList_doesNotCrash_returnsZero() {
        // Cold-start with an empty mirror list: we'd never reach this
        // helper in production (RegionFailoverSyncExecutor.require() would
        // throw first), but the validator itself must not crash on
        // listSize=0 either.
        assertEquals(0, validatedRegionIndex(rawIndex = 0, listSize = 0))
    }

    @Test
    fun shrunkenList_doesNotCrashAndProducesReasonableFallback() {
        // Concrete drift scenario from the audit:
        //   old persisted state: list=[de1, at1], currentIndex=1 (at1)
        //   new live state:      list=[de1]            (at1 offline)
        // Expected: 0 (first host of the new list — deterministic, safe).
        assertEquals(0, validatedRegionIndex(rawIndex = 1, listSize = 1))
    }
}
