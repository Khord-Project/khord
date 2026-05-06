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
    const val SEED_DISPLAY = "seed/display"
    const val SEED_CONFIRM = "seed/confirm"
    const val REGISTRATION = "register"
    const val CONTACTS = "contacts"
    const val ADD_CONTACT = "contacts/add"
    const val SETTINGS = "settings"

    // Chat carries the contact's fingerprint so the screen can pick the
    // right ContactSession from the orchestrator.
    const val CHAT_PATTERN = "chat/{fingerprint}"
    fun chat(fingerprint: String): String = "chat/$fingerprint"
}
