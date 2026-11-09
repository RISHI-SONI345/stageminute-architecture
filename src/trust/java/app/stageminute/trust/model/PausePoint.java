package app.stageminute.trust.model;

/** One pause: where it starts and how long it is, in milliseconds. */
public record PausePoint(int startMs, int lengthMs) {}
