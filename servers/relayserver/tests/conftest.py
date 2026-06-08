"""
Test fixtures for the Relay Server.

Loop-binding gotcha: the WebSocket tests use Starlette's sync TestClient,
which spins up its own anyio portal (and therefore its own event loop).
A module-level async engine bound to the pytest-asyncio session loop will
hand out asyncpg connections that cannot be used on the TestClient loop
("attached to a different loop").

Workaround: the autouse `_override_get_session` fixture below replaces the
app's `get_session` dependency with one that creates a fresh `NullPool`
engine *per request*. The engine is built on whatever loop is currently
running (session loop for ASGITransport, portal loop for TestClient), so
both transports work without trampling each other.
"""
import os

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

os.environ.setdefault(
    "DATABASE_URL",
    "postgresql+asyncpg://relayserver:relayserver_dev_password@relayserver-db:5432/relayserver",
)

from app.database import get_session  # noqa: E402  (after env setup)
from app.main import app  # noqa: E402
from app.notifier import notifier  # noqa: E402
from app.ratelimit import limiter, reset_limiter  # noqa: E402

# Rate limiting is global, in-memory, and shared across the whole app, so it is
# OFF by default during tests — otherwise counters would bleed between the many
# functional tests and cause spurious 429s. The dedicated rate-limit tests opt
# back in via the `rate_limited` fixture.
limiter.enabled = False


@pytest.fixture
def rate_limited():
    """Enable rate limiting (from a clean slate) for the duration of a test."""
    reset_limiter()
    limiter.enabled = True
    yield
    limiter.enabled = False
    reset_limiter()


@pytest.fixture(autouse=True)
def _reset_notifier_state():
    """Clear the module-singleton notifier's connection/subscriber tracking so
    WS connection-cap tests can't leak counts into one another."""
    notifier._ip_counts.clear()
    notifier._subscribers.clear()
    yield
    notifier._ip_counts.clear()
    notifier._subscribers.clear()


_TABLES = ["media", "messages", "mailboxes"]


async def _request_scoped_session():
    """Per-request session factory — creates an engine on the current loop."""
    eng = create_async_engine(
        os.environ["DATABASE_URL"], poolclass=NullPool
    )
    SessionLocal = async_sessionmaker(
        eng, expire_on_commit=False, class_=AsyncSession
    )
    try:
        async with SessionLocal() as session:
            yield session
    finally:
        await eng.dispose()


@pytest_asyncio.fixture(loop_scope="session", scope="session", autouse=True)
async def _override_get_session():
    """Install the per-request session factory for the entire test session."""
    app.dependency_overrides[get_session] = _request_scoped_session
    yield
    app.dependency_overrides.clear()


@pytest_asyncio.fixture(loop_scope="session", scope="session")
async def engine():
    """Engine used by `db_session` for cross-test truncation."""
    eng = create_async_engine(os.environ["DATABASE_URL"], pool_pre_ping=True)
    yield eng
    await eng.dispose()


@pytest_asyncio.fixture(loop_scope="session")
async def db_session(engine) -> AsyncSession:
    async with engine.begin() as conn:
        await conn.execute(
            text(f"TRUNCATE {', '.join(_TABLES)} RESTART IDENTITY CASCADE")
        )
    SessionLocal = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)
    async with SessionLocal() as session:
        yield session


@pytest_asyncio.fixture(loop_scope="session")
async def client():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac
