package app.stageminute.account;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.platform.UserId;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The one day calculator: the stored zone plus the server clock, never the phone (03 F-050, root rule 1). */
class AccountCalendarTest extends DatabaseTest {

    @Autowired
    AccountService accounts;

    @Autowired
    ProfileService profiles;

    @Autowired
    AccountCalendar calendar;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    private UserId userIn(String zone) {
        UserId user = accounts.resolve(
                "user_" + UUID.randomUUID().toString().replace("-", ""),
                new NewAccountContext("device-" + UUID.randomUUID(), "android", "10.9.2.1"));
        profiles.patch(user, new ProfileChange(null, null, zone, null));
        return user;
    }

    private void setClock(String instant) {
        clock.advance(java.time.Duration.between(clock.instant(), Instant.parse(instant)));
    }

    @Test
    void todayFollowsTheStoredZone() {
        setClock("2026-10-26T20:00:00Z"); // 01:30 on the 27th in India, still the 26th in UTC
        UserId india = userIn("Asia/Kolkata");
        UserId utc = userIn("UTC");

        assertThat(calendar.today(india)).isEqualTo(LocalDate.parse("2026-10-27"));
        assertThat(calendar.today(utc)).isEqualTo(LocalDate.parse("2026-10-26"));
    }

    @Test
    void theDayEndsAtLocalMidnight() {
        setClock("2026-10-26T20:00:00Z");

        assertThat(calendar.resetsAt(userIn("Asia/Kolkata"))).isEqualTo(Instant.parse("2026-10-27T18:30:00Z"));
    }

    @Test
    void aDayChangeOfClockInTheDaylightSavingSwitchStillEndsAtMidnight() {
        setClock("2026-11-01T12:00:00Z"); // US clocks go back that night

        UserId newYork = userIn("America/New_York");

        assertThat(calendar.today(newYork)).isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(calendar.resetsAt(newYork)).isEqualTo(Instant.parse("2026-11-02T05:00:00Z"));
    }

    /** The stored zone is replaced at once; the once-a-week limit is a different rule (ProfileServiceTest). */
    private void jumpTo(UserId user, String zone) {
        jdbc.sql("UPDATE users SET timezone = :zone WHERE id = :id")
                .param("zone", zone)
                .param("id", user.value())
                .update();
    }

    private Duration lengthOfTodayFor(UserId user) {
        Instant startOfToday = calendar.today(user)
                .atStartOfDay(java.time.ZoneId.of(zoneOf(user)))
                .toInstant();
        return Duration.between(startOfToday, calendar.resetsAt(user));
    }

    private String zoneOf(UserId user) {
        return jdbc.sql("SELECT timezone FROM users WHERE id = :id")
                .param("id", user.value())
                .query(String.class)
                .single();
    }

    @Test
    void theSpringForwardDayInNewYorkHasTwentyThreeHours() {
        setClock("2026-03-08T12:00:00Z"); // US clocks go forward that morning, 08:00 in New York
        UserId newYork = userIn("America/New_York");

        assertThat(calendar.today(newYork)).isEqualTo(LocalDate.parse("2026-03-08"));
        assertThat(calendar.resetsAt(newYork)).isEqualTo(Instant.parse("2026-03-09T04:00:00Z"));
        assertThat(lengthOfTodayFor(newYork)).isEqualTo(Duration.ofHours(23));
    }

    @Test
    void theFallBackDayInNewYorkHasTwentyFiveHours() {
        setClock("2026-11-01T12:00:00Z");
        UserId newYork = userIn("America/New_York");

        assertThat(lengthOfTodayFor(newYork)).isEqualTo(Duration.ofHours(25));
    }

    @Test
    void acrossTheDateLineTwoPeopleAreTwoDatesApart() {
        setClock("2026-10-26T10:30:00Z");
        UserId kiritimati = userIn("Pacific/Kiritimati"); // UTC+14
        UserId pagoPago = userIn("Pacific/Pago_Pago"); // UTC-11

        assertThat(calendar.today(kiritimati)).isEqualTo(LocalDate.parse("2026-10-27"));
        assertThat(calendar.today(pagoPago)).isEqualTo(LocalDate.parse("2026-10-25"));
        assertThat(calendar.resetsAt(kiritimati)).isEqualTo(Instant.parse("2026-10-27T10:00:00Z"));
        assertThat(calendar.resetsAt(pagoPago)).isEqualTo(Instant.parse("2026-10-26T11:00:00Z"));
    }

    @Test
    void aPersonWhoSignsUpAtElevenFiftyPmGetsTheNextDayTenMinutesLater() {
        setClock("2026-10-26T18:20:00Z"); // 23:50 in India
        UserId india = userIn("Asia/Kolkata");

        assertThat(calendar.today(india)).isEqualTo(LocalDate.parse("2026-10-26"));
        assertThat(calendar.resetsAt(india)).isEqualTo(Instant.parse("2026-10-26T18:30:00Z"));

        clock.advance(Duration.ofMinutes(11));

        assertThat(calendar.today(india)).isEqualTo(LocalDate.parse("2026-10-27"));
    }

    @Test
    void aZoneJumpMovesTheDayToTheNewZoneAndTheResetStaysInTheFuture() {
        setClock("2026-10-26T20:00:00Z"); // 01:30 on the 27th in India
        UserId traveller = userIn("Asia/Kolkata");
        assertThat(calendar.today(traveller)).isEqualTo(LocalDate.parse("2026-10-27"));

        jumpTo(traveller, "America/Los_Angeles"); // 13:00 on the 26th there

        assertThat(calendar.today(traveller)).isEqualTo(LocalDate.parse("2026-10-26"));
        assertThat(calendar.resetsAt(traveller)).isEqualTo(Instant.parse("2026-10-27T07:00:00Z"));
        assertThat(calendar.resetsAt(traveller)).isAfter(clock.instant());
    }
}
