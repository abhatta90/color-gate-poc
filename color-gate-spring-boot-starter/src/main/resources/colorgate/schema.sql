-- Color gate table. Only the deployment pipeline writes to it; every pod reads its row once per poll interval.
-- No row for a service means nobody is active.
CREATE TABLE IF NOT EXISTS color_gate (
    service_name VARCHAR(100) PRIMARY KEY,
    active_color VARCHAR(10)  NOT NULL CHECK (active_color IN ('BLUE', 'GREEN')),
    switched_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    switched_by  VARCHAR(200)
);
