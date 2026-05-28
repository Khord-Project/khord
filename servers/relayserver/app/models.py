from datetime import datetime

from sqlalchemy import (
    BigInteger,
    Boolean,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    LargeBinary,
    String,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class Mailbox(Base):
    __tablename__ = "mailboxes"

    mailbox_id: Mapped[str] = mapped_column(String, primary_key=True)
    bearer_token_hash: Mapped[str] = mapped_column(String, nullable=False)
    proof_of_work_verified: Mapped[bool] = mapped_column(
        Boolean, default=False, nullable=False
    )
    # Monotonic per-mailbox sequence counter (ADR 007). Never resets.
    last_sequence: Mapped[int] = mapped_column(
        BigInteger, nullable=False, default=0, server_default="0"
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    messages: Mapped[list["Message"]] = relationship(
        back_populates="mailbox", cascade="all, delete-orphan"
    )


class Message(Base):
    __tablename__ = "messages"
    __table_args__ = (
        Index("ix_messages_mailbox_sequence", "mailbox_id", "sequence"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    mailbox_id: Mapped[str] = mapped_column(
        String, ForeignKey("mailboxes.mailbox_id", ondelete="CASCADE"), nullable=False
    )
    sequence: Mapped[int] = mapped_column(BigInteger, nullable=False)
    blob: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    mailbox: Mapped[Mailbox] = relationship(back_populates="messages")


class Media(Base):
    """An opaque, client-encrypted media blob (ADR 029).

    Deliberately standalone — no FK to a mailbox. The uploader and the
    eventual downloader are decoupled: the sender uploads here, then ships
    the random `id` (+ the symmetric key, out-of-band via the E2E message
    channel) to the recipient, who fetches by `id` alone. That makes the
    blob portable across relays (federation) and means knowing the 128-bit
    random `id` is the only capability required to fetch — which is why the
    GET is delete-on-read (one-time, like a consumed message).

    The server stores ONLY: the random id, the encrypted bytes, and two
    timestamps. No filename, no content type, no sender, no recipient, no
    key — it cannot decrypt or attribute the blob.
    """

    __tablename__ = "media"
    __table_args__ = (Index("ix_media_expires_at", "expires_at"),)

    # 16 random bytes as hex (secrets.token_hex(16)) — 128 bits of capability.
    id: Mapped[str] = mapped_column(String, primary_key=True)
    data: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
