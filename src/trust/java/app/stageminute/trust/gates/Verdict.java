package app.stageminute.trust.gates;

import java.util.List;
import java.util.stream.Collectors;

/** The plain first line of every report. It says "LAUNCH BLOCKED" when any gate fails, and never rounds a fail up. */
public final class Verdict {

    private Verdict() {}

    public static String line(List<Gate> gates, boolean synthetic) {
        if (synthetic) {
            return "LAUNCH BLOCKED: SYNTHETIC RUN on fake vendors. The numbers prove the harness works, not the product.";
        }
        List<String> failed = ids(gates, Status.FAIL);
        if (!failed.isEmpty()) {
            return "LAUNCH BLOCKED: " + failed.size() + " gate(s) failed: " + String.join(", ", failed);
        }
        List<String> unmeasured = ids(gates, Status.NOT_MEASURED);
        if (!unmeasured.isEmpty()) {
            return "LAUNCH NOT PROVEN: " + unmeasured.size() + " gate(s) not measured: "
                    + String.join(", ", unmeasured);
        }
        List<String> open = ids(gates, Status.NOT_SET);
        return open.isEmpty()
                ? "ALL MEASURED GATES PASS"
                : "ALL MEASURED GATES PASS; open (no pass line yet): " + String.join(", ", open);
    }

    private static List<String> ids(List<Gate> gates, Status status) {
        return gates.stream()
                .filter(gate -> gate.status() == status)
                .map(Gate::id)
                .collect(Collectors.toList());
    }
}
