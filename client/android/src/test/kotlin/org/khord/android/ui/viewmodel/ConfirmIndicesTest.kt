package org.khord.android.ui.viewmodel

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfirmIndicesTest {

    @Test
    fun pick_returns_three_distinct_sorted_indices_in_range() {
        val picked = ConfirmIndices.pick()
        assertEquals(3, picked.size, "default count is 3")
        assertEquals(picked.distinct().size, picked.size, "indices are distinct")
        assertEquals(picked, picked.sorted(), "indices are ascending")
        assert(picked.all { it in 0..11 }) { "out of range: $picked" }
    }

    @Test
    fun pick_with_seed_is_deterministic() {
        val a = ConfirmIndices.pick(rng = Random(42))
        val b = ConfirmIndices.pick(rng = Random(42))
        assertEquals(a, b)
    }

    @Test
    fun pick_with_different_seeds_eventually_diverges() {
        // Many independent draws — if the picker were constant we'd see
        // the same triple every time; with randomness we should see at
        // least two distinct results across 50 runs (probability of a
        // 50-run collision under uniform sampling is negligible).
        val results = (1..50).map { seed -> ConfirmIndices.pick(rng = Random(seed.toLong())) }
        assert(results.toSet().size > 1) {
            "ConfirmIndices.pick produced the same triple for 50 seeds — not random?"
        }
    }

    @Test
    fun pick_respects_count_and_max_arguments() {
        val picked = ConfirmIndices.pick(count = 5, max = 10)
        assertEquals(5, picked.size)
        assert(picked.all { it in 0..9 }) { "out of [0,10): $picked" }
        assertEquals(picked.distinct().size, picked.size)
    }

    @Test
    fun pick_rejects_count_greater_than_max() {
        assertFailsWith<IllegalArgumentException> {
            ConfirmIndices.pick(count = 13, max = 12)
        }
    }

    @Test
    fun pick_rejects_zero_count() {
        assertFailsWith<IllegalArgumentException> {
            ConfirmIndices.pick(count = 0, max = 12)
        }
    }
}
