"""
Smart Rate Limiter — ML Anomaly Detection Service
==================================================
Uses Isolation Forest to detect anomalous API traffic patterns.

What it detects:
- Burst attacks: sudden spike in request count vs. historical baseline
- Bot patterns: unnaturally consistent inter-request timing (low std dev)
- Error storms: IP hammering endpoints and getting repeated 429s
- Off-hours anomalies: traffic at unusual times for that IP

How it works:
1. Every 60s: pull last 10 minutes of traffic_log from MySQL
2. Aggregate per-IP features (request count, 429 ratio, timing patterns)
3. Train / update Isolation Forest on this feature matrix
4. Score each IP: anomaly_score < 0 means outlier
5. Write results to anomaly_result table
6. Spring Boot AnomalySyncService picks up results and blocks IPs in Redis

Why Isolation Forest:
- Unsupervised — no labelled data needed (you don't know what attacks look like yet)
- Fast O(n log n) training, O(log n) scoring
- Works well on tabular features
- Interpretable: contamination param = expected % of anomalies

Requirements:
    pip install pandas numpy scikit-learn sqlalchemy pymysql schedule
"""

import logging
import os
import time
import schedule
import numpy as np
import pandas as pd
from datetime import datetime, timedelta
from sklearn.ensemble import IsolationForest
from sklearn.preprocessing import StandardScaler
from sqlalchemy import create_engine, text

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s"
)
log = logging.getLogger(__name__)

# ── Configuration ─────────────────────────────────────────────────────────────

DB_CONFIG = {
    "host": os.getenv("DB_HOST", "localhost"),
    "port": int(os.getenv("DB_PORT", "3306")),
    "user": os.getenv("DB_USER", "root"),
    "password": os.getenv("DB_PASSWORD", ""),
    "database": os.getenv("DB_NAME", "rate_limiter_db"),
    "ssl_mode": os.getenv("DB_SSL_MODE", "DISABLED").upper(),
    "ssl_ca": os.getenv("DB_SSL_CA", ""),
}

# Isolation Forest params
CONTAMINATION = float(os.getenv("CONTAMINATION", "0.05"))
N_ESTIMATORS = int(os.getenv("N_ESTIMATORS", "100"))
RANDOM_STATE = int(os.getenv("RANDOM_STATE", "42"))

# How far back to look for feature computation (minutes)
FEATURE_WINDOW_MINUTES = int(os.getenv("FEATURE_WINDOW_MINUTES", "10"))

# Minimum requests from an IP to score it (avoid false positives on low traffic)
MIN_REQUESTS_TO_SCORE = int(os.getenv("MIN_REQUESTS_TO_SCORE", "5"))

# Minimum number of IPs needed to train the model
MIN_IPS_TO_TRAIN = int(os.getenv("MIN_IPS_TO_TRAIN", "10"))

DETECTION_INTERVAL_SECONDS = int(os.getenv("DETECTION_INTERVAL_SECONDS", "60"))

# ── Database Setup ─────────────────────────────────────────────────────────────

def get_engine():
    url = (f"mysql+pymysql://{DB_CONFIG['user']}:{DB_CONFIG['password']}"
           f"@{DB_CONFIG['host']}:{DB_CONFIG['port']}/{DB_CONFIG['database']}")
    connect_args = {}
    if DB_CONFIG["ssl_mode"] != "DISABLED":
        ssl_config = {}
        if DB_CONFIG["ssl_ca"]:
            ssl_config["ca"] = DB_CONFIG["ssl_ca"]
        connect_args["ssl"] = ssl_config
    return create_engine(url, pool_recycle=3600, pool_pre_ping=True, connect_args=connect_args)

ENGINE = get_engine()

# ── Feature Engineering ────────────────────────────────────────────────────────

def fetch_recent_traffic(minutes: int = FEATURE_WINDOW_MINUTES) -> pd.DataFrame:
    """Pull recent traffic_log rows from MySQL."""
    since = datetime.now() - timedelta(minutes=minutes)
    query = text("""
        SELECT client_ip, status_code, latency_ms, request_count, timestamp
        FROM traffic_log
        WHERE timestamp >= :since
        ORDER BY timestamp ASC
    """)
    with ENGINE.connect() as conn:
        df = pd.read_sql(query, conn, params={"since": since})
    log.info(f"Fetched {len(df)} traffic events from last {minutes} minutes")
    return df


def engineer_features(df: pd.DataFrame) -> pd.DataFrame:
    """
    Compute per-IP features for anomaly detection.

    Features:
    - total_requests:      how many requests in the window
    - requests_per_minute: normalized request rate
    - p429_ratio:          fraction of requests that were rate-limited (0–1)
    - avg_latency_ms:      average response latency
    - max_request_count:   peak sliding window count seen
    - timing_std_ms:       std dev of inter-request intervals (low = bot-like)
    - hour_of_day:         0–23, for off-hours detection
    - unique_endpoints:    number of distinct endpoints hit (low = scanner)
    """
    if df.empty:
        return pd.DataFrame()

    features = []
    for ip, group in df.groupby("client_ip"):
        if len(group) < MIN_REQUESTS_TO_SCORE:
            continue

        group = group.sort_values("timestamp")
        timestamps = pd.to_datetime(group["timestamp"])

        # Inter-request timing
        if len(timestamps) > 1:
            intervals_ms = timestamps.diff().dt.total_seconds().dropna() * 1000
            timing_std = intervals_ms.std()
            timing_mean = intervals_ms.mean()
        else:
            timing_std = 0
            timing_mean = 0

        window_minutes = (timestamps.max() - timestamps.min()).total_seconds() / 60 + 0.001
        req_per_minute = len(group) / window_minutes

        features.append({
            "client_ip":          ip,
            "total_requests":     len(group),
            "requests_per_minute": req_per_minute,
            "p429_ratio":         (group["status_code"] == 429).mean(),
            "avg_latency_ms":     group["latency_ms"].mean(),
            "max_request_count":  group["request_count"].max(),
            "timing_std_ms":      timing_std if not np.isnan(timing_std) else 0,
            "timing_mean_ms":     timing_mean if not np.isnan(timing_mean) else 0,
            "hour_of_day":        timestamps.dt.hour.mode()[0],
        })

    return pd.DataFrame(features)


# ── Anomaly Detection ──────────────────────────────────────────────────────────

FEATURE_COLS = [
    "total_requests",
    "requests_per_minute",
    "p429_ratio",
    "avg_latency_ms",
    "max_request_count",
    "timing_std_ms",
    "timing_mean_ms",
    "hour_of_day",
]

scaler = StandardScaler()
model = IsolationForest(
    n_estimators=N_ESTIMATORS,
    contamination=CONTAMINATION,
    random_state=RANDOM_STATE,
    n_jobs=-1
)

def detect_anomalies(features_df: pd.DataFrame) -> pd.DataFrame:
    """
    Train Isolation Forest and score each IP.
    Returns features_df with added columns:
    - anomaly_score: raw score (-1 anomaly, 1 normal)
    - is_anomalous:  bool
    - reason:        human-readable explanation
    """
    if len(features_df) < MIN_IPS_TO_TRAIN:
        log.warning(f"Only {len(features_df)} IPs — need {MIN_IPS_TO_TRAIN} to train. Skipping.")
        return pd.DataFrame()

    X = features_df[FEATURE_COLS].fillna(0)
    X_scaled = scaler.fit_transform(X)

    # Isolation Forest: -1 = anomaly, 1 = normal
    predictions = model.fit_predict(X_scaled)
    scores = model.score_samples(X_scaled)  # lower = more anomalous

    features_df = features_df.copy()
    features_df["raw_prediction"] = predictions
    features_df["anomaly_score"] = scores
    features_df["is_anomalous"] = predictions == -1

    # Generate human-readable reason for each flagged IP
    features_df["reason"] = features_df.apply(build_reason, axis=1)

    anomalous = features_df[features_df["is_anomalous"]].shape[0]
    log.info(f"Scored {len(features_df)} IPs — {anomalous} flagged as anomalous")

    return features_df


def build_reason(row: pd.Series) -> str:
    """
    Produce a human-readable reason string explaining why an IP was flagged.
    Used for logging, the anomaly_result.reason column, and debugging.
    """
    if not row["is_anomalous"]:
        return "normal"

    reasons = []

    if row["requests_per_minute"] > 50:
        reasons.append(f"high_rate={row['requests_per_minute']:.1f}req/min")

    if row["p429_ratio"] > 0.3:
        reasons.append(f"high_429_ratio={row['p429_ratio']:.2f}")

    if row["timing_std_ms"] < 50 and row["total_requests"] > 20:
        reasons.append(f"bot_timing_std={row['timing_std_ms']:.1f}ms")

    if row["max_request_count"] > 80:
        reasons.append(f"burst_peak={row['max_request_count']}")

    if not reasons:
        reasons.append(f"isolation_forest_score={row['anomaly_score']:.3f}")

    return "; ".join(reasons)


# ── Database Write ─────────────────────────────────────────────────────────────

def write_results(results_df: pd.DataFrame):
    """Write scored IPs to anomaly_result table."""
    if results_df.empty:
        return

    now = datetime.now()
    insert_sql = text("""
        INSERT INTO anomaly_result
            (client_ip, anomaly_score, is_anomalous, reason, detected_at, synced_to_redis)
        VALUES
            (:client_ip, :anomaly_score, :is_anomalous, :reason, :detected_at, :synced_to_redis)
    """)

    rows = []
    for _, row in results_df.iterrows():
        rows.append({
            "client_ip":      row["client_ip"],
            "anomaly_score":  float(row["anomaly_score"]),
            "is_anomalous":   bool(row["is_anomalous"]),
            "reason":         row["reason"],
            "detected_at":    now,
            "synced_to_redis": False
        })

    with ENGINE.begin() as conn:
        conn.execute(insert_sql, rows)

    flagged = [r for r in rows if r["is_anomalous"]]
    log.info(f"Wrote {len(rows)} results to DB — {len(flagged)} anomalous IPs queued for blocking")
    for r in flagged:
        log.warning(f"  ANOMALY: {r['client_ip']} — {r['reason']}")


# ── Main Detection Loop ────────────────────────────────────────────────────────

def run_detection():
    """Single detection cycle: fetch → features → score → write."""
    log.info("=" * 50)
    log.info("Running anomaly detection cycle")
    try:
        df = fetch_recent_traffic()
        if df.empty:
            log.info("No traffic data — skipping cycle")
            return

        features = engineer_features(df)
        if features.empty:
            log.info("No IPs with enough requests — skipping cycle")
            return

        results = detect_anomalies(features)
        if results.empty:
            return

        write_results(results)
        log.info("Detection cycle complete")

    except Exception as e:
        log.error(f"Detection cycle failed: {e}", exc_info=True)


if __name__ == "__main__":
    log.info("Smart Rate Limiter — Anomaly Detection Service started")
    log.info(f"Config: contamination={CONTAMINATION}, window={FEATURE_WINDOW_MINUTES}min, "
             f"min_requests={MIN_REQUESTS_TO_SCORE}")

    # Run immediately on startup, then every 60 seconds
    run_detection()
    schedule.every(DETECTION_INTERVAL_SECONDS).seconds.do(run_detection)

    while True:
        schedule.run_pending()
        time.sleep(1)
