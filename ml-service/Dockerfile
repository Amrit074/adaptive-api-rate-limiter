FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY anomaly_detector.py .

CMD ["python", "-u", "anomaly_detector.py"]
