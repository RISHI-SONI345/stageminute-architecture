package app.stageminute.topics.internal;

import java.util.List;

/** The topic files break a rule: the server must not start (F-014). Carries every problem, one line each. */
public final class TopicFilesInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> problems;

    public TopicFilesInvalidException(List<String> problems) {
        super("Topic files are invalid:\n" + String.join("\n", problems), null, false, false);
        this.problems = List.copyOf(problems);
    }

    /** One line per problem, each starting with its code, such as {@code E-TEXT-TOO-LONG}. */
    public List<String> problems() {
        return problems;
    }
}
