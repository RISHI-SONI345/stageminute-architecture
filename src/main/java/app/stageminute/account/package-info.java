/**
 * Profile, consent, the account day (one calendar), account deletion.
 * <p>Start here: {@code AccountService}, {@code ProfileService} (level, style, zone, name and the reminder settings, {@code Profile.Reminders}), {@code ConsentService}, {@code AccountCalendar}, {@code Deletion} (delete the account; its nested {@code Requested} is the event), {@code StyleChanged} (event), {@code ProfileService.ZoneChanged} (event, F-050). Map: ARCHITECTURE.md.
 * <p>Allowed dependencies follow docs/architecture/02-SERVER-LLD.md §1; Spring Modulith verify() enforces them.
 */
@ApplicationModule(
        displayName = "account",
        allowedDependencies = {"platform"})
package app.stageminute.account;

import org.springframework.modulith.ApplicationModule;
