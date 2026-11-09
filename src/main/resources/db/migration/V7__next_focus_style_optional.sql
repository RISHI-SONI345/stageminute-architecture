-- Ticket #16 review (F-009, AD-140): a carried fix about one step of the answer (add a closing, say the point first)
-- belongs to the style it was set in; a delivery fix (fewer fillers, pause on purpose, pace) belongs to none, so a new
-- style must not clear it. NULL style = not bound to a style. Expand only: old rows keep their style.
ALTER TABLE next_focus ALTER COLUMN style DROP NOT NULL;
COMMENT ON COLUMN next_focus.style IS 'The style a step fix was set in; NULL for a fix not bound to a style.';
