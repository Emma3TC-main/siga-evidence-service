CREATE TABLE evidence.evidence_metadata (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type varchar(30) NOT NULL CHECK (owner_type = 'MOVEMENT'),
    owner_id uuid NOT NULL,
    object_key varchar(500) NOT NULL UNIQUE,
    original_filename varchar(255) NOT NULL,
    content_type varchar(120) NOT NULL CHECK (content_type IN ('application/pdf','image/jpeg','image/png')),
    size_bytes bigint NOT NULL CHECK (size_bytes > 0),
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    storage_provider varchar(30) NOT NULL CHECK (storage_provider = 'MINIO'),
    uploaded_by_user_id uuid NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED','VALIDATING','STORED','AVAILABLE','REJECTED','ARCHIVED')),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(metadata) = 'object' AND octet_length(metadata::text) <= 16384),
    uploaded_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_evidence_owner
    ON evidence.evidence_metadata(owner_type, owner_id, uploaded_at DESC, id DESC);
CREATE INDEX idx_evidence_uploader
    ON evidence.evidence_metadata(uploaded_by_user_id, uploaded_at DESC);
CREATE INDEX idx_evidence_sha256 ON evidence.evidence_metadata(sha256);

CREATE TABLE evidence.upload_idempotency (
    idempotency_key uuid PRIMARY KEY,
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    evidence_id uuid NOT NULL UNIQUE,
    status varchar(20) NOT NULL CHECK (status IN ('PROCESSING','COMPLETED','FAILED')),
    response_status smallint,
    response_payload jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (status <> 'COMPLETED' OR (response_status = 201 AND response_payload IS NOT NULL))
);
CREATE INDEX idx_evidence_idempotency_status_updated
    ON evidence.upload_idempotency(status, updated_at);

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
CREATE INDEX idx_evidence_outbox_pending
    ON evidence.outbox_event(status, next_attempt_at, occurred_at) WHERE status='PENDING';
