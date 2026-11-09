package app.stageminute.trust.run;

/** The hand-run program: {@code ./gradlew trustHarness --args="--synthetic"} (see tests/trust/README.md). */
public final class TrustHarness {

    private TrustHarness() {}

    public static void main(String[] args) {
        int code = Cli.run(args, System.getenv(), System.out);
        if (code != 0) {
            System.exit(code);
        }
    }
}
