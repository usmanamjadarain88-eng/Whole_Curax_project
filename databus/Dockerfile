# Data Bus WebSocket server — long-running process (Docker, Fly.io, ECS, VPS, etc.).
# Vercel deploys only serverless routes under api/; run this image where persistent WS is supported.
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY server.py .

ENV PORT=5052
EXPOSE 5052

CMD ["python", "server.py"]
