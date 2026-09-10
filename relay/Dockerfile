FROM python:3.12-slim
RUN pip install --no-cache-dir aiohttp==3.10.10 websockets==13.1
WORKDIR /app
COPY fastcast.py main.py /app/
ENV PYTHONUNBUFFERED=1
CMD ["python", "main.py"]
