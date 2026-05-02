"""initial keyserver schema

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
        "identity_keys",
        sa.Column("fingerprint", sa.String(), primary_key=True),
        sa.Column("identity_key", sa.LargeBinary(), nullable=False),
        sa.Column("signed_pre_key_id", sa.Integer(), nullable=False),
        sa.Column("signed_pre_key", sa.LargeBinary(), nullable=False),
        sa.Column("signed_pre_key_signature", sa.LargeBinary(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )

    op.create_table(
        "one_time_pre_keys",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column(
            "fingerprint",
            sa.String(),
            sa.ForeignKey("identity_keys.fingerprint", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("key_id", sa.Integer(), nullable=False),
        sa.Column("public_key", sa.LargeBinary(), nullable=False),
        sa.Column("consumed", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index(
        "ix_one_time_pre_keys_fingerprint",
        "one_time_pre_keys",
        ["fingerprint"],
    )

    op.create_table(
        "auth_challenges",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("fingerprint", sa.String(), nullable=False),
        sa.Column("challenge", sa.LargeBinary(), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("used", sa.Boolean(), nullable=False, server_default=sa.false()),
    )
    op.create_index(
        "ix_auth_challenges_fingerprint",
        "auth_challenges",
        ["fingerprint"],
    )


def downgrade() -> None:
    op.drop_index("ix_auth_challenges_fingerprint", table_name="auth_challenges")
    op.drop_table("auth_challenges")
    op.drop_index("ix_one_time_pre_keys_fingerprint", table_name="one_time_pre_keys")
    op.drop_table("one_time_pre_keys")
    op.drop_table("identity_keys")
