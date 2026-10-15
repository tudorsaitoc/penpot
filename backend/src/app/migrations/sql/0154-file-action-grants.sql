-- This Source Code Form is subject to the terms of the Mozilla Public
-- License, v. 2.0. If a copy of the MPL was not distributed with this
-- file, You can obtain one at http://mozilla.org/MPL/2.0/.
--
-- Copyright (c) 2026 Tudor Saitoc

-- NULL marks legacy history whose incarnation cannot be proved. Never infer it
-- from the current file after restore. New snapshots and changes always tag it.
ALTER TABLE file_change ADD COLUMN vern integer;
CREATE INDEX file_change_exact_revision_idx ON file_change (file_id, vern, revn);

CREATE TABLE file_action_grant (
  id uuid PRIMARY KEY,
  file_id uuid NOT NULL REFERENCES file(id) ON DELETE CASCADE,
  actor_id uuid NOT NULL REFERENCES profile(id) ON DELETE CASCADE,
  request_id uuid NOT NULL,
  session_id uuid NOT NULL,
  revn integer NOT NULL CHECK (revn >= 0),
  vern integer NOT NULL CHECK (vern >= 0),
  args_sha256 text NOT NULL CHECK (args_sha256 ~ '^[0-9a-f]{64}$'),
  binding bytea NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL DEFAULT now() + interval '15 minutes',
  replay_until timestamptz NOT NULL DEFAULT now() + interval '30 days',
  revoked_at timestamptz,
  consumed_at timestamptz,
  receipt bytea,
  UNIQUE (actor_id, request_id),
  CHECK ((receipt IS NULL) = (consumed_at IS NULL))
);
-- Retention is enforced on issue: 15-minute use window, 30-day exact replay.
-- File/profile deletion also cascades. No separate document copy is stored.
CREATE INDEX file_action_grant_retention_idx ON file_action_grant (replay_until);
