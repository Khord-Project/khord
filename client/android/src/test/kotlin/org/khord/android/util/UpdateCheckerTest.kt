package org.khord.android.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateCheckerTest {

    @Test
    fun equal_versions_are_not_newer() {
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("0.1.0-alpha.3", "0.1.0-alpha.3"))
    }

    @Test
    fun higher_numeric_component_wins() {
        assertTrue(UpdateChecker.isNewer("0.1.1", "0.1.0"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.9.9"))
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.1.1"))
    }

    @Test
    fun stable_outranks_prerelease_at_same_numeric_version() {
        assertTrue(UpdateChecker.isNewer("1.0.0", "1.0.0-alpha"))
        assertFalse(UpdateChecker.isNewer("1.0.0-alpha", "1.0.0"))
    }

    @Test
    fun alpha_to_alpha_double_digit_compares_numerically() {
        // This is the regression: under the old lexicographic compare,
        // "alpha.10" < "alpha.2" — meaning a user on alpha.2 would
        // never see alpha.10 as an upgrade.
        assertTrue(UpdateChecker.isNewer("0.1.0-alpha.10", "0.1.0-alpha.2"))
        assertFalse(UpdateChecker.isNewer("0.1.0-alpha.2", "0.1.0-alpha.10"))
    }

    @Test
    fun alpha_10_is_newer_than_alpha_9_adjacent_case() {
        // The exact case observed in the field: a user on alpha.9
        // upgrading to alpha.10. The comparator was already correct
        // (covered by the double-digit test above), but adding the
        // adjacent case explicitly so the regression has a named
        // test if it ever resurfaces.
        assertTrue(UpdateChecker.isNewer("0.1.0-alpha.10", "0.1.0-alpha.9"))
        assertFalse(UpdateChecker.isNewer("0.1.0-alpha.9", "0.1.0-alpha.10"))
    }

    @Test
    fun alpha_to_alpha_single_digit_still_works() {
        assertTrue(UpdateChecker.isNewer("0.1.0-alpha.4", "0.1.0-alpha.3"))
        assertFalse(UpdateChecker.isNewer("0.1.0-alpha.3", "0.1.0-alpha.4"))
    }

    @Test
    fun beta_outranks_alpha_lexicographically() {
        assertTrue(UpdateChecker.isNewer("1.0.0-beta", "1.0.0-alpha"))
        assertFalse(UpdateChecker.isNewer("1.0.0-alpha", "1.0.0-beta"))
    }

    @Test
    fun longer_prerelease_outranks_shorter_when_prefix_equal() {
        // Per semver §11: "alpha" < "alpha.1".
        assertTrue(UpdateChecker.isNewer("1.0.0-alpha.1", "1.0.0-alpha"))
        assertFalse(UpdateChecker.isNewer("1.0.0-alpha", "1.0.0-alpha.1"))
    }

    // pickNewerThan — covers the GitHub-ordering regression that
    // shipped in alpha.5–alpha.10. The GitHub /releases API doesn't
    // guarantee newest-first ordering; the old `arr[0]` path stopped
    // at the first non-draft release, which on a 10-release listing
    // was alpha.9 rather than alpha.10.

    private fun ui(version: String) =
        UpdateInfo(version = version, htmlUrl = "https://x/$version")

    @Test
    fun pickNewerThan_returns_max_regardless_of_input_order() {
        // The exact ordering GitHub returned when alpha.10 was live:
        // alpha.9 first, alpha.10 buried at position 7. The picker
        // must surface alpha.10 anyway.
        val list = listOf(
            ui("0.1.0-alpha.9"),
            ui("0.1.0-alpha.8"),
            ui("0.1.0-alpha.7"),
            ui("0.1.0-alpha.6"),
            ui("0.1.0-alpha.5"),
            ui("0.1.0-alpha.4"),
            ui("0.1.0-alpha.10"),
            ui("0.1.0-alpha.3"),
            ui("0.1.0-alpha.2"),
            ui("0.1.0-alpha"),
        )
        assertEquals("0.1.0-alpha.10", UpdateChecker.pickNewerThan(list, "0.1.0-alpha.9")?.version)
        assertEquals("0.1.0-alpha.10", UpdateChecker.pickNewerThan(list, "0.1.0-alpha.8")?.version)
        assertEquals("0.1.0-alpha.10", UpdateChecker.pickNewerThan(list, "0.1.0-alpha")?.version)
    }

    @Test
    fun pickNewerThan_returns_null_when_local_already_newest() {
        val list = listOf(
            ui("0.1.0-alpha.9"),
            ui("0.1.0-alpha.8"),
            ui("0.1.0-alpha.10"),
        )
        assertEquals(null, UpdateChecker.pickNewerThan(list, "0.1.0-alpha.10"))
        assertEquals(null, UpdateChecker.pickNewerThan(list, "0.1.0-alpha.11"))
    }

    @Test
    fun pickNewerThan_handles_empty_input() {
        assertEquals(null, UpdateChecker.pickNewerThan(emptyList(), "0.1.0-alpha.9"))
    }
}
