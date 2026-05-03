"""Unit tests for the proof-of-work primitives."""
import hashlib

from app import pow as pow_module
from app.crypto import leading_zero_bits


def test_leading_zero_bits_known_values():
    assert leading_zero_bits(b"\x00\x00\x00") == 24
    assert leading_zero_bits(b"\xff") == 0
    assert leading_zero_bits(b"\x01") == 7
    assert leading_zero_bits(b"\x00\x01") == 15
    assert leading_zero_bits(b"\x80") == 0


def test_pow_verify_true_for_valid_solution():
    mailbox_id = "abcdefghij"
    # Pre-mined nonce for difficulty 8 — recompute to keep test self-contained.
    nonce = 0
    while True:
        h = hashlib.sha256(
            mailbox_id.encode() + str(nonce).encode()
        ).digest()
        if leading_zero_bits(h) >= 8:
            break
        nonce += 1
    assert pow_module.verify(mailbox_id, str(nonce), 8) is True


def test_pow_verify_false_for_low_difficulty_solution():
    """A nonce that solves difficulty 0 must NOT pass difficulty 16."""
    mailbox_id = "abcdefghij"
    assert pow_module.verify(mailbox_id, "0", 0) is True
    assert pow_module.verify(mailbox_id, "0", 16) is False  # almost certainly


def test_pow_verify_rejects_non_decimal_nonce():
    assert pow_module.verify("abc", "0xff", 0) is False
    assert pow_module.verify("abc", "abc", 0) is False
    assert pow_module.verify("abc", "", 0) is False
    assert pow_module.verify("abc", "-1", 0) is False
