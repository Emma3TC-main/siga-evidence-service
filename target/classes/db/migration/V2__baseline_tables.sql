-- TODO: Pegar aquí los CREATE TABLE correspondientes al esquema 'evidence'.
-- REGLA: 0 FK hacia otros esquemas. Relaciones inter-dominio solo por ID.
-- ============================================================
-- EVIDENCE / evidence-service
-- ============================================================
CREATE TABLE evidence.evidence_metadata (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type varchar(30) NOT NULL CHECK (owner_type IN ('MOVEMENT','ASSET','ADJUSTMENT','REPORT')),
    owner_id uuid NOT NULL,                   -- external owner reference
    object_key varchar(500) NOT NULL UNIQUE,
    original_filename varchar(255) NOT NULL,
    content_type varchar(120) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes > 0),
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-fA-F]{64}$'),
    storage_provider varchar(30) NOT NULL CHECK (storage_provider IN ('MINIO','GCS')),
    uploaded_by_user_id uuid NOT NULL,        -- external IAM reference
    status varchar(30) NOT NULL DEFAULT 'RECEIVED' CHECK (status IN (
        'RECEIVED','VALIDATING','STORED','AVAILABLE','REJECTED','ARCHIVED'
    )),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    uploaded_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_evidence_owner ON evidence.evidence_metadata(owner_type, owner_id);
CREATE INDEX idx_evidence_uploader ON evidence.evidence_metadata(uploaded_by_user_id, uploaded_at DESC);
CREATE INDEX idx_evidence_sha256 ON evidence.evidence_metadata(sha256);

CREATE TABLE evidence.outbox_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type varchar(80) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(120) NOT NULL,
    schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version > 0),
    correlation_id uuid,
    causation_id uuid,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PUBLISHED','FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
CREATE INDEX idx_evidence_outbox_pending ON evidence.outbox_event(status, next_attempt_at, occurred_at) WHERE status='PENDING';

-- ============================================================
-- AUDIT + NOTIFICATION / audit-notification-service
-- ============================================================
CREATE TABLE audit.audit_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type varchar(120) NOT NULL,
    actor_id uuid,                            -- external IAM reference
    aggregate_type varchar(80),
    aggregate_id uuid,
    action varchar(120) NOT NULL,
    result varchar(30) NOT NULL,
    correlation_id uuid,
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_occurred ON audit.audit_event(occurred_at DESC);
CREATE INDEX idx_audit_correlation ON audit.audit_event(correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX idx_audit_aggregate ON audit.audit_event(aggregate_type, aggregate_id, occurred_at DESC);
CREATE INDEX idx_audit_actor ON audit.audit_event(actor_id, occurred_at DESC) WHERE actor_id IS NOT NULL;

CREATE TABLE audit.notification (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,                   -- external IAM reference
    type varchar(80) NOT NULL,
    title varchar(160) NOT NULL,
    message text NOT NULL,
    reference_type varchar(80),
    reference_id uuid,
    read_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_user_unread ON audit.notification(user_id, created_at DESC) WHERE read_at IS NULL;
CREATE INDEX idx_notification_user_created ON audit.notification(user_id, created_at DESC);

CREATE TABLE audit.processed_event (
    event_id uuid NOT NULL,
    consumer varchar(120) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);