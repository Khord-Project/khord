package org.khord.shared.crypto.ratchet

import org.khord.shared.crypto.X25519KeyPair
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Guards the offline-queue send rollback (ADR 030, review finding #1): a
 * failed send must roll back ONLY the sending chain (CKs, Ns) — the fields
 * DoubleRatchet.encrypt advances — and must NOT touch the receiving chain
 * (CKr, Nr, DHr, RK, …). A concurrent, unsynchronised receiveMessages() may
 * have advanced the receive chain while the send's relay POST was in flight;
 * a full-state restore would discard that committed inbound progress and
 * desync the channel.
 */
class SendChainSnapshotTest {

    private fun state() = RatchetState(
        DHs = X25519KeyPair(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
        DHr = ByteArray(32) { 3 },
        RK = ByteArray(32) { 4 },
        CKs = ByteArray(32) { 5 },
        CKr = ByteArray(32) { 6 },
        Ns = 10,
        Nr = 20,
        PN = 7,
    )

    @Test
    fun restore_rolls_back_send_chain_only() {
        val s = state()
        val snap = s.snapshotSendChain()

        // Simulate encrypt() advancing the send chain...
        s.CKs = ByteArray(32) { 55 }
        s.Ns = 11
        // ...AND a concurrent receive advancing the receive chain.
        s.CKr = ByteArray(32) { 66 }
        s.Nr = 21
        s.DHr = ByteArray(32) { 33 }
        s.RK = ByteArray(32) { 44 }

        s.restoreSendChain(snap)

        // Send chain rolled back.
        assertContentEquals(ByteArray(32) { 5 }, s.CKs)
        assertEquals(10, s.Ns)
        // Receive chain UNTOUCHED — the concurrent receive's progress survives.
        assertContentEquals(ByteArray(32) { 66 }, s.CKr)
        assertEquals(21, s.Nr)
        assertContentEquals(ByteArray(32) { 33 }, s.DHr)
        assertContentEquals(ByteArray(32) { 44 }, s.RK)
    }

    @Test
    fun snapshot_is_an_independent_copy() {
        val s = state()
        val snap = s.snapshotSendChain()
        // Mutating the live CKs must not change the snapshot's copy.
        s.CKs!![0] = 99
        s.restoreSendChain(snap)
        assertEquals(5, s.CKs!![0])
    }
}
