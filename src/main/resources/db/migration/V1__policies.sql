CREATE TABLE policy_revision
(
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    revision  bigint NOT NULL
);
INSERT INTO policy_revision
VALUES (true, 0);
CREATE TABLE policies
(
    id       varchar(64) PRIMARY KEY,
    version  bigint NOT NULL,
    document jsonb  NOT NULL
);
CREATE TABLE policy_history
(
    revision   bigint PRIMARY KEY,
    policy_id  varchar(64) NOT NULL,
    actor      varchar(64) NOT NULL,
    document   jsonb       NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX policy_history_id ON policy_history (policy_id, revision DESC);
