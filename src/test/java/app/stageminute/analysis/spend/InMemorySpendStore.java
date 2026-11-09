package app.stageminute.analysis.spend;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** A {@link SpendStore} held in memory: the second adapter of the seam, for tests that need no database. */
final class InMemorySpendStore implements SpendStore {

    private final Map<LocalDate, long[]> days = new ConcurrentHashMap<>();
    final AtomicBoolean broken = new AtomicBoolean();

    @Override
    public Day read(LocalDate utcDate) {
        if (broken.get()) {
            throw new IllegalStateException("database is down");
        }
        long[] row = days.getOrDefault(utcDate, new long[2]);
        synchronized (row) {
            return new Day(row[0], (int) row[1]);
        }
    }

    @Override
    public Day add(LocalDate utcDate, long micros) {
        long[] row = days.computeIfAbsent(utcDate, d -> new long[2]);
        synchronized (row) {
            row[0] += micros;
            row[1]++;
            return new Day(row[0], (int) row[1]);
        }
    }
}
