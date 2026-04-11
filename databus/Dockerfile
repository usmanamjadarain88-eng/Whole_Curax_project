# Data Bus WebSocket server - deploy anywhere (Railway, Fly.io, ECS, etc.)
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY server.py .

ENV PORT=5052
EXPOSE 5052

CMD ["python", "server.py"]
