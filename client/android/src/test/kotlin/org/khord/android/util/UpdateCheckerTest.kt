package org.khord.android.util

import kotlin.test.Test
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
}
