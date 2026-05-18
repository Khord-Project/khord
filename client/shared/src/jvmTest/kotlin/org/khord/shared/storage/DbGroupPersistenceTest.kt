package org.khord.shared.storage

import kotlinx.coroutines.test.runTest
import org.khord.shared.crypto.Crypto
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Group persistence tests (ADR 023) for [DbPersistence]. Mirrors the
 * commonTest [GroupPersistenceTest] but exercises the real SQLDelight
 * driver — confirming the schema, FK CASCADE, and upsert semantics
 * behave as the in-memory variant claims.
 *
 * Uses the same tempdir-per-suite pattern as [DbPersistenceTest] for
 * parallel safety.
 */
class DbGroupPersistenceTest {

    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)

    private val tempDir = createTempDirectory("khord-group-persistence-test").toFile().apply {
        deleteOnExit()
    }

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private suspend fun openTestDb(name: String = "group-${System.nanoTime()}.db"): Persistence {
        Crypto.ensureInitialized()
        val dbPath = Path(tempDir.absolutePath, name).absolutePathString()
        return openDbPersistence(dbPath, InMemoryKeyStore())
    }

    @Test
    fun save_and_load_group_round_trips() = runTest {
        val p = openTestDb()
        p.saveGroup("g1", "Family", alice, isAdmin = true)
        val loaded = p.loadGroup("g1")
        assertNotNull(loaded)
        assertEquals("Family", loaded.groupName)
        assertEquals(alice, loaded.createdByFingerprint)
        assertTrue(loaded.isAdmin)
        p.close()
    }

    @Test
    fun update_group_name_persists() = runTest {
        val p = openTestDb()
        p.saveGroup("g1", "Old", alice, isAdmin = true)
        p.updateGroupName("g1", "Updated")
        assertEquals("Updated", p.loadGroup("g1")?.groupName)
        p.close()
    }

    @Test
    fun member_upsert_and_remove() = runTest {
        val p = openTestDb()
        p.saveGroup("g1", "Family", alice, isAdmin = true)
        p.addGroupMember("g1", alice, "Alice")
        p.addGroupMember("g1", bob, "Bob")
        assertEquals(2, p.loadGroupMembers("g1").size)

        p.addGroupMember("g1", bob, "Robert")
        val updated = p.loadGroupMembers("g1").first { it.fingerprint == bob }
        assertEquals("Robert", updated.displayName)

        p.removeGroupMember("g1", bob)
        assertEquals(1, p.loadGroupMembers("g1").size)
        p.close()
    }

    @Test
    fun group_message_round_trip_with_direction() = runTest {
        val p = openTestDb()
        p.saveGroup("g1", "Family", alice, isAdmin = true)
        p.saveGroupMessage(
            "g1", alice, "Alice", "first", "2026-05-18T10:00:00Z",
            MessageDirection.SENT,
        )
        p.saveGroupMessage(
            "g1", bob, "Bob", "second", "2026-05-18T10:00:01Z",
            MessageDirection.RECEIVED,
        )
        val msgs = p.loadGroupMessages("g1")
        assertEquals(2, msgs.size)
        assertEquals(MessageDirection.SENT, msgs[0].direction)
        assertEquals(MessageDirection.RECEIVED, msgs[1].direction)
        assertEquals("Bob", msgs[1].senderDisplayName)
        p.close()
    }

    @Test
    fun delete_group_cascades_members_and_messages() = runTest {
        val p = openTestDb()
        p.saveGroup("g1", "Family", alice, isAdmin = true)
        p.addGroupMember("g1", alice, "Alice")
        p.addGroupMember("g1", bob, "Bob")
        p.saveGroupMessage(
            "g1", alice, "Alice", "ephemeral", "2026-05-18T10:00:00Z",
            MessageDirection.SENT,
        )

        p.deleteGroup("g1")

        // FK ON DELETE CASCADE should have cleared rows in both tables.
        assertNull(p.loadGroup("g1"))
        assertEquals(0, p.loadGroupMembers("g1").size)
        assertEquals(0, p.loadGroupMessages("g1").size)
        p.close()
    }

    @Test
    fun multiple_groups_are_independent() = runTest {
        val p = openTestDb()
        p.saveGroup("g1", "Family", alice, isAdmin = true)
        p.saveGroup("g2", "Work", bob, isAdmin = false)
        p.addGroupMember("g1", carol, "Carol")
        p.addGroupMember("g2", bob, "Bob")

        assertEquals(2, p.loadGroups().size)
        assertEquals(1, p.loadGroupMembers("g1").size)
        assertEquals(1, p.loadGroupMembers("g2").size)

        p.deleteGroup("g1")
        assertNotNull(p.loadGroup("g2"))
        assertFalse(p.loadGroup("g2")!!.isAdmin)
        p.close()
    }
}
