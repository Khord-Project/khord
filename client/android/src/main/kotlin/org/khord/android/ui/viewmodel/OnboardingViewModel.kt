package org.khord.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random
import org.khord.android.AppContainer
import org.khord.android.ServerUrls
import org.khord.shared.crypto.IdentityKey
import org.khord.shared.identity.SeedPhrase

/**
 * Picks the indices the confirmation screen quizzes the user on.
 * Extracted as a pure helper so the randomness is unit-testable
 * (an injectable [Random]) without spinning up the ViewModel.
 *
 * Returns [count] distinct indices from `[0, max)`, sorted ascending —
 * sorted display reads more naturally ("words 2, 7, 11" vs "7, 2, 11").
 */
internal object ConfirmIndices {
    fun pick(
        rng: Random = Random.Default,
        count: Int = 3,
        max: Int = 12,
    ): List<Int> {
        require(count in 1..max) { "count $count out of range for max $max" }
        return (0 until max).shuffled(rng).take(count).sorted()
    }
}

/**
 * Owns the onboarding flow's state — the freshly generated phrase, the
 * confirmation step, and the registration call.
 *
 * Process-singleton so the user can navigate Display → Confirm → back →
 * Display without losing the phrase. (Re-creating it on each visit would
 * waste the user's effort if they tapped Back to re-read.)
 */
class OnboardingViewModel : ViewModel() {

    sealed interface Status {
        data object Idle : Status
        data object Generating : Status
        data class Display(val words: List<String>) : Status
        data object Registering : Status
        data object Done : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /**
     * The currently-displayed phrase. Lives only in memory; the moment the
     * registration flow finishes, this is wiped via [Status.Done].
     */
    private var currentPhrase: List<String>? = null

    /**
     * Three distinct word positions the confirm screen quizzes the user
     * on. Re-rolled on every [generate] call so a user (or attacker) who
     * knows the previous prompt set can't game which words to memorise.
     * Stable for the lifetime of one phrase — re-entering the confirm
     * screen with the same phrase shows the same prompts.
     */
    private var confirmIndices: List<Int> = ConfirmIndices.pick()

    /**
     * User's chosen display name. Optional — if the user skips the prompt
     * we register as "Anonymous". Trimmed and capped to 64 chars to avoid
     * silly inputs filling the reply_info block on every message.
     */
    @Volatile
    private var displayName: String = "Anonymous"

    fun setDisplayName(name: String) {
        val trimmed = name.trim().take(64)
        displayName = if (trimmed.isEmpty()) "Anonymous" else trimmed
    }

    fun currentDisplayName(): String = displayName

    fun generate() {
        viewModelScope.launch(Dispatchers.IO) {
            _status.value = Status.Generating
            try {
                org.khord.shared.crypto.Crypto.ensureInitialized()
                val words = SeedPhrase.generate()
                currentPhrase = words
                confirmIndices = ConfirmIndices.pick(max = words.size)
                _status.value = Status.Display(words)
            } catch (e: Throwable) {
                _status.value = Status.Failed(e.message ?: "generate failed")
            }
        }
    }

    fun confirmIndices(): List<Int> = confirmIndices

    /** Returns true iff the supplied words match the phrase at [confirmIndices]. */
    fun checkConfirmation(typed: List<String>): Boolean {
        val phrase = currentPhrase ?: return false
        if (typed.size != confirmIndices.size) return false
        for ((i, idx) in confirmIndices.withIndex()) {
            if (!typed[i].equals(phrase[idx], ignoreCase = true)) return false
        }
        return true
    }

    /**
     * Final step: derive identity, open persistence-backed Messaging,
     * upload bundle to Key Server. The Argon2id derivation costs ~700ms;
     * this is fully off the main thread.
     */
    fun register() {
        val phrase = currentPhrase ?: run {
            _status.value = Status.Failed("no phrase staged")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _status.value = Status.Registering
            try {
                org.khord.shared.crypto.Crypto.ensureInitialized()
                val canonical = SeedPhrase.toCanonicalString(phrase)
                val identity = IdentityKey.fromSeedPhrase(canonical)
                val bootstrap = AppContainer.bootstrap
                    ?: error("AppContainer not bootstrapped")
                val messaging = bootstrap.registerFreshIdentity(
                    identity = identity,
                    keyServerUrl = ServerUrls.KEY_SERVER,
                    relayServerUrl = ServerUrls.RELAY_SERVER,
                    displayName = displayName,
                )
                AppContainer.messaging = messaging
                messaging.register(opkBatchSize = 50)

                // Forget the phrase from memory — the user has it on paper.
                currentPhrase = null
                _status.value = Status.Done
            } catch (e: Throwable) {
                _status.value = Status.Failed(e.message ?: e::class.simpleName ?: "register failed")
            }
        }
    }
}
