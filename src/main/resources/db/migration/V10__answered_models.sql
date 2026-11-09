-- #19.1 (AD-151): the model ids the vendors actually answered with, next to the pinned name in model_version.
-- Two nullable columns, expand only: a report saved before this ticket, or by a fake, has none. Server-side only:
-- no API field carries them (a change is counted and logged when the report is saved, not shown to the person).
ALTER TABLE reports
  ADD COLUMN answered_judge_model text,
  ADD COLUMN answered_stt_model   text;
