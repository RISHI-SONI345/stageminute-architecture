package app.stageminute.analysis.vendor;

import app.stageminute.analysis.score.FillerCandidate;
import app.stageminute.analysis.score.FillerKind;
import app.stageminute.analysis.score.FillerLabel;
import app.stageminute.analysis.score.LanguageLevels;
import app.stageminute.analysis.score.SentenceSpan;
import app.stageminute.analysis.score.StepStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * A deterministic stand-in for the one Gemini call (02-SERVER-LLD.md). The same take always gets the same labels:
 * Point, Reason and Closing strong, Example weak; relevance 4; clarity 3, conciseness 3, accuracy 4; every in-context
 * filler candidate meaningful; the strongest line is the second sentence. Its model, prompt and schema versions are
 * constants until #19 sets the real ones (AD-125). Only {@link VendorConfig} creates it, and only when the dev setting
 * turns the fakes on.
 */
public final class FakeJudge implements Judge {

    public static final JudgeVersions VERSIONS = new JudgeVersions("fake-judge", "fake-prompt-0", "fake-schema-0");

    private static final int STRONGEST = 1;

    @Override
    public JudgeLabels judge(JudgeInput input) {
        List<SentenceSpan> sentences = input.transcript().sentences();
        int last = sentences.size() - 1;
        List<@Nullable Integer> stepSentences = new ArrayList<>();
        stepSentences.add(sentenceOrNull(0, last));
        stepSentences.add(sentenceOrNull(1, last));
        stepSentences.add(sentenceOrNull(2, last));
        stepSentences.add(last >= 3 ? last : null);

        Map<Integer, FillerLabel> fillers = new HashMap<>();
        for (FillerCandidate candidate : input.fillerCandidates()) {
            if (candidate.kind() == FillerKind.IN_CONTEXT) {
                fillers.put(candidate.id(), FillerLabel.MEANINGFUL);
            }
        }
        List<JudgeLabels.LanguageItem> items = new ArrayList<>();
        if (last >= 1) {
            int first = sentences.get(1).firstWord();
            items.add(new JudgeLabels.LanguageItem("grammar", 1, first + 1, first + 2, "was loading"));
        }
        return new JudgeLabels(
                List.of(StepStatus.STRONG, StepStatus.STRONG, StepStatus.WEAK, StepStatus.STRONG),
                stepSentences,
                4,
                new LanguageLevels(3, 3, 4),
                fillers,
                false,
                "Clear point and a clean closing.", // copy-ok: fake model output for local runs, never shown in
                // production
                "Give your example a name and a place.", // copy-ok: fake model output for local runs, never shown in
                // production
                "Our checkout was slow, so we rebuilt its caching layer.", // copy-ok: fake model output for local runs,
                // never shown in production
                last >= STRONGEST ? OptionalInt.of(STRONGEST) : OptionalInt.empty(),
                "You named the change plainly.", // copy-ok: fake model output for local runs, never shown in production
                items);
    }

    @Override
    public JudgeVersions versions() {
        return VERSIONS;
    }

    private static @Nullable Integer sentenceOrNull(int id, int last) {
        return id <= last ? id : null;
    }
}
