package org.khord.shared.storage

internal actual suspend fun openDbPersistence(
    databasePath: String,
    keyStore: KeyStore,
): Persistence {
    throw NotImplementedError(
        "Android persistence factory wiring is part of the UI phase " +
        "(SQLCipher + Keystore-bound passphrase, see persistence investigation Q3)."
    )
}
