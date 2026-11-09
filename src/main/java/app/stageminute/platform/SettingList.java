package app.stageminute.platform;

import java.util.List;
import java.util.Locale;

/**
 * Settings that are a list of codes rather than a number, read with {@link Settings#list}. The value in the
 * constructor is the launch default from the requirements; it is used when no row exists or the row is not a list
 * of codes, so a missing or broken setting never widens a rule.
 */
public enum SettingList {
    // 03 F-034: the fixes the carried focus may carry ("add a closing, say the point first, fewer fillers, pause on
    // purpose, pace"), each job in both styles: PREP add_closing / open_point, STAR say_result / set_scene (C-74).
    // An empty list carries nothing.
    CARRY_FIX_PORTABLE_CODES("add_closing,say_result,open_point,set_scene,filler_cluster,no_freeze,steady_pace"),
    // 06 F-087: the manual override of the AI-cost throttle (the admin console reads it later, #27): auto (by the
    // budget), force_open (paused as if over budget) or force_closed (the share levels are ignored; the call stop still
    // holds). One code only; the meter treats any other list as force_open.
    AI_THROTTLE_OVERRIDE("auto"),
    // 03 F-053, F-060, F-120: the streak lengths, in DAYS (never sessions), that are celebrated once. Written as codes
    // ("3", "7", ...) because the list setting holds codes; the progress module reads them as whole numbers.
    MILESTONE_DAYS("3,7,14,30,50,100");

    private final String launchDefault; // comma separated: an enum may hold only values that cannot change

    SettingList(String launchDefault) {
        this.launchDefault = launchDefault;
    }

    /** The row key in {@code app_settings}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public List<String> launchDefault() {
        return List.of(launchDefault.split(",", -1));
    }
}
