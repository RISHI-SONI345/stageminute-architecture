-- Ticket #15.4: GET /v1/analyses lists the topic of each report (06-API section 7). The reports module may not read
-- practice_sessions (module rule), so the topic id is kept on the report itself when it is saved. Forward-only.
ALTER TABLE reports ADD COLUMN topic_id text;
COMMENT ON COLUMN reports.topic_id IS 'The topic of the take, copied at save so the history list needs no other table.';
