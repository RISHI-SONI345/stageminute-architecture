package app.stageminute.analysis.score;

/** Test fixture: the launch {@link FixSettings} with a few values replaced. */
final class FixSettingsFixture {

    private FixSettingsFixture() {}

    static FixSettings withPauseAndClusterTriggers(int noFreezeMinPauses, int fillerClustersTrigger) {
        FixSettings d = FixSettings.LAUNCH_DEFAULT;
        return new FixSettings(
                d.speakLongerMinWords(),
                d.speakLongerMinSpeechMs(),
                d.speakLongerDoneMinSpeechMs(),
                d.offTopicLevel(),
                d.relevanceDoneLevel(),
                noFreezeMinPauses,
                d.pauseShorterPercent(),
                d.fillersPerMinuteTrigger(),
                fillerClustersTrigger,
                d.fillerRateDropPercent(),
                d.startSoonerOverMs(),
                d.startSoonerDoneWithinMs(),
                d.splitSentenceOverWords(),
                d.splitSentenceDoneMaxWords(),
                d.steadyPaceOutsideMs(),
                d.steadyPaceDoneInBandPercent(),
                d.windowMs(),
                d.languageFixMaxLevel(),
                d.languageDoneRise());
    }
}
