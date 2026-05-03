"""
FastAPI dependencies for protected endpoints.

The bearer-token dependency parses the `Authorization` header, verifies the
HMAC, and returns the bound fingerprint. URL-fingerprint authorization (the
caller's token must match the {fingerprint} path param) is intentionally
performed *inside each endpoint* rather than in a dependency, so the 403
distinction between "no token" and "wrong token for this resource" is
explicit at the call site.
"""
from typing import Annotated

from fastapi import Header, HTTPException, status

from app import tokens


async def require_session_token(
    authorization: Annotated[str | None, Header()] = None,
) -> str:
    """Return the fingerprint bound by a valid Bearer token, else 401."""
    if not authorization:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="missing bearer token",
        )

    scheme, _, value = authorization.partition(" ")
    if scheme.lower() != "bearer" or not value:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="malformed authorization header",
        )

    try:
        return tokens.verify(value)
    except tokens.InvalidToken as e:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=f"invalid token: {e}",
        ) from e
