FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install -r requirements.txt

COPY alembic.ini .
COPY alembic ./alembic
COPY app ./app
COPY tests ./tests
COPY pytest.ini .

EXPOSE 8000

# --no-access-log is load-bearing: uvicorn's default access log embeds request
# paths (which contain mailbox IDs), violating ADR 016 (allow-list logging).
#
# --proxy-headers + --forwarded-allow-ips make the per-IP rate limits and WS
# connection caps see the REAL client address from X-Forwarded-For instead of
# the reverse proxy's container IP (which would lump all clients into one
# bucket). FORWARDED_ALLOW_IPS must list the trusted proxy network; uvicorn
# then walks X-Forwarded-For past those trusted hops to the real client, which
# also makes the header spoof-resistant. Defaults to 127.0.0.1 (no proxy
# trusted) so a direct/dev deployment behaves unchanged; production composes
# set it to the internal proxy network.
CMD ["sh", "-c", "alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port 8000 --no-access-log --proxy-headers --forwarded-allow-ips ${FORWARDED_ALLOW_IPS:-127.0.0.1}"]
