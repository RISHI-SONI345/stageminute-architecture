package app.stageminute.analysis.score;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The word lists Metrics v2 reads (03 F-027 and F-031, requirements version 1.7). They are server settings, tuned on
 * the labelled set; {@link #LAUNCH_DEFAULT} holds the requirement values. Words are stored lower case with no
 * punctuation; a phrase is words separated by one space.
 *
 * @param alwaysCounted filler sounds code confirms itself (um, uh, er, erm, ah, hmm)
 * @param inContext candidates the AI call labels (like, so, you know, I mean, basically, actually, literally, I
 *     guess, you see)
 * @param hedges hedges, counted on Delivery but never scored (kind of, sort of, I think, I guess, maybe)
 * @param functionWords small common words that are never a "mis-heard content word": articles, pronouns,
 *     auxiliaries, prepositions, conjunctions and the filler words themselves. Written by hand for this product (no
 *     library list is copied); the founder approved the words on 2026-10-28 (step3/CONFLICTS.md C-31).
 */
public record WordLists(
        Set<String> alwaysCounted, List<String> inContext, List<String> hedges, Set<String> functionWords) {

    public static final WordLists LAUNCH_DEFAULT = new WordLists(
            Set.of("um", "uh", "er", "erm", "ah", "hmm"),
            List.of("like", "so", "you know", "i mean", "basically", "actually", "literally", "i guess", "you see"),
            List.of("kind of", "sort of", "i think", "i guess", "maybe"),
            Set.copyOf(List.of(
                    // articles and a few determiners
                    "a",
                    "an",
                    "the",
                    "this",
                    "that",
                    "some",
                    "all",
                    "no",
                    "more",
                    // pronouns
                    "i",
                    "me",
                    "my",
                    "we",
                    "us",
                    "our",
                    "you",
                    "your",
                    "he",
                    "him",
                    "his",
                    "she",
                    "her",
                    "it",
                    "its",
                    "they",
                    "them",
                    "their",
                    // question and linking words
                    "who",
                    "what",
                    "which",
                    "when",
                    "where",
                    "why",
                    "how",
                    // auxiliaries
                    "am",
                    "is",
                    "are",
                    "was",
                    "were",
                    "be",
                    "been",
                    "have",
                    "has",
                    "had",
                    "do",
                    "does",
                    "did",
                    "will",
                    "would",
                    "can",
                    "could",
                    "should",
                    // prepositions
                    "of",
                    "to",
                    "in",
                    "on",
                    "at",
                    "by",
                    "for",
                    "with",
                    "from",
                    "about",
                    "into",
                    "over",
                    "out",
                    "up",
                    "as",
                    "after",
                    "before",
                    // conjunctions and other small words
                    "and",
                    "but",
                    "or",
                    "so",
                    "if",
                    "because",
                    "than",
                    "then",
                    "not",
                    "just",
                    "very",
                    "also",
                    "here",
                    "there",
                    // contractions Deepgram writes as one word
                    "i'm",
                    "i've",
                    "i'll",
                    "it's",
                    "that's",
                    "let's",
                    "there's",
                    "don't",
                    "doesn't",
                    "didn't",
                    "can't",
                    "isn't",
                    "wasn't",
                    "you're",
                    "we're",
                    "they're",
                    // the filler words themselves
                    "um",
                    "uh",
                    "er",
                    "erm",
                    "ah",
                    "hmm",
                    "like",
                    "basically",
                    "actually",
                    "literally")));

    public WordLists {
        if (alwaysCounted.isEmpty()) {
            throw new IllegalArgumentException("alwaysCounted cannot be empty");
        }
        if (functionWords.isEmpty()) {
            throw new IllegalArgumentException("functionWords cannot be empty");
        }
        alwaysCounted = normaliseAll(alwaysCounted);
        functionWords = normaliseAll(functionWords);
        inContext = inContext.stream().map(WordLists::normalise).toList();
        hedges = hedges.stream().map(WordLists::normalise).toList();
    }

    /** A phrase to look for: its words and what kind of candidate it is. */
    record Phrase(List<String> words, FillerKind kind) {}

    /**
     * Every phrase, longest first. If the same words are on more than one list the strongest kind wins (always
     * counted, then in context, then hedge), so "I guess" is judged in context.
     */
    List<Phrase> phrases() {
        Map<List<String>, FillerKind> byWords = new LinkedHashMap<>();
        alwaysCounted.forEach(w -> byWords.putIfAbsent(split(w), FillerKind.ALWAYS_COUNTED));
        inContext.forEach(p -> byWords.putIfAbsent(split(p), FillerKind.IN_CONTEXT));
        hedges.forEach(p -> byWords.putIfAbsent(split(p), FillerKind.HEDGE));
        List<Phrase> phrases = new ArrayList<>();
        byWords.forEach((words, kind) -> phrases.add(new Phrase(words, kind)));
        phrases.sort(Comparator.comparingInt((Phrase p) -> p.words().size()).reversed());
        return phrases;
    }

    boolean isFunctionWord(String normalisedWord) {
        return functionWords.contains(normalisedWord);
    }

    /** Lower case, letters and apostrophes only; a curly apostrophe counts as a straight one. */
    static String normalise(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toLowerCase(Locale.ROOT).replace('’', '\'').toCharArray()) {
            if (Character.isLetter(c) || c == '\'' || c == ' ') {
                out.append(c);
            }
        }
        return out.toString().strip().replaceAll("^'+|'+$", "").replaceAll(" +", " ");
    }

    private static Set<String> normaliseAll(Set<String> words) {
        return words.stream().map(WordLists::normalise).collect(Collectors.toUnmodifiableSet());
    }

    private static List<String> split(String phrase) {
        return List.of(phrase.split(" ", -1));
    }
}
