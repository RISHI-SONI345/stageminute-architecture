-- V11 (ticket #20.1, AD-155): a zone change to the east can skip a calendar date; that date is frozen so the move never
-- breaks a streak (03 F-050, F-053). The reason list gets one more word. Forward-only; no data is touched.
ALTER TABLE streak_freezes DROP CONSTRAINT streak_freezes_reason_check;
ALTER TABLE streak_freezes ADD CONSTRAINT streak_freezes_reason_check
  CHECK (reason IN ('outage','throttle','support','zone'));
