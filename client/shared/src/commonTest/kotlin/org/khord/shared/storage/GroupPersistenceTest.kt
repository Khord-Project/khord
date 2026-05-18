package org.khord.shared.storage

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Group persistence tests (ADR 023) for the InMemoryPersistence variant.
 * DbPersistence has its own JVM-only suite ([DbGroupPersistenceTest])
 * since SQLDelight's JVM driver isn't available in commonTest.
 *
 * Covers:
 *   - saveGroup / loadGroup / loadGroups / updateGroupName / deleteGroup
 *   - addGroupMember (upsert) / removeGroupMember / loadGroupMembers
 *   - saveGroupMessage / loadGroupMessages with direction roundtrip
 *   - cascade-like behaviour on deleteGroup (members + messages gone)
 *   - panic() clears every group table
 */
class GroupPersistenceTest {

    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)

    @Test
    fun save_and_load_group_round_trips() = runTest {
        val p = InMemoryPersistence()
        p.saveGroup("g1", "Family", alice, isAdmin = true)
        val loaded = p.loadGroup("g1")
        assertNotNull(loaded)
        assertEquals("Family", loaded.groupName)
        assertEquals(alice, loaded.createdByFingerprint)
        assertTrue(loaded.isAdmin)
        assertEquals(1, p.loadGroups().size)
    }

    @Test
    fun update_group_name_persists() = runTest {
        val p = InMemoryPersistence()
        p.saveGroup("g1", "Old", alice, isAdmin = true)
        p.updateGroupName("g1", "New")
        assertEquals("New", p.loadGroup("g1")?.groupName)
    }

    @Test
    fun add_and_remove_members() = runTest {
        val p = InMemoryPersistence()
        p.saveGroup("g1", "Family", alice, isAdmin = true)
        p.addGroupMember("g1", alice, "Alice")
        p.addGroupMember("g1", bob, "Bob")
        assertEquals(2, p.loadGroupMembers("g1").size)

        // Upsert — re-adding updates display_name.
        p.addGroupMember("g1", bob, "Robert")
        val updated = p.loadGroupMembers("g1").first { it.fingerprint == bob }
        assertEquals("Robert", updated.displayName)

        p.removeGroupMember("g1", bob)
        assertEquals(1, p.loadGroupMembers("g1").size)
    }

    @Test
    fun save_and_load_group_messages_preserves_order_and_direction() = runTest {
        val p = InMemoryPersistence()
        p.saveGroup("g1", "Family", alice, isAdmin = true)
        p.saveGroupMessage(
            "g1", alice, "Alice", "hi all", "2026-05-18T10:00:00Z",
            MessageDirection.SENT,
        )
        p.saveGroupMessage(
            "g1", bob, "Bob", "hello!", "2026-05-18T10:00:01Z",
            MessageDirection.RECEIVED,
        )
        val msgs = p.loadGroupMessages("g1")
        assertEquals(2, msgs.size)
        assertEquals("hi all", msgs[0].body)
        assertEquals(MessageDirection.SENT, msgs[0].direction)
        assertEquals(alice, msgs[0].senderFingerprint)
        assertEquals(MessageDirection.RECEIVED, msgs[1].direction)
    }

    @Test
    fun delete_group_clears_members_and_messages() = runTest {
        val p = InMemoryPersistence()
        p.saveGroup("g1", "Family", alice, isAdmin = true)
        p.addGroupMember("g1", alice, "Alice")
        p.saveGroupMessage(
            "g1", alice, "Alice", "ephemeral", "2026-05-18T10:00:00Z",
            MessageDirection.SENT,
        )

        p.deleteGroup("g1")

        assertNull(p.loadGroup("g1"))
        assertEquals(0, p.loadGroupMembers("g1").size)
        assertEquals(0, p.loadGroupMessages("g1").size)
    }

    @Test
    fun panic_wipes_all_group_state() = runTest {
        val p = InMemoryPersistence()
        p.saveGroup("g1", "Family", alice, isAdmin = true)
        p.addGroupMember("g1", alice, "Alice")
        p.addGroupMember("g1", bob, "Bob")
        p.saveGroupMessage(
            "g1", alice, "Alice", "bye", "2026-05-18T10:00:00Z",
            MessageDirection.SENT,
        )

        p.panic()

        assertEquals(0, p.loadGroups().size)
        assertEquals(0, p.loadGroupMembers("g1").size)
        assertEquals(0, p.loadGroupMessages("g1").size)
    }

    @Test
    fun multiple_groups_are_independent() = runTest {
        val p = InMemoryPersistence()
        p.saveGroup("g1", "Family", alice, isAdmin = true)
        p.saveGroup("g2", "Work", bob, isAdmin = false)
        p.addGroupMember("g1", alice, "Alice")
        p.addGroupMember("g1", carol, "Carol")
        p.addGroupMember("g2", bob, "Bob")

        assertEquals(2, p.loadGroupMembers("g1").size)
        assertEquals(1, p.loadGroupMembers("g2").size)

        p.deleteGroup("g1")
        assertNotNull(p.loadGroup("g2"))
        assertFalse(p.loadGroup("g2")!!.isAdmin)
        assertEquals(1, p.loadGroupMembers("g2").size)
    }
}
