-- Ticket #15.3: POST /v1/analyze (AD-129). Forward-only; the tables it touches are empty before this ticket.

-- C-52: an analysis starts only for a session still reserved (or committed, for attempts 2 and 3). The start sets
-- analysing_since in one conditional update; a release the person sends while it is set is ignored, so a released
-- take can never be committed over the day's cap. A failure clears it and gives the session back; a crashed run is
-- treated as over after the setting analysis_stale_seconds, and the 48 h job still frees a session left reserved.
ALTER TABLE practice_sessions ADD COLUMN analysing_since timestamptz;
COMMENT ON COLUMN practice_sessions.analysing_since IS 'Set while /analyze runs for this session (C-52); null otherwise.';

-- facts_records keeps every number the next attempt's focus check and the personal pace band read (03 F-033,
-- F-027), so the server never trusts the phone for them (D-11: numbers and labels only, no words).
ALTER TABLE facts_records
  ADD COLUMN session_id             uuid,
  ADD COLUMN attempt_no             smallint,
  ADD COLUMN speech_ms              int,
  ADD COLUMN filler_clusters        smallint,
  ADD COLUMN longest_sentence_words smallint,
  ADD COLUMN run_on_labelled        boolean,
  ADD COLUMN window_wpm             smallint[],
  ADD COLUMN pace_band_low          smallint,
  ADD COLUMN pace_band_high         smallint,
  ADD COLUMN hit_cap                boolean,
  ADD COLUMN fix_target             smallint,
  ADD COLUMN fix_carried            boolean,
  ADD COLUMN fix_nothing_major      boolean,
  ADD COLUMN average_wpm            smallint,
  ADD COLUMN score_total            smallint,
  ADD COLUMN next_attempt_allowed   boolean;
CREATE INDEX ON facts_records (user_id, session_id);
CREATE INDEX ON facts_records (user_id, created_at DESC);   -- the last 10 paces for the personal band
COMMENT ON COLUMN facts_records.window_wpm IS 'Words a minute in each 10 s window: numbers only.';
