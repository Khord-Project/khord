package org.khord.shared.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import org.khord.shared.crypto.Crypto
import org.khord.shared.protocol.wire.QrPayload
import org.khord.shared.storage.db.KhordDatabase
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Media-attachment persistence (ADR 029): the fresh-schema insert/select
 * round-trip AND the v11 -> v12 migration that upgrading installs take.
 * The image-attachment receive bug strands messages unacked in the
 * mailbox, which is exactly the symptom of an exception in saveMessage on
 * the receive path — so these guard both the column mapping and the
 * migration that adds those columns.
 */
class DbMediaPersistenceTest {

    private val tempDir = createTempDirectory("khord-media-test").toFile().apply { deleteOnExit() }

    @AfterTest fun cleanup() = tempDir.deleteRecursively().let {}

    private suspend fun openTestDb(): Persistence {
        Crypto.ensureInitialized()
        val dbPath = Path(tempDir.absolutePath, "media-${System.nanoTime()}.db").absolutePathString()
        return openDbPersistence(dbPath, InMemoryKeyStore())
    }

    private suspend fun seedContact(p: Persistence, fp: String) {
        p.saveContact(
            QrPayload(
                identityKey = "AAAA", fingerprint = fp,
                keyServer = "https://ks", relayServer = "https://rs",
                relayMailbox = "mailbox-id-22-chars-zzzz",
            ),
            displayName = "Bob",
        )
    }

    @Test
    fun message_media_round_trips() = runTest {
        val p = openTestDb()
        try {
            val fp = "c".repeat(64)
            seedContact(p, fp)
            val media = MediaAttachment(
                mediaId = "abc123",
                mediaKey = ByteArray(32) { it.toByte() },
                mediaNonce = ByteArray(24) { (it + 1).toByte() },
                mediaRelay = "https://rs",
                thumbnail = ByteArray(40) { (it * 3).toByte() },
            )
            p.saveMessage(fp, MessageDirection.RECEIVED, "caption", "2026-05-29T00:00:00Z", "uuid-1", null, media)

            val loaded = p.loadMessages(fp).single()
            assertEquals("caption", loaded.body)
            val m = assertNotNull(loaded.media, "media must survive the round-trip")
            assertEquals("abc123", m.mediaId)
            assertContentEquals(media.mediaKey, m.mediaKey)
            assertContentEquals(media.mediaNonce, m.mediaNonce)
            assertEquals("https://rs", m.mediaRelay)
            assertContentEquals(media.thumbnail, m.thumbnail)
            assertNull(m.cachedPath)

            p.setMessageMediaCachedPath(loaded.id, "/data/media/abc123.jpg")
            assertEquals("/data/media/abc123.jpg", p.loadMessages(fp).single().media?.cachedPath)
        } finally {
            p.close()
        }
    }

    @Test
    fun plain_text_message_has_null_media() = runTest {
        val p = openTestDb()
        try {
            val fp = "d".repeat(64)
            seedContact(p, fp)
            p.saveMessage(fp, MessageDirection.SENT, "hi", "2026-05-29T00:00:00Z")
            assertNull(p.loadMessages(fp).single().media)
        } finally {
            p.close()
        }
    }

    /**
     * Reproduces what an upgrading install does: a DB physically at schema
     * v11 (message/group_message WITHOUT the media columns) that the driver
     * migrates to v12 by running 11.sqm. If 11.sqm didn't add the columns,
     * the post-migration INSERT would throw "no such column" — the exact
     * failure that would strand inbound image messages on a device.
     */
    @Test
    fun migration_11_to_12_adds_media_columns() {
        val dbPath = Path(tempDir.absolutePath, "migrate-${System.nanoTime()}.db").absolutePathString()
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")

        // Build the pre-media v11 shape of the two tables (only the columns
        // the migration cares about — enough to ALTER) and stamp v11.
        driver.execute(null, """
            CREATE TABLE message (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                contact_fingerprint TEXT NOT NULL,
                direction TEXT NOT NULL,
                body TEXT NOT NULL,
                timestamp TEXT NOT NULL,
                stored_at TEXT NOT NULL,
                message_uuid TEXT,
                edited INTEGER NOT NULL DEFAULT 0,
                reply_to_uuid TEXT
            );
        """.trimIndent(), 0)
        driver.execute(null, """
            CREATE TABLE group_message (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                group_id TEXT NOT NULL,
                sender_fingerprint TEXT NOT NULL,
                sender_display_name TEXT NOT NULL DEFAULT '',
                body TEXT NOT NULL,
                timestamp TEXT NOT NULL,
                direction TEXT NOT NULL,
                stored_at TEXT NOT NULL,
                message_uuid TEXT,
                edited INTEGER NOT NULL DEFAULT 0,
                reply_to_uuid TEXT
            );
        """.trimIndent(), 0)

        // Run exactly the 11 -> 12 migration step.
        KhordDatabase.Schema.migrate(driver, 11, 12)

        // The migrated columns must now exist and accept a BLOB/text insert.
        driver.execute(null, """
            INSERT INTO message(
                contact_fingerprint, direction, body, timestamp, stored_at,
                message_uuid, reply_to_uuid,
                media_id, media_key, media_nonce, media_relay, thumbnail, media_cached_path
            ) VALUES ('fp','received','cap','t','t', NULL, NULL,
                      'mid', x'00', x'01', 'https://rs', x'02', NULL);
        """.trimIndent(), 0)

        var mediaId: String? = null
        driver.executeQuery(null, "SELECT media_id FROM message LIMIT 1;", { cursor ->
            if (cursor.next().value) mediaId = cursor.getString(0)
            app.cash.sqldelight.db.QueryResult.Unit
        }, 0)
        assertEquals("mid", mediaId)

        // Schema.version must have advanced so the device actually triggers
        // the 11 -> 12 onUpgrade.
        assertTrue(KhordDatabase.Schema.version >= 12, "schema version did not advance to 12")
        driver.close()
    }
}
