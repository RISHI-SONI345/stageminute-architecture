package app.stageminute.practice;

/**
 * How the last attempt did on the fix the person was working on (03 F-033, glossary `focus`). {@code NONE} means
 * there was no focus check (the first attempt, or the earlier take failed or was not scored). The analysis module
 * maps its own result to this; {@code practice} knows no score types (docs/architecture/02-SERVER-LLD.md section 1).
 */
public enum FocusOutcome {
    NONE,
    DONE,
    BETTER,
    NOT_YET
}
