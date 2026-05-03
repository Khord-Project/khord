"""add mailboxes.last_sequence

Revision ID: 0002
Revises: 0001
Create Date: 2026-05-03

Per ADR 007, each mailbox has a per-mailbox monotonic sequence counter.
The counter must persist across acks/deletes so a mailbox that has seen
sequences 1-100 (all acked) assigns 101 to the next message — never resets
to 0. Storing it on the mailboxes row gives us atomic increment via
UPDATE ... RETURNING under read-committed.
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "0002"
down_revision: Union[str, None] = "0001"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "mailboxes",
        sa.Column(
            "last_sequence",
            sa.BigInteger(),
            nullable=False,
            server_default="0",
        ),
    )


def downgrade() -> None:
    op.drop_column("mailboxes", "last_sequence")
