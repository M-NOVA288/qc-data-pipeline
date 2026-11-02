-- Quality control schema for multi-stage product testing.
-- Written for PostgreSQL; works on most SQL engines with minor type changes.

-- Production stages in order (e.g. MIX -> CURE -> PACK).
CREATE TABLE stages (
    stage_code   VARCHAR(16) PRIMARY KEY,
    stage_order  SMALLINT    NOT NULL UNIQUE,
    description  VARCHAR(100) NOT NULL
);

-- Acceptable range for each metric at each stage.
CREATE TABLE spec_limits (
    stage_code   VARCHAR(16)  NOT NULL REFERENCES stages(stage_code),
    metric       VARCHAR(32)  NOT NULL,
    unit         VARCHAR(16)  NOT NULL,
    min_value    NUMERIC(12,4) NOT NULL,
    max_value    NUMERIC(12,4) NOT NULL,
    PRIMARY KEY (stage_code, metric),
    CHECK (min_value < max_value)
);

CREATE TABLE batches (
    batch_id     VARCHAR(20) PRIMARY KEY,
    product_code VARCHAR(20) NOT NULL,
    started_at   TIMESTAMP   NOT NULL
);

-- One row per accepted reading. Only validated data lands here.
CREATE TABLE measurements (
    measurement_id BIGSERIAL PRIMARY KEY,
    batch_id       VARCHAR(20)  NOT NULL REFERENCES batches(batch_id),
    stage_code     VARCHAR(16)  NOT NULL REFERENCES stages(stage_code),
    metric         VARCHAR(32)  NOT NULL,
    value          NUMERIC(12,4) NOT NULL,
    unit           VARCHAR(16)  NOT NULL,
    technician     VARCHAR(40)  NOT NULL,
    measured_at    TIMESTAMP    NOT NULL,
    FOREIGN KEY (stage_code, metric) REFERENCES spec_limits(stage_code, metric)
);

-- Readings that are valid data but outside spec: real product issues to investigate.
CREATE TABLE anomalies (
    anomaly_id     BIGSERIAL PRIMARY KEY,
    measurement_id BIGINT      NOT NULL REFERENCES measurements(measurement_id),
    direction      VARCHAR(5)  NOT NULL CHECK (direction IN ('LOW', 'HIGH')),
    deviation      NUMERIC(12,4) NOT NULL,
    status         VARCHAR(12) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','REVIEWED','CLOSED')),
    created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Rows that never made it in: format errors and statistical outliers, kept for audit.
CREATE TABLE rejected_rows (
    rejected_id  BIGSERIAL PRIMARY KEY,
    source_file  VARCHAR(200) NOT NULL,
    line_number  INTEGER      NOT NULL,
    raw_line     TEXT         NOT NULL,
    reason       VARCHAR(200) NOT NULL,
    rejected_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_measurements_batch_stage ON measurements (batch_id, stage_code);
CREATE INDEX idx_measurements_metric_time ON measurements (stage_code, metric, measured_at);
CREATE INDEX idx_anomalies_open ON anomalies (status) WHERE status = 'OPEN';

INSERT INTO stages VALUES
    ('MIX',  1, 'Resin and additive blending'),
    ('CURE', 2, 'Heat cure'),
    ('PACK', 3, 'Final inspection and packaging');
