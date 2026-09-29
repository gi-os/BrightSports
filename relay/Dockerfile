FROM python:3.12-slim
RUN pip install --no-cache-dir aiohttp==3.10.10 websockets==13.1 httpx[http2]==0.27.2 PyJWT[crypto]==2.9.0
WORKDIR /app
COPY fastcast.py main.py tape.py milb.py /app/
ENV PYTHONUNBUFFERED=1
CMD ["python", "main.py"]
