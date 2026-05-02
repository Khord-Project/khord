from fastapi import APIRouter, status
from fastapi.responses import JSONResponse

router = APIRouter(prefix="/auth", tags=["auth"])

_NOT_IMPLEMENTED = JSONResponse(
    status_code=status.HTTP_501_NOT_IMPLEMENTED,
    content={"detail": "not implemented"},
)


@router.get("/challenge/{fingerprint}", status_code=status.HTTP_501_NOT_IMPLEMENTED)
async def request_challenge(fingerprint: str) -> JSONResponse:
    return _NOT_IMPLEMENTED


@router.post("/verify", status_code=status.HTTP_501_NOT_IMPLEMENTED)
async def verify_challenge() -> JSONResponse:
    return _NOT_IMPLEMENTED
