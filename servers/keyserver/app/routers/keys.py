from fastapi import APIRouter, status
from fastapi.responses import JSONResponse

router = APIRouter(prefix="/keys", tags=["keys"])

_NOT_IMPLEMENTED = JSONResponse(
    status_code=status.HTTP_501_NOT_IMPLEMENTED,
    content={"detail": "not implemented"},
)


@router.post("/{fingerprint}/bundle", status_code=status.HTTP_501_NOT_IMPLEMENTED)
async def upload_pre_key_bundle(fingerprint: str) -> JSONResponse:
    return _NOT_IMPLEMENTED


@router.get("/{fingerprint}/bundle", status_code=status.HTTP_501_NOT_IMPLEMENTED)
async def fetch_pre_key_bundle(fingerprint: str) -> JSONResponse:
    return _NOT_IMPLEMENTED


@router.post("/{fingerprint}/prekeys", status_code=status.HTTP_501_NOT_IMPLEMENTED)
async def replenish_pre_keys(fingerprint: str) -> JSONResponse:
    return _NOT_IMPLEMENTED


@router.get("/{fingerprint}/prekey-count", status_code=status.HTTP_501_NOT_IMPLEMENTED)
async def pre_key_count(fingerprint: str) -> JSONResponse:
    return _NOT_IMPLEMENTED
