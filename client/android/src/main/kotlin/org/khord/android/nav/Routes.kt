package org.khord.android.nav

/**
 * Type-safe-ish navigation routes for the Khord app.
 *
 * Compose Navigation's argument typing is awkward for sealed-class
 * destinations; we use plain string templates and a single helper to
 * build paths. Argument values come from the orchestrator (fingerprints,
 * mailbox ids) and are URL-safe by construction (hex / base64url).
 */
object Routes {
    const val SPLASH = "splash"
    const val WELCOME = "welcome"
    const val SERVER_SETUP = "server-setup"
    const val SEED_DISPLAY = "seed/display"
    const val SEED_CONFIRM = "seed/confirm"
    /** Seed-phrase recovery entry — see ADR 025. */
    const val SEED_RECOVERY = "seed/recovery"
    const val REGISTRATION = "register"
    const val CONTACTS = "contacts"
    const val ADD_CONTACT = "contacts/add"
    /** Contact acceptance gate — see Messaging.pendingContacts. */
    const val PENDING_CONTACTS = "contacts/pending"
    const val SETTINGS = "settings"

    // Chat carries the contact's fingerprint so the screen can pick the
    // right ContactSession from the orchestrator.
    const val CHAT_PATTERN = "chat/{fingerprint}"
    fun chat(fingerprint: String): String = "chat/$fingerprint"

    // Group routes (ADR 023). Group ID is a 32-char hex string so it's
    // URL-safe by construction.
    const val CREATE_GROUP = "groups/create"
    const val GROUP_CHAT_PATTERN = "group/{groupId}"
    fun groupChat(groupId: String): String = "group/$groupId"
    const val GROUP_INFO_PATTERN = "group/{groupId}/info"
    fun groupInfo(groupId: String): String = "group/$groupId/info"
}
