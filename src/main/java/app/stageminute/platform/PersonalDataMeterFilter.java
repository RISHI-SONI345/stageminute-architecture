package app.stageminute.platform;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Metrics are counts, never people (AD-23). A meter that carries a label named like an identity, or a
 * label value that looks like an email address, is dropped when it is created, so a mistake in one
 * call site cannot put a user into a dashboard. Request ids are refused too: one value per request
 * would also blow up the number of series.
 */
@Component
class PersonalDataMeterFilter implements MeterFilter {

    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "user",
            "userid",
            "user_id",
            "uid",
            "account",
            "accountid",
            "account_id",
            "email",
            "mail",
            "username",
            "device",
            "deviceid",
            "device_id",
            "ip",
            "clerk_user_id",
            "sub",
            "token",
            "session",
            "client_ip",
            "requestid",
            "request_id");

    private static final Pattern EMAIL_LIKE = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    @Override
    public MeterFilterReply accept(Meter.Id id) {
        for (Tag tag : id.getTagsAsIterable()) {
            if (FORBIDDEN_KEYS.contains(tag.getKey().toLowerCase(Locale.ROOT))
                    || EMAIL_LIKE.matcher(tag.getValue()).find()) {
                return MeterFilterReply.DENY;
            }
        }
        return MeterFilterReply.NEUTRAL;
    }
}
