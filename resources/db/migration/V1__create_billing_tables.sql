CREATE TABLE administrators (
    user_id VARCHAR(128) PRIMARY KEY
);

CREATE TABLE active_guests (
    user_id VARCHAR(128) PRIMARY KEY,
    entered_at_ms BIGINT NOT NULL CHECK (entered_at_ms >= 0)
);

CREATE TABLE balances (
    user_id VARCHAR(128) PRIMARY KEY,
    amount_minor BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE rates (
    start_minute INTEGER PRIMARY KEY,
    end_minute INTEGER NOT NULL,
    amount_per_half_hour BIGINT NOT NULL,
    max_amount BIGINT NOT NULL,
    CHECK (start_minute BETWEEN 0 AND 1440),
    CHECK (end_minute BETWEEN 0 AND 2880),
    CHECK (end_minute > start_minute),
    CHECK (end_minute - start_minute <= 1440),
    CHECK (amount_per_half_hour >= 0),
    CHECK (max_amount = -1 OR max_amount >= 0)
);

CREATE TABLE operations (
    requested_at_ms BIGINT NOT NULL CHECK (requested_at_ms >= 0),
    processed_at_ms BIGINT NOT NULL CHECK (processed_at_ms >= 0),
    operator_id VARCHAR(128) NOT NULL,
    target_id VARCHAR(128) NOT NULL,
    type VARCHAR(32) NOT NULL,
    allowed BOOLEAN NOT NULL,
    note TEXT NOT NULL DEFAULT '',
    bill_minor BIGINT,
    delta_minor BIGINT,
    balance_after_minor BIGINT,
    CONSTRAINT operations_pkey PRIMARY KEY (requested_at_ms, operator_id),
    CONSTRAINT operations_type_check CHECK (
        type IN (
            'LOGIN',
            'LOGOUT',
            'ADMIN_ADD',
            'ADMIN_REMOVE',
            'ADMIN_ADJUST',
            'BALANCE_QUERY',
            'CHANGES_QUERY',
            'BILL_QUERY',
            'RATE_UPDATE',
            'DEBTS_QUERY'
        )
    ),
    CONSTRAINT operations_balance_fields_check CHECK (
        (
            type = 'ADMIN_ADJUST'
            AND bill_minor IS NULL
            AND delta_minor IS NOT NULL
            AND delta_minor <> 0
            AND balance_after_minor IS NOT NULL
        )
        OR
        (
            type = 'LOGOUT'
            AND allowed = TRUE
            AND bill_minor IS NOT NULL
            AND bill_minor >= 0
            AND delta_minor = -bill_minor
            AND balance_after_minor IS NOT NULL
        )
        OR
        (
            NOT (type = 'ADMIN_ADJUST' OR (type = 'LOGOUT' AND allowed = TRUE))
            AND bill_minor IS NULL
            AND delta_minor IS NULL
            AND balance_after_minor IS NULL
        )
    )
);

CREATE INDEX operations_balance_history_idx
    ON operations (target_id, requested_at_ms DESC, operator_id DESC)
    WHERE allowed = TRUE AND delta_minor IS NOT NULL AND delta_minor <> 0;

CREATE INDEX balances_debts_idx
    ON balances (amount_minor, user_id)
    WHERE amount_minor < 0;
