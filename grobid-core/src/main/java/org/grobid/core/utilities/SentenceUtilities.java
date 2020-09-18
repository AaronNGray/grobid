package org.grobid.core.utilities;

import org.grobid.core.exceptions.GrobidException;
import org.grobid.core.lang.SentenceDetectorFactory;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class for using sentence segmentation (singleton).
 */
public class SentenceUtilities {
    public static final Logger LOGGER = LoggerFactory.getLogger(SentenceUtilities.class);

    private static volatile SentenceUtilities instance = null;

    private SentenceDetectorFactory sdf = null;

    public static SentenceUtilities getInstance() {
        if (instance == null) {
            synchronized (SentenceUtilities.class) {
                if (instance == null) {
                    LOGGER.debug("synchronized getNewInstance");
                    instance = new SentenceUtilities();
                }
            }
        }
        return instance;
    }

    private SentenceUtilities() {
        String className = GrobidProperties.getSentenceDetectorFactory();
        try {
            sdf = (SentenceDetectorFactory) Class.forName(className).newInstance();
        } catch (ClassCastException e) {
            throw new GrobidException("Class " + className
                + " must implement "
                + SentenceDetectorFactory.class.getName(), e);
        } catch (ClassNotFoundException e) {
            throw new GrobidException(
                "Class "
                    + className
                    + " were not found in the classpath. "
                    + "Make sure that it is provided correctly is in the classpath", e);
        } catch (InstantiationException e) {
            throw new GrobidException("Class " + className
                + " should have a default constructor", e);
        } catch (IllegalAccessException e) {
            throw new GrobidException(e);
        }
    }

    /**
     * Basic run for sentence identification, return the offset positions of the
     * identified sentences
     *
     * @param text text to segment into sentences
     * @return list of offset positions for the identified sentence, relative to the input text
     */
    public List<OffsetPosition> runSentenceDetection(String text) {
        if (text == null)
            return null;
        try {
            return sdf.getInstance().detect(text);
        } catch (Exception e) {
            LOGGER.warn("Cannot detect sentences. ", e);
            return null;
        }
    }

    /**
     * Run for sentence identification with some forbidden span constraints, return the offset positions of the
     * identified sentences without sentence boundaries within a forbidden span (typically a reference marker
     * and we don't want a sentence end/start in the middle of that).
     *
     * @param text      text to segment into sentences
     * @param forbidden list of offset positions where sentence boundaries are forbidden
     * @return list of offset positions for the identified sentence, relative to the input text
     */
    public List<OffsetPosition> runSentenceDetection(String text, List<OffsetPosition> forbidden) {
        if (text == null)
            return null;
        try {
            List<OffsetPosition> sentencePositions = sdf.getInstance().detect(text);

            // to be sure, we sort the forbidden positions
            if (forbidden == null)
                return sentencePositions;

            Collections.sort(forbidden);

            // cancel sentence boundaries within the forbidden spans
            List<OffsetPosition> finalSentencePositions = new ArrayList<>();
            int forbiddenIndex = 0;
            for (int j = 0; j < sentencePositions.size(); j++) {
                OffsetPosition position = sentencePositions.get(j);
                for (int i = forbiddenIndex; i < forbidden.size(); i++) {
                    OffsetPosition forbiddenPos = forbidden.get(i);
                    if (forbiddenPos.end < position.end)
                        continue;
                    if (forbiddenPos.start > position.end)
                        break;
                    while ((forbiddenPos.start < position.end && position.end < forbiddenPos.end)) {
                        if (j + 1 < sentencePositions.size()) {
                            position.end = sentencePositions.get(j + 1).end;
                            j++;
                            forbiddenIndex = i;
                        } else
                            break;
                    }
                }
                finalSentencePositions.add(position);
            }

            // adjust the forbidden spans - if they are present at the beginning of the sentence, move them to the
            // end of the previous sentence

            for (int index = 0; index < finalSentencePositions.size(); index++) {
                OffsetPosition currentSentence = finalSentencePositions.get(index);
                for (OffsetPosition forbiddenSpan : forbidden) {
                    if (forbiddenSpan.start == currentSentence.start && index > 0) {
                        // Adjust the previous sentence to include this span
                        OffsetPosition previousSentence = finalSentencePositions.get(index - 1);
                        previousSentence.end = forbiddenSpan.end;
                        currentSentence.start = forbiddenSpan.end;
                        while (text.charAt(currentSentence.start) == ' ') {
                            if (currentSentence.start == text.length() - 1) {
                                break;
                            } else {
                                currentSentence.start++;
                            }
                        }
                    }
                }
            }

            List<OffsetPosition> cleanedSentencesPositions = finalSentencePositions
                .stream()
                .filter(offsetPosition -> offsetPosition.end - offsetPosition.start > 0)
                .collect(Collectors.toList());

            return cleanedSentencesPositions;
        } catch (Exception e) {
            LOGGER.warn("Cannot detect sentences. ", e);
            return null;
        }
    }

    public String getXml(String text, List<OffsetPosition> offsetPositions) {
        StringBuilder outputText = new StringBuilder();

        outputText.append(text.substring(offsetPositions.get(offsetPositions.size() - 1).end));
        int previousStart = -1;

        for (int i = offsetPositions.size() - 1; i >= 0; i--) {
            if (previousStart != -1) {
                outputText.insert(0, text.substring(offsetPositions.get(i).end, previousStart));
            }
            outputText.insert(0, "<s>" + text.substring(offsetPositions.get(i).start, offsetPositions.get(i).end)
                + "</s>");
            previousStart = offsetPositions.get(i).start;
        }

        outputText.insert(0, text.substring(0, offsetPositions.get(0).start));

        return "<sents>" + outputText.toString() + "</sents>";
    }
}