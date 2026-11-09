package app.stageminute.analysis.spend;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.analysis.vendor.Vendor;
import app.stageminute.analysis.vendor.VendorUsage;
import org.junit.jupiter.api.Test;

/** Units to cost micros in one place (#19.2). The prices are the published ones, passed in as settings would. */
class SpendPricesTest {

    // Deepgram Nova-3 multilingual USD 0.0052 a minute; Gemini USD 1.50 in, USD 7.50 out per million tokens.
    private static final SpendPrices PRICES = new SpendPrices(5_200, 1_500_000, 7_500_000, 8_000, 8_192);

    @Test
    void oneMinuteOfAudioCostsTheMinutePrice() {
        assertThat(PRICES.cost(VendorUsage.audio(60_000))).isEqualTo(5_200);
    }

    @Test
    void audioIsBilledBySecondAndRoundedUpToAWholeMicro() {
        assertThat(PRICES.cost(VendorUsage.audio(1_000))).isEqualTo(87); // 86.67 micros
        assertThat(PRICES.cost(VendorUsage.audio(0))).isZero();
    }

    @Test
    void tokensAreChargedPerMillionInAndOut() {
        assertThat(PRICES.cost(VendorUsage.tokens(1_000_000, 0))).isEqualTo(1_500_000);
        assertThat(PRICES.cost(VendorUsage.tokens(0, 1_000_000))).isEqualTo(7_500_000);
        assertThat(PRICES.cost(VendorUsage.tokens(1_100, 400))).isEqualTo(1_650 + 3_000); // 1.1 K in, 0.4 K out
        assertThat(PRICES.cost(VendorUsage.tokens(1, 1))).isEqualTo(2 + 8); // 1.5 and 7.5 micros, each rounded up
    }

    @Test
    void theWorstCaseOfADeepgramCallIsTheWholeTakeAtThePrice() {
        assertThat(PRICES.worstCase(Vendor.DEEPGRAM, 90_000)).isEqualTo(7_800);
    }

    @Test
    void theWorstCaseOfAGeminiCallUsesTheConfiguredTokenCeilings() {
        // 8 000 tokens in = 12 000 micros; 8 192 tokens out = 61 440 micros
        assertThat(PRICES.worstCase(Vendor.GEMINI, 0)).isEqualTo(73_440);
    }

    @Test
    void aCallNeverCostsMoreThanItsWorstCaseWhenTheVendorStaysInsideTheCeilings() {
        assertThat(PRICES.cost(VendorUsage.tokens(8_000, 8_192))).isEqualTo(PRICES.worstCase(Vendor.GEMINI, 0));
    }

    @Test
    void aPriceOfZeroOrLessIsNotValidSoTheMeterCanFailClosed() {
        assertThat(PRICES.valid()).isTrue();
        assertThat(new SpendPrices(0, 1, 1, 1, 1).valid()).isFalse();
        assertThat(new SpendPrices(1, 0, 1, 1, 1).valid()).isFalse();
        assertThat(new SpendPrices(1, 1, 0, 1, 1).valid()).isFalse();
        assertThat(new SpendPrices(1, 1, 1, 0, 1).valid()).isFalse();
        assertThat(new SpendPrices(1, 1, 1, 1, 0).valid()).isFalse();
    }
}
