package app.stageminute.trust.labelset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.stageminute.analysis.score.StepStatus;
import app.stageminute.analysis.score.Style;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LabelSetLoaderTest {

    @TempDir
    Path root;

    private Path set() {
        SyntheticSet.write(root);
        return root.resolve("set");
    }

    @Test
    void theSyntheticSetLoadsAndTheRevokedConsentIsLeftOut() {
        LabelSet loaded = LabelSetLoader.load(set());

        assertThat(loaded.speakers()).extracting(LabelSet.SpeakerFolder::id).containsExactly("syn-01", "syn-02");
        assertThat(loaded.excluded()).containsExactly(new LabelSet.Excluded("syn-03", "consent revoked"));
        LabelSet.SpeakerFolder first = loaded.speakers().get(0);
        assertThat(first.accentGroup()).isEqualTo("synthetic-a");
        assertThat(first.style()).isEqualTo(Style.PREP);
        assertThat(first.capMs()).isEqualTo(60_000);
        assertThat(first.annotators()).containsExactly("syn-a1", "syn-a2");
        assertThat(first.takes()).hasSize(2);
        LabelSet.TakeInput take = first.takes().get(0);
        assertThat(take.name()).isEqualTo("take-1.m4a");
        assertThat(take.phoneDurationMs()).hasValue(44_800);
        assertThat(take.references()).hasSize(2);
        assertThat(take.references().get(1).steps())
                .containsExactly(StepStatus.STRONG, StepStatus.WEAK, StepStatus.WEAK, StepStatus.STRONG);
        assertThat(take.references().get(0).fillerStartsMs()).containsExactly(8000, 16000);
        assertThat(take.references().get(0).wordCount()).hasValue(100);
        assertThat(take.references().get(0).firstFix()).hasValue("add_example");
    }

    @Test
    void aSpeakerWhoseConsentIsNotListedIsLeftOut() throws IOException {
        Path set = set();
        Files.writeString(set.resolve("consents.json"), "{\"SYN-CONSENT-1\":\"active\"}");

        LabelSet loaded = LabelSetLoader.load(set);

        assertThat(loaded.speakers()).extracting(LabelSet.SpeakerFolder::id).containsExactly("syn-01");
        assertThat(loaded.excluded()).extracting(LabelSet.Excluded::reason).containsOnly("consent missing");
    }

    @Test
    void noConsentsFileStopsTheRun() throws IOException {
        Path set = set();
        Files.delete(set.resolve("consents.json"));

        assertThatThrownBy(() -> LabelSetLoader.load(set))
                .isInstanceOf(LabelSetException.class)
                .hasMessageContaining("consents.json");
    }

    @Test
    void anAnnotatorWithNoLabelsFileStopsTheRun() throws IOException {
        Path set = set();
        Files.delete(set.resolve("syn-01").resolve("labels-syn-a2.json"));

        assertThatThrownBy(() -> LabelSetLoader.load(set))
                .isInstanceOf(LabelSetException.class)
                .hasMessageContaining("syn-01")
                .hasMessageContaining("labels-syn-a2.json");
    }

    @Test
    void anAnnotatorWhoSkippedATakeStopsTheRun() throws IOException {
        Path set = set();
        Files.writeString(set.resolve("syn-01").resolve("labels-syn-a2.json"), "{\"takes\":{}}");

        assertThatThrownBy(() -> LabelSetLoader.load(set))
                .isInstanceOf(LabelSetException.class)
                .hasMessageContaining("syn-a2 has no labels for syn-01/take-1.m4a");
    }

    @Test
    void fewerThanTwoAnnotatorsIsRefused() throws IOException {
        Path set = set();
        Path file = set.resolve("syn-01").resolve("speaker.json");
        Files.writeString(
                file,
                Files.readString(file)
                        .replace("\"annotators\":[\"syn-a1\",\"syn-a2\"]", "\"annotators\":[\"syn-a1\"]"));

        assertThatThrownBy(() -> LabelSetLoader.load(set))
                .isInstanceOf(LabelSetException.class)
                .hasMessageContaining("at least 2 annotator ids");
    }

    @Test
    void aBadStepWordOrStyleNamesTheFileNotTheContent() throws IOException {
        Path set = set();
        Path labels = set.resolve("syn-01").resolve("labels-syn-a1.json");
        Files.writeString(labels, Files.readString(labels).replace("\"weak\"", "\"so-so\""));

        assertThatThrownBy(() -> LabelSetLoader.load(set))
                .isInstanceOf(LabelSetException.class)
                .hasMessageContaining("syn-01/take-1.m4a")
                .hasMessageContaining("not strong, weak or missing");

        Path speaker = set.resolve("syn-02").resolve("speaker.json");
        Files.writeString(speaker, Files.readString(speaker).replace("PREP", "TED"));
        Files.writeString(labels, Files.readString(labels).replace("\"so-so\"", "\"weak\""));
        assertThatThrownBy(() -> LabelSetLoader.load(set))
                .isInstanceOf(LabelSetException.class)
                .hasMessageContaining("PREP or STAR");
    }

    @Test
    void aStepListThatIsNotFourLongIsRefused() throws IOException {
        Path set = set();
        Path labels = set.resolve("syn-02").resolve("labels-syn-a1.json");
        Files.writeString(
                labels,
                Files.readString(labels).replace("[\"strong\",\"strong\",\"weak\",\"strong\"]", "[\"strong\"]"));

        assertThatThrownBy(() -> LabelSetLoader.load(set))
                .isInstanceOf(LabelSetException.class)
                .hasMessageContaining("4 step labels");
    }

    @Test
    void aFolderWithoutSpeakerJsonOrWithNoAudioIsRefused() throws IOException {
        Path set = set();
        Files.createDirectories(set.resolve("stray"));
        assertThatThrownBy(() -> LabelSetLoader.load(set))
                .isInstanceOf(LabelSetException.class)
                .hasMessageContaining("stray has no speaker.json");
        Files.delete(set.resolve("stray"));

        Files.delete(set.resolve("syn-01").resolve("take-1.m4a"));
        Files.delete(set.resolve("syn-01").resolve("take-2.m4a"));
        assertThatThrownBy(() -> LabelSetLoader.load(set))
                .isInstanceOf(LabelSetException.class)
                .hasMessageContaining("syn-01 has no .m4a takes");
    }

    @Test
    void aMissingFolderOrAnEmptySetIsRefused() throws IOException {
        assertThatThrownBy(() -> LabelSetLoader.load(root.resolve("nope")))
                .isInstanceOf(LabelSetException.class)
                .hasMessageContaining("does not exist");
        Path empty = Files.createDirectories(root.resolve("empty"));
        Files.writeString(empty.resolve("consents.json"), "{}");
        assertThatThrownBy(() -> LabelSetLoader.load(empty)).isInstanceOf(LabelSetException.class);
        Files.writeString(empty.resolve("consents.json"), "not json");
        assertThatThrownBy(() -> LabelSetLoader.load(empty))
                .isInstanceOf(LabelSetException.class)
                .hasMessageContaining("not readable JSON");
    }

    @Test
    void theReplayFolderGivesOneClipPerAudioFileWithTheDefaultCap() {
        SyntheticSet.write(root);

        var clips = LabelSetLoader.loadReplay(root.resolve("replay"));

        assertThat(clips).extracting(LabelSet.ClipInput::name).containsExactly("clip-1.m4a", "clip-2.m4a");
        assertThat(clips.get(0).style()).isEqualTo(Style.PREP);
        assertThat(clips.get(0).capMs()).isEqualTo(60_000);
    }

    @Test
    void aStarReplayFolderGetsTheLongerCapAndAnEmptyOneIsRefused() throws IOException {
        SyntheticSet.write(root);
        Path replay = root.resolve("replay");
        Files.writeString(replay.resolve("replay.json"), "{\"style\":\"STAR\"}");
        assertThat(LabelSetLoader.loadReplay(replay).get(0).capMs()).isEqualTo(90_000);

        Path none = Files.createDirectories(root.resolve("none"));
        assertThatThrownBy(() -> LabelSetLoader.loadReplay(none)).isInstanceOf(LabelSetException.class);
        assertThatThrownBy(() -> LabelSetLoader.loadReplay(root.resolve("nope")))
                .isInstanceOf(LabelSetException.class);
    }
}
