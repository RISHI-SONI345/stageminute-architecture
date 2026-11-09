-- Ticket #16.1 (C-71, AD-136): the model's rewording of the answer (rewrite, language_tries) travels with speech_data
-- and stays on the phone. Reports saved before #16.1 still hold both in their JSON; this removes them, so no stored
-- report keeps them. Forward-only and safe to run on a database with no such rows.
UPDATE reports SET report = report - 'rewrite' - 'language_tries'
WHERE report -> 'rewrite' IS NOT NULL OR report -> 'language_tries' IS NOT NULL;
