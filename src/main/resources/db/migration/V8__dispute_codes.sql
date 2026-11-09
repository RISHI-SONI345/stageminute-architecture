-- Ticket #17.1 (issue #79, AD-147; 02b F-042, C-67): what a score dispute points at, as codes and numbers only.
-- Expand only: four nullable columns, a closed list of areas and two rules. The table had no rows before #17.1.
--   item_index       which step (0 to 3) or which language suggestion (its id in the report) was reported
--   fix_code         the fix the one-tap "This fix felt wrong" is about, read from the stored report (never the phone)
--   formula_version  the report's score formula, read from the stored report
--   prompt_version   the report's prompt, read from the stored report
ALTER TABLE score_disputes
  ADD COLUMN item_index smallint CHECK (item_index >= 0),
  ADD COLUMN fix_code text,
  ADD COLUMN formula_version text,
  ADD COLUMN prompt_version text,
  ADD CONSTRAINT ck_score_disputes_area CHECK (area IN
    ('structure_step','language_suggestion','pause','pace','filler','score','other','fix_first')),
  ADD CONSTRAINT ck_score_disputes_index CHECK
    ((area IN ('structure_step','language_suggestion')) = (item_index IS NOT NULL)),
  -- the one-tap holds no words at all (C-67): no note, no transcript, and it names its fix
  ADD CONSTRAINT ck_score_disputes_one_tap_no_words CHECK
    (area <> 'fix_first' OR (note IS NULL AND transcript_text IS NULL AND fix_code IS NOT NULL));

-- One one-tap per report: a repeated tap is the same dispute and counts nothing (AD-147).
CREATE UNIQUE INDEX ux_score_disputes_one_tap ON score_disputes (report_id) WHERE area = 'fix_first';

COMMENT ON COLUMN score_disputes.item_index IS 'Step position (0-3) or language item id the dispute is about; no words.';
COMMENT ON COLUMN score_disputes.fix_code IS 'Fix id of a one-tap dispute (area fix_first), from the stored report.';
COMMENT ON COLUMN score_disputes.formula_version IS 'The disputed report''s formula_version, from the stored report.';
COMMENT ON COLUMN score_disputes.prompt_version IS 'The disputed report''s prompt_version, from the stored report.';
