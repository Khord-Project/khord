"""Server metadata endpoints (currently just PoW parameters)."""
from fastapi import APIRouter

from app.config import settings
from app.schemas import PowParamsResponse

router = APIRouter(tags=["meta"])


@router.get("/pow-params", response_model=PowParamsResponse)
async def pow_params() -> PowParamsResponse:
    """Public — return the current PoW parameters so clients can mine.

    Unauthenticated. Returns no per-client or per-request data.
    """
    return PowParamsResponse(
        difficulty_bits=settings.proof_of_work_difficulty_bits,
        mailbox_id_min_length=settings.mailbox_id_min_length,
    )
