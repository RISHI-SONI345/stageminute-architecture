package app.stageminute.analysis.vendor;

import app.stageminute.analysis.score.SentenceSpan;
import app.stageminute.analysis.score.TimedWord;
import app.stageminute.analysis.score.Transcript;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A deterministic stand-in for Deepgram (02-SERVER-LLD.md: every vendor has a fake). It never reads the audio: it
 * makes the same words for the same length and scenario, so a test or a phone run sees a known report. It records
 * every request it gets, so a test can prove each one sent {@code mip_opt_out=true} and {@code filler_words=true}
 * (#19 keeps that test for the real client). Only {@link VendorConfig} creates it, and only when the dev setting turns
 * the fakes on.
 */
public final class FakeSpeechToText implements SpeechToText {

    /** Words of the fake take, in a loop. Plain content words: no filler, hedge or habit word, no label word. */
    private static final List<String> WORDS = List.of(
            "our",
            "checkout",
            "page",
            "loaded",
            "slowly",
            "last",
            "spring",
            "and",
            "we",
            "rebuilt",
            "its",
            "caching",
            "layer",
            "with",
            "care",
            "customers",
            "noticed",
            "faster",
            "orders",
            "within",
            "weeks",
            "everyone",
            "shipped",
            "quietly");

    private static final int FIRST_WORD_MS = 800;
    private static final int LAST_GAP_MS = 600;
    private static final int WORD_EVERY_MS = 417; // about 144 words a minute
    private static final int WORD_LENGTH_MS = 300;
    private static final int WORDS_PER_SENTENCE = 12;
    private static final int FILLER_EVERY = 20;
    private static final int SHORT_TAKE_WORDS = 30;
    private static final double SURE = 0.95;
    private static final double UNSURE = 0.3;
    private static final int UNSURE_EVERY = 5;

    private final FakeScenario scenario;
    private final long delayMs;
    private final List<SttRequest> requests = new CopyOnWriteArrayList<>();

    public FakeSpeechToText(FakeScenario scenario, long delayMs) {
        this.scenario = scenario;
        this.delayMs = delayMs;
    }

    @Override
    public SpeechResult transcribe(byte[] audio, int durationMs, SttRequest request) {
        requests.add(request);
        pause();
        if (scenario == FakeScenario.VENDOR_FAILURE) {
            throw new VendorUnavailable("fake_stt_failure");
        }
        List<TimedWord> words = scenario == FakeScenario.NO_SPEECH ? List.of() : words(durationMs);
        return new SpeechResult(
                new Transcript(words, sentences(words.size()), durationMs), scenario != FakeScenario.NOT_ENGLISH);
    }

    /** Every request this fake was sent, oldest first. */
    public List<SttRequest> requests() {
        return List.copyOf(requests);
    }

    private List<TimedWord> words(int durationMs) {
        int count = Math.max(0, (durationMs - FIRST_WORD_MS - LAST_GAP_MS) / WORD_EVERY_MS);
        if (scenario == FakeScenario.SHORT_TAKE) {
            count = Math.min(count, SHORT_TAKE_WORDS);
        }
        List<TimedWord> words = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int start = FIRST_WORD_MS + i * WORD_EVERY_MS;
            double confidence =
                    scenario == FakeScenario.LOW_AUDIO && i % UNSURE_EVERY == UNSURE_EVERY - 1 ? UNSURE : SURE;
            words.add(new TimedWord(text(i), start, start + WORD_LENGTH_MS, confidence));
        }
        return words;
    }

    /** The word at a position: an "um" now and then, "I think" once, a sentence opened with "So" once. */
    private static String text(int i) {
        if (i % FILLER_EVERY == FILLER_EVERY / 2) {
            return "um";
        }
        if (i == 2 * WORDS_PER_SENTENCE + 3) {
            return "I";
        }
        if (i == 2 * WORDS_PER_SENTENCE + 4) {
            return "think";
        }
        if (i == WORDS_PER_SENTENCE) {
            return "So";
        }
        String word = WORDS.get(i % WORDS.size());
        return i % WORDS_PER_SENTENCE == WORDS_PER_SENTENCE - 1 ? word + "." : word;
    }

    private static List<SentenceSpan> sentences(int wordCount) {
        List<SentenceSpan> sentences = new ArrayList<>();
        for (int first = 0; first < wordCount; first += WORDS_PER_SENTENCE) {
            sentences.add(new SentenceSpan(first, Math.min(wordCount, first + WORDS_PER_SENTENCE) - 1));
        }
        return sentences;
    }

    /** The configured slowness, so the app's "Taking a bit longer" state can be shown on a phone (F-023). */
    private void pause() {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VendorUnavailable("fake_stt_interrupted");
        }
    }
}
