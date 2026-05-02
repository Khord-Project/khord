"""initial relayserver schema

Revision ID: 0001
Revises:
Create Date: 2026-05-02
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "0001"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "mailboxes",
        sa.Column("mailbox_id", sa.String(), primary_key=True),
        sa.Column("bearer_token_hash", sa.String(), nullable=False),
        sa.Column(
            "proof_of_work_verified",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )

    op.create_table(
        "messages",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column(
            "mailbox_id",
            sa.String(),
            sa.ForeignKey("mailboxes.mailbox_id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("sequence", sa.BigInteger(), nullable=False),
        sa.Column("blob", sa.LargeBinary(), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index(
        "ix_messages_mailbox_sequence",
        "messages",
        ["mailbox_id", "sequence"],
    )


def downgrade() -> None:
    op.drop_index("ix_messages_mailbox_sequence", table_name="messages")
    op.drop_table("messages")
    op.drop_table("mailboxes")
