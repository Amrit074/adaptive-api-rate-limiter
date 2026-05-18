# Deploying on Render with Aiven

This project deploys to Render as two Docker services:

- `smart-rate-limiter-api`: public Spring Boot web service
- `smart-rate-limiter-ml`: background Python anomaly detection worker

Aiven provides the external MySQL database and Redis-compatible cache.

## 1. Create Aiven services

Create these in Aiven:

- MySQL 8 service
- Redis-compatible cache service

Copy the host, port, username, password, and database name from Aiven.

## 2. Deploy with Render Blueprint

Push this repository to GitHub, then create a Render Blueprint from `render.yaml`.

Render will ask for the environment variables marked `sync: false`.

## 3. API service environment variables

Set these on `smart-rate-limiter-api`:

```text
SPRING_DATASOURCE_URL=jdbc:mysql://AIVEN_MYSQL_HOST:AIVEN_MYSQL_PORT/AIVEN_MYSQL_DATABASE?useSSL=true&requireSSL=true&verifyServerCertificate=false
SPRING_DATASOURCE_USERNAME=avnadmin
SPRING_DATASOURCE_PASSWORD=AIVEN_MYSQL_PASSWORD
SPRING_JPA_HIBERNATE_DDL_AUTO=update
SPRING_DATA_REDIS_HOST=AIVEN_REDIS_HOST
SPRING_DATA_REDIS_PORT=AIVEN_REDIS_PORT
SPRING_DATA_REDIS_USERNAME=default
SPRING_DATA_REDIS_PASSWORD=AIVEN_REDIS_PASSWORD
SPRING_DATA_REDIS_SSL_ENABLED=true
MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=never
```

If your Aiven Redis service does not use a username, leave `SPRING_DATA_REDIS_USERNAME` empty.

## 4. ML worker environment variables

Set these on `smart-rate-limiter-ml`:

```text
DB_HOST=AIVEN_MYSQL_HOST
DB_PORT=AIVEN_MYSQL_PORT
DB_USER=avnadmin
DB_PASSWORD=AIVEN_MYSQL_PASSWORD
DB_NAME=AIVEN_MYSQL_DATABASE
DB_SSL_MODE=REQUIRED
DETECTION_INTERVAL_SECONDS=60
```

## 5. Test after deploy

Open the Render URL for `smart-rate-limiter-api`:

```text
https://YOUR-RENDER-SERVICE.onrender.com/swagger-ui.html
```

Try:

```text
GET /api/hello
GET /api/metrics/summary
GET /api/metrics/top-ips
GET /api/metrics/anomalies
```

To test rate limiting, lower `RATE_LIMITER_MAX_REQUESTS` temporarily and call `/api/hello` repeatedly until you receive `429 Too Many Requests`.
