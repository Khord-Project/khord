"""
Test fixtures for the Key Server.

  * Connects to the SAME Postgres database the running server uses (the test
    DB lives inside the keyserver-db container; from the host it is
    unreachable by design — see ADR 002 — so tests run inside the keyserver
    container via `docker compose exec keyserver pytest`).
  * Truncates every table before each test for full isolation.
  * Provides an `httpx.AsyncClient` bound to the FastAPI app via
    ASGITransport — no real network involved.
"""
import os

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

# Force a deterministic token secret for tests so issued tokens are stable.
os.environ.setdefault(
    "KEY_SERVER_TOKEN_SECRET", "test-secret-do-not-use-in-production"
)
os.environ.setdefault(
    "DATABASE_URL",
    "postgresql+asyncpg://keyserver:keyserver_dev_password@keyserver-db:5432/keyserver",
)

from app.database import Base, get_session  # noqa: E402  (after env setup)
from app.main import app  # noqa: E402
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


_TABLES = [
    "auth_challenges",
    "one_time_pre_keys",
    "identity_keys",
]


@pytest_asyncio.fixture(loop_scope="session", scope="session")
async def engine():
    eng = create_async_engine(os.environ["DATABASE_URL"], pool_pre_ping=True)
    async with eng.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield eng
    await eng.dispose()


@pytest_asyncio.fixture(loop_scope="session")
async def db_session(engine) -> AsyncSession:
    """Truncate tables, then yield a session bound to the fixture engine."""
    async with engine.begin() as conn:
        # RESTART IDENTITY resets the auto-increment counters so id values
        # are predictable across tests.
        await conn.execute(
            text(
                f"TRUNCATE {', '.join(_TABLES)} RESTART IDENTITY CASCADE"
            )
        )
    SessionLocal = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)
    async with SessionLocal() as session:
        yield session


@pytest_asyncio.fixture(loop_scope="session")
async def client(engine):
    """An httpx AsyncClient that drives the FastAPI app via ASGI in-process."""
    SessionLocal = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)

    async def _override_session():
        async with SessionLocal() as session:
            yield session

    app.dependency_overrides[get_session] = _override_session
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac
    app.dependency_overrides.clear()
