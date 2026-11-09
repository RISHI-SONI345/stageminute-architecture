package app.stageminute.account;

import app.stageminute.account.internal.ConsentRepository;
import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.Reason;
import app.stageminute.platform.UserId;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI consent and the Usage data switch (F-079). Consent is saved with the policy version and the time, every
 * change (agree or withdraw) adds a row to {@code consents_history}, and consent given to an older policy
 * version counts as no consent until the person agrees again.
 */
@Service
public class ConsentService {

    private final ConsentRepository consents;
    private final Clock clock;
    private final String currentVersion;

    public ConsentService(
            ConsentRepository consents,
            Clock clock,
            @Value("${stageminute.privacy.policy-version}") String currentVersion) {
        this.consents = consents;
        this.clock = clock;
        this.currentVersion = currentVersion;
    }

    public ConsentState state(UserId id) {
        ConsentRepository.Row row = consents.find(id).orElseThrow(() -> new BusinessRefusal(Reason.ACCOUNT_DELETED));
        boolean valid = row.ai() && currentVersion.equals(row.version());
        return new ConsentState(valid, currentVersion, !valid, row.usageData());
    }

    /**
     * Saves the person's answer. The version must be the one the server asks for: a phone cannot record
     * consent for a text it did not show.
     */
    @Transactional
    public void record(UserId id, String policyVersion, boolean granted, boolean usageData) {
        if (!currentVersion.equals(policyVersion)) {
            throw new BusinessRefusal(Reason.VALIDATION_FAILED, Map.of("fields", List.of("policy_version")));
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (!consents.save(id, granted, policyVersion, now, usageData)) {
            throw new BusinessRefusal(Reason.ACCOUNT_DELETED);
        }
        consents.addHistory(id, policyVersion, granted, now);
    }

    /** Throws {@code consent_off} unless the person agreed to the current policy (used by {@code /analyze}). */
    public void require(UserId id) {
        if (!state(id).ai()) {
            throw new BusinessRefusal(Reason.CONSENT_OFF);
        }
    }
}
