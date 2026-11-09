package app.stageminute.trust.measures;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.analysis.spend.SpendPrices;
import org.junit.jupiter.api.Test;

class CostEstimateTest {

    // Deepgram 4,300 micros a minute; Gemini 300,000 micros per million input and 2,500,000 per million output tokens;
    // worst case 8,000 in and 8,192 out. Prices here are test numbers, not the vendors'.
    private static final SpendPrices PRICES = new SpendPrices(4_300, 300_000, 2_500_000, 8_000, 8_192);

    @Test
    void theRepeatRunMultipliesTheCallsAndTheAudio() {
        // 4 labelled takes of 2.5 minutes (10 minutes) and 2 clips of 1 minute run 5 times each.
        CostEstimate estimate = CostEstimate.of(600_000, 4, 120_000, 2, 5, PRICES);

        assertThat(estimate.takeCalls()).isEqualTo(14); // 4 + 2 x 5
        assertThat(estimate.audioMinutes()).isEqualTo(20.0); // 10 + 2 x 1 x 5
        // Deepgram: 20 minutes x 4,300 = 86,000. Gemini expected: 1,100 tokens at the output price = 2,750 a call,
        // x 14 = 38,500. Expected total 124,500.
        assertThat(estimate.expectedMicros()).isEqualTo(124_500);
        // Gemini worst case a call: 8,000 x 0.3 + 8,192 x 2.5 = 2,400 + 20,480 = 22,880, x 14 = 320,320; plus 86,000.
        assertThat(estimate.worstCaseMicros()).isEqualTo(406_320);
    }

    @Test
    void theEstimateSaysWhatItIsMadeOfAndThatTheRepeatRunIsFiveTimes() {
        String text = CostEstimate.of(600_000, 4, 120_000, 2, 5, PRICES).describe();

        assertThat(text)
                .contains("14 pipeline runs")
                .contains("repeat-run test runs each clip 5 times")
                .contains("USD 0.1245")
                .contains("USD 0.4063")
                .contains("ESTIMATE");
    }

    @Test
    void nothingToRunCostsNothing() {
        CostEstimate estimate = CostEstimate.of(0, 0, 0, 0, 5, PRICES);

        assertThat(estimate.expectedMicros()).isZero();
        assertThat(estimate.takeCalls()).isZero();
    }
}
