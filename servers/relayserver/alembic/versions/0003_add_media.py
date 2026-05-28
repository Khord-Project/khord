"""add media table

Revision ID: 0003
Revises: 0002
Create Date: 2026-05-28

ADR 029 — image attachments. A standalone store for opaque, client-
encrypted media blobs. No FK to mailboxes: the uploader and downloader are
decoupled (the random id travels out-of-band through the E2E message
channel). `expires_at` drives the same opportunistic-TTL + delete-on-read
lifecycle the messages table uses.
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "0003"
down_revision: Union[str, None] = "0002"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "media",
        sa.Column("id", sa.String(), primary_key=True),
        sa.Column("data", sa.LargeBinary(), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index("ix_media_expires_at", "media", ["expires_at"])


def downgrade() -> None:
    op.drop_index("ix_media_expires_at", table_name="media")
    op.drop_table("media")
