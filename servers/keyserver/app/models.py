from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, LargeBinary, String, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class IdentityKey(Base):
    __tablename__ = "identity_keys"

    fingerprint: Mapped[str] = mapped_column(String, primary_key=True)
    identity_key: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    signed_pre_key_id: Mapped[int] = mapped_column(Integer, nullable=False)
    signed_pre_key: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    signed_pre_key_signature: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        onupdate=func.now(),
        nullable=False,
    )

    one_time_pre_keys: Mapped[list["OneTimePreKey"]] = relationship(
        back_populates="identity", cascade="all, delete-orphan"
    )


class OneTimePreKey(Base):
    __tablename__ = "one_time_pre_keys"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    fingerprint: Mapped[str] = mapped_column(
        String, ForeignKey("identity_keys.fingerprint", ondelete="CASCADE"), index=True
    )
    key_id: Mapped[int] = mapped_column(Integer, nullable=False)
    public_key: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    consumed: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    identity: Mapped[IdentityKey] = relationship(back_populates="one_time_pre_keys")


class AuthChallenge(Base):
    __tablename__ = "auth_challenges"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    fingerprint: Mapped[str] = mapped_column(String, index=True, nullable=False)
    challenge: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    used: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
