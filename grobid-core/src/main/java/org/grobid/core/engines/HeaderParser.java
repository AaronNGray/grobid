package org.grobid.core.engines;

import com.google.common.base.Splitter;
//import org.apache.commons.lang3.tuple.ImmutablePair;
//import org.apache.commons.lang3.tuple.Pair;
import org.grobid.core.GrobidModels;
import org.grobid.core.data.BiblioItem;
import org.grobid.core.data.Date;
import org.grobid.core.data.Affiliation;
import org.grobid.core.data.Keyword;
import org.grobid.core.data.Person;
import org.grobid.core.document.Document;
import org.grobid.core.document.DocumentPiece;
import org.grobid.core.document.DocumentPointer;
import org.grobid.core.document.DocumentSource;
import org.grobid.core.document.TEIFormatter;
import org.grobid.core.engines.config.GrobidAnalysisConfig;
import org.grobid.core.engines.label.SegmentationLabels;
import org.grobid.core.engines.label.TaggingLabel;
import org.grobid.core.engines.label.TaggingLabels;
import org.grobid.core.exceptions.GrobidException;
import org.grobid.core.exceptions.GrobidExceptionStatus;
import org.grobid.core.features.FeatureFactory;
import org.grobid.core.features.FeaturesVectorHeader;
import org.grobid.core.lang.Language;
import org.grobid.core.lexicon.Lexicon;
import org.grobid.core.layout.Block;
import org.grobid.core.layout.BoundingBox;
import org.grobid.core.layout.LayoutToken;
import org.grobid.core.layout.PDFAnnotation;
import org.grobid.core.tokenization.LabeledTokensContainer;
import org.grobid.core.tokenization.TaggingTokenCluster;
import org.grobid.core.tokenization.TaggingTokenClusteror;
import org.grobid.core.utilities.Pair;
import org.grobid.core.utilities.Consolidation;
import org.grobid.core.utilities.GrobidProperties;
import org.grobid.core.utilities.LanguageUtilities;
import org.grobid.core.utilities.OffsetPosition;
import org.grobid.core.utilities.LayoutTokensUtil;
import org.grobid.core.utilities.TextUtilities;
import org.grobid.core.utilities.counters.CntManager;
import org.grobid.core.GrobidModels.Collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.SortedSet;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Patrice Lopez
 */
public class HeaderParser extends AbstractParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(HeaderParser.class);

    private LanguageUtilities languageUtilities = LanguageUtilities.getInstance();

    private EngineParsers parsers;

    // default bins for relative position
    private static final int NBBINS_POSITION = 12;

    // default bins for inter-block spacing
    private static final int NBBINS_SPACE = 5;

    // default bins for block character density
    private static final int NBBINS_DENSITY = 5;

    // projection scale for line length
    private static final int LINESCALE = 10;

    private Lexicon lexicon = Lexicon.getInstance();

    public HeaderParser(EngineParsers parsers, CntManager cntManager) {
        super(GrobidModels.HEADER, cntManager);
        this.parsers = parsers;
        GrobidProperties.getInstance();
    }

    public HeaderParser(EngineParsers parsers) {
        super(GrobidModels.HEADER);
        this.parsers = parsers;
        GrobidProperties.getInstance();
    }

    /**
     * Processing with application of the segmentation model
     */
    public Pair<String, Document> processing(File input, BiblioItem resHeader, GrobidAnalysisConfig config) {
        DocumentSource documentSource = null;
        try {
            documentSource = DocumentSource.fromPdf(input, config.getStartPage(), config.getEndPage());
            Document doc = parsers.getSegmentationParser().processing(documentSource, config);

            String tei = processingHeaderSection(config, doc, resHeader, true);
            return new Pair<String, Document>(tei, doc); // HACK ImmutablePair
        } finally {
            if (documentSource != null) {
                documentSource.close(true, true, true);
            }
        }
    }

    /**
     * Header processing after application of the segmentation model
     */
    public String processingHeaderSection(GrobidAnalysisConfig config, Document doc, BiblioItem resHeader, boolean serialize) {
        try {
            SortedSet<DocumentPiece> documentHeaderParts = documentHeaderParts = doc.getDocumentPart(SegmentationLabels.HEADER);
            List<LayoutToken> tokenizations = doc.getTokenizations();

            if (resHeader != null) {
                doc.resHeader = resHeader; // TODO: loose end.
            }

            if (documentHeaderParts != null) {
                List<LayoutToken> tokenizationsHeader = new ArrayList<LayoutToken>();

                for (DocumentPiece docPiece : documentHeaderParts) {
                    DocumentPointer dp1 = docPiece.getLeft();
                    DocumentPointer dp2 = docPiece.getRight();

                    int tokens = dp1.getTokenDocPos();
                    int tokene = dp2.getTokenDocPos();
                    for (int i = tokens; i < tokene; i++) {
                        tokenizationsHeader.add(tokenizations.get(i));
                    }
                }

                //String header = getSectionHeaderFeatured(doc, documentHeaderParts, true);
                Pair<String, List<LayoutToken>> featuredHeader = getSectionHeaderFeatured(doc, documentHeaderParts);
                String header = featuredHeader.getLeft();
                List<LayoutToken> headerTokenization = featuredHeader.getRight();
                String res = null;
                if ((header != null) && (header.trim().length() > 0)) {
                    res = label(header);
                    /*resHeader = */ resultExtraction(res, headerTokenization, /*resHeader, */doc);
                }

                // language identification
                StringBuilder contentSample = new StringBuilder();
                if (doc.resHeader.getTitle() != null) {
                    contentSample.append(doc.resHeader.getTitle());
                }
                if (doc.resHeader.getAbstract() != null) {
                    contentSample.append("\n");
                    contentSample.append(doc.resHeader.getAbstract());
                }
                if (contentSample.length() < 200) {
                    // we can exploit more textual content to ensure that the language identification will be
                    // correct
                    SortedSet<DocumentPiece> documentBodyParts = doc.getDocumentPart(SegmentationLabels.BODY);
                    if (documentBodyParts != null) {
                        StringBuilder contentBuffer = new StringBuilder();
                        for (DocumentPiece docPiece : documentBodyParts) {
                            DocumentPointer dp1 = docPiece.getLeft();
                            DocumentPointer dp2 = docPiece.getRight();

                            int tokens = dp1.getTokenDocPos();
                            int tokene = dp2.getTokenDocPos();
                            for (int i = tokens; i < tokene; i++) {
                                contentBuffer.append(tokenizations.get(i));
                                contentBuffer.append(" ");
                            }
                        }
                        contentSample.append(" ");
                        contentSample.append(contentBuffer.toString());
                    }
                }
                Language langu = languageUtilities.runLanguageId(contentSample.toString());
                if (langu != null) {
                    String lang = langu.getLang();
                    doc.setLanguage(lang);
                    doc.resHeader.setLanguage(lang);
                }

                if (doc.resHeader != null) {
                    if (doc.resHeader.getAbstract() != null) {
                        doc.resHeader.setAbstract(TextUtilities.dehyphenizeHard(doc.resHeader.getAbstract()));
                        //doc.resHeader.setAbstract(TextUtilities.dehyphenize(doc.resHeader.getAbstract()));
                    }
                    BiblioItem.cleanTitles(doc.resHeader);
                    if (doc.resHeader.getTitle() != null) {
                        // String temp =
                        // utilities.dehyphenizeHard(doc.resHeader.getTitle());
                        String temp = TextUtilities.dehyphenize(doc.resHeader.getTitle());
                        temp = temp.trim();
                        if (temp.length() > 1) {
                            if (temp.startsWith("1"))
                                temp = temp.substring(1, temp.length());
                            temp = temp.trim();
                        }
                        doc.resHeader.setTitle(temp);
                    }
                    if (doc.resHeader.getBookTitle() != null) {
                        doc.resHeader.setBookTitle(TextUtilities.dehyphenize(doc.resHeader.getBookTitle()));
                    }

                    doc.resHeader.setOriginalAuthors(doc.resHeader.getAuthors());
                    doc.resHeader.getAuthorsTokens();

                    boolean fragmentedAuthors = false;
                    boolean hasMarker = false;
                    List<Integer> authorsBlocks = new ArrayList<Integer>();
                    List<List<LayoutToken>> authorSegments = new ArrayList<>();
                    if (doc.resHeader.getAuthorsTokens() != null) {
                        // split the list of layout tokens when token "\t" is met
                        List<LayoutToken> currentSegment = new ArrayList<>();
                        for(LayoutToken theToken : doc.resHeader.getAuthorsTokens()) {
                            if (theToken.getText() != null && theToken.getText().equals("\t")) {
                                if (currentSegment.size() > 0)
                                    authorSegments.add(currentSegment);
                                currentSegment = new ArrayList<>();
                            } else
                                currentSegment.add(theToken);
                        }
                        // last segment
                        if (currentSegment.size() > 0)
                            authorSegments.add(currentSegment);

                        if (authorSegments.size() > 1) {
                            fragmentedAuthors = true;
                        }
                        for (int k = 0; k < authorSegments.size(); k++) {
                            if (authorSegments.get(k).size() == 0)
                                continue;
                            List<Person> localAuthors = parsers.getAuthorParser()
                                .processingHeaderWithLayoutTokens(authorSegments.get(k), doc.getPDFAnnotations());
                            if (localAuthors != null) {
                                for (Person pers : localAuthors) {
                                    doc.resHeader.addFullAuthor(pers);
                                    if (pers.getMarkers() != null) {
                                        hasMarker = true;
                                    }
                                    authorsBlocks.add(k);
                                }
                            }
                        }
                    }


                    // remove invalid authors (no last name, noise, etc.)
                    doc.resHeader.setFullAuthors(Person.sanityCheck(doc.resHeader.getFullAuthors()));

                    doc.resHeader.setFullAffiliations(
                            parsers.getAffiliationAddressParser().processReflow(res, tokenizations));
                    doc.resHeader.attachEmails();
                    boolean attached = false;
                    if (fragmentedAuthors && !hasMarker) {
                        if (doc.resHeader.getFullAffiliations() != null) {
                            if (authorSegments != null) {
                                if (doc.resHeader.getFullAffiliations().size() == authorSegments.size()) {
                                    int k = 0;
                                    List<Person> persons = doc.resHeader.getFullAuthors();
                                    for (Person pers : persons) {
                                        if (k < authorsBlocks.size()) {
                                            int indd = authorsBlocks.get(k);
                                            if (indd < doc.resHeader.getFullAffiliations().size()) {
                                                pers.addAffiliation(doc.resHeader.getFullAffiliations().get(indd));
                                            }
                                        }
                                        k++;
                                    }
                                    attached = true;
                                    doc.resHeader.setFullAffiliations(null);
                                    doc.resHeader.setAffiliation(null);
                                }
                            }
                        }
                    }
                    if (!attached) {
                        doc.resHeader.attachAffiliations();
                    }

                    // remove duplicated authors
                    doc.resHeader.setFullAuthors(Person.deduplicate(doc.resHeader.getFullAuthors()));

                    if (doc.resHeader.getEditors() != null) {
                        // TBD: consider segments also for editors, like for authors above
                        doc.resHeader.setFullEditors(parsers.getAuthorParser().processingHeader(doc.resHeader.getEditors()));
                    }

                    // below using the reference strings to improve the metadata extraction, it will have to
                    // be reviewed for something safer as just a straightforward correction
                    /*if (doc.resHeader.getReference() != null) {
                        BiblioItem refer = parsers.getCitationParser().processingString(doc.resHeader.getReference(), 0);
                        BiblioItem.correct(doc.resHeader, refer);
                    }*/
                }

                // keyword post-processing
                if (doc.resHeader.getKeyword() != null) {
                    String keywords = TextUtilities.dehyphenize(doc.resHeader.getKeyword());
                    keywords = BiblioItem.cleanKeywords(keywords);
                    doc.resHeader.setKeyword(keywords.replace("\n", " ").replace("  ", " "));
                    List<Keyword> keywordsSegmented = BiblioItem.segmentKeywords(keywords);
                    if ((keywordsSegmented != null) && (keywordsSegmented.size() > 0))
                        doc.resHeader.setKeywords(keywordsSegmented);
                }

                // DOI pass
                List<String> dois = doc.getDOIMatches();
                if (dois != null) {
                    if ((dois.size() == 1) && (doc.resHeader != null)) {
                        doc.resHeader.setDOI(dois.get(0));
                    }
                }

                doc.resHeader = consolidateHeader(doc.resHeader, config.getConsolidateHeader());

                // normalization of dates
                if (doc.resHeader != null) {
                    if (doc.resHeader.getPublicationDate() != null) {
                        List<Date> dates = parsers.getDateParser().processing(doc.resHeader.getPublicationDate());
                        // most basic heuristic, we take the first date - to be
                        // revised...
                        if (dates != null) {
                            if (dates.size() > 0) {
                                doc.resHeader.setNormalizedPublicationDate(dates.get(0));
                            }
                        }
                    }

                    if (doc.resHeader.getSubmissionDate() != null) {
                        List<Date> dates = parsers.getDateParser().processing(doc.resHeader.getSubmissionDate());
                        if (dates != null) {
                            if (dates.size() > 0) {
                                doc.resHeader.setNormalizedSubmissionDate(dates.get(0));
                            }
                        }
                    }

                    if (doc.resHeader.getDownloadDate() != null) {
                        List<Date> dates = parsers.getDateParser().processing(doc.resHeader.getDownloadDate());
                        if (dates != null) {
                            if (dates.size() > 0) {
                                doc.resHeader.setNormalizedDownloadDate(dates.get(0));
                            }
                        }
                    }

                    if (doc.resHeader.getServerDate() != null) {
                        List<Date> dates = parsers.getDateParser().processing(doc.resHeader.getServerDate());
                        if (dates != null) {
                            if (dates.size() > 0) {
                                doc.resHeader.setNormalizedServerDate(dates.get(0));
                            }
                        }
                    }
                }

                // we don't need to serialize if we process the full text (it would be done 2 times)
                if (serialize) {
                    TEIFormatter teiFormatter = new TEIFormatter(doc, null);
                    StringBuilder tei = teiFormatter.toTEIHeader(doc.resHeader, null, null, config);
                    tei.append("\t</text>\n");
                    tei.append("</TEI>\n");
                    return tei.toString();
                } else
                    return null;
            }
        } catch (Exception e) {
            throw new GrobidException("An exception occurred while running Grobid.", e);
        }
        return null;
    }


    /**
     * Return the header section with features to be processed by the sequence labelling model
     */
    public Pair<String, List<LayoutToken>> getSectionHeaderFeatured(Document doc,
                                           SortedSet<DocumentPiece> documentHeaderParts) {
        FeatureFactory featureFactory = FeatureFactory.getInstance();
        StringBuilder header = new StringBuilder();
        String currentFont = null;
        int currentFontSize = -1;

        // vector for features
        FeaturesVectorHeader features;
        FeaturesVectorHeader previousFeatures = null;

        double lineStartX = Double.NaN;
        boolean indented = false;
        boolean centered = false;

        boolean endblock;
        //for (Integer blocknum : blockDocumentHeaders) {
        List<Block> blocks = doc.getBlocks();
        if ((blocks == null) || blocks.size() == 0) {
            return null;
        }

        List<LayoutToken> headerTokenizations = new ArrayList<LayoutToken>();

        // find the largest, smallest and average size font on the header section
        double largestFontSize = 0.0;
        double smallestFontSize = 100000.0;
        double averageFontSize;
        double accumulatedFontSize = 0.0;
        int nbTokens = 0;
        for (DocumentPiece docPiece : documentHeaderParts) {
            DocumentPointer dp1 = docPiece.getLeft();
            DocumentPointer dp2 = docPiece.getRight();

            for (int blockIndex = dp1.getBlockPtr(); blockIndex <= dp2.getBlockPtr(); blockIndex++) {
                Block block = blocks.get(blockIndex);

                List<LayoutToken> tokens = block.getTokens();
                if ((tokens == null) || (tokens.size() == 0)) {
                    continue;
                }

                for(LayoutToken token : tokens) {
                    if (token.getFontSize() > largestFontSize) {
                        largestFontSize = token.getFontSize();
                    }

                    if (token.getFontSize() < smallestFontSize) {
                        smallestFontSize = token.getFontSize();
                    }

                    accumulatedFontSize += token.getFontSize();
                    nbTokens++;
                }
            }
        }
        averageFontSize = accumulatedFontSize / nbTokens;

        // TBD: this would need to be made more efficient, by applying the regex only to a limited
        // part of the tokens
        /*List<LayoutToken> tokenizations = doc.getTokenizations();
        List<OffsetPosition> locationPositions = lexicon.tokenPositionsLocationNames(tokenizations);
        List<OffsetPosition> urlPositions = lexicon.tokenPositionsUrlPattern(tokenizations);
        List<OffsetPosition> emailPositions = lexicon.tokenPositionsEmailPattern(tokenizations);*/

        for (DocumentPiece docPiece : documentHeaderParts) {
            DocumentPointer dp1 = docPiece.getLeft();
            DocumentPointer dp2 = docPiece.getRight();

            for (int blockIndex = dp1.getBlockPtr(); blockIndex <= dp2.getBlockPtr(); blockIndex++) {
                Block block = blocks.get(blockIndex);
                boolean newline = false;
                boolean previousNewline = true;
                endblock = false;
                double spacingPreviousBlock = 0.0; // discretized

                if (previousFeatures != null)
                    previousFeatures.blockStatus = "BLOCKEND";

                List<LayoutToken> tokens = block.getTokens();
                if ((tokens == null) || (tokens.size() == 0)) {
                    continue;
                }

                String localText = block.getText();
                if (localText == null)
                    continue;
                int startIndex = 0;
                int n = 0;
                if (blockIndex == dp1.getBlockPtr()) {
                    //n = block.getStartToken();
                    n = dp1.getTokenDocPos() - block.getStartToken();
                    startIndex = dp1.getTokenDocPos() - block.getStartToken();
                }

                // character density of the block
                double density = 0.0;
                if ( (block.getHeight() != 0.0) && (block.getWidth() != 0.0) &&
                     (block.getText() != null) && (!block.getText().contains("@PAGE")) &&
                     (!block.getText().contains("@IMAGE")) )
                    density = (double)block.getText().length() / (block.getHeight() * block.getWidth());

                String[] lines = localText.split("[\\n\\r]");
                // set the max length of the lines in the block, in number of characters
                int maxLineLength = 0;
                for(int p=0; p<lines.length; p++) {
                    if (lines[p].length() > maxLineLength)
                        maxLineLength = lines[p].length();
                }

                /*for (int li = 0; li < lines.length; li++) {
                    String line = lines[li];

                    features.lineLength = featureFactory
                            .linearScaling(line.length(), maxLineLength, LINESCALE);

                    features.punctuationProfile = TextUtilities.punctuationProfile(line);
                }*/

                List<OffsetPosition> locationPositions = lexicon.tokenPositionsLocationNames(tokens);
                List<OffsetPosition> emailPositions = lexicon.tokenPositionsEmailPattern(tokens);
                List<OffsetPosition> urlPositions = lexicon.tokenPositionsUrlPattern(tokens);

                /*for (OffsetPosition position : emailPositions) {
                    System.out.println(position.start + " " + position.end + " / " + tokens.get(position.start) + " ... " + tokens.get(position.end));
                }*/

                while (n < tokens.size()) {
                    if (blockIndex == dp2.getBlockPtr()) {
                        if (n > dp2.getTokenDocPos() - block.getStartToken()) {
                            break;
                        }
                    }

                    LayoutToken token = tokens.get(n);
                    headerTokenizations.add(token);

                    String text = token.getText();
                    if (text == null) {
                        n++;
                        continue;
                    }

                    text = text.replace(" ", "");
                    if (text.length() == 0) {
                        n++;
                        continue;
                    }

                    if (text.equals("\n") || text.equals("\r")) {
                        previousNewline = true;
                        newline = false;
                        n++;
                        continue;
                    }

                    if (previousNewline) {
                        newline = true;
                        previousNewline = false;
                        if (token != null && previousFeatures != null) {
                            double previousLineStartX = lineStartX;
                            lineStartX = token.getX();
                            double characterWidth = token.width / token.getText().length();
                            if (!Double.isNaN(previousLineStartX)) {
                                // Indentation if line start is > 1 character width to the right of previous line start
                                if (lineStartX - previousLineStartX > characterWidth)
                                    indented = true;
                                // Indentation ends if line start is > 1 character width to the left of previous line start
                                else if (previousLineStartX - lineStartX > characterWidth)
                                    indented = false;
                                // Otherwise indentation is unchanged
                            }
                        }
                    } else{
                        newline = false;
                    }
                    // centered ?

                    // final sanitisation and filtering for the token
                    text = text.replaceAll("[ \n]", "");
                    if (TextUtilities.filterLine(text)) {
                        n++;
                        continue;
                    }

                    features = new FeaturesVectorHeader();
                    features.token = token;
                    features.string = text;

                    if (newline)
                        features.lineStatus = "LINESTART";

                    Matcher m0 = featureFactory.isPunct.matcher(text);
                    if (m0.find()) {
                        features.punctType = "PUNCT";
                    }
                    if (text.equals("(") || text.equals("[")) {
                        features.punctType = "OPENBRACKET";

                    } else if (text.equals(")") || text.equals("]")) {
                        features.punctType = "ENDBRACKET";

                    } else if (text.equals(".")) {
                        features.punctType = "DOT";

                    } else if (text.equals(",")) {
                        features.punctType = "COMMA";

                    } else if (text.equals("-")) {
                        features.punctType = "HYPHEN";

                    } else if (text.equals("\"") || text.equals("\'") || text.equals("`")) {
                        features.punctType = "QUOTE";
                    }

                    if (n == startIndex) {
                        // beginning of block
                        features.lineStatus = "LINESTART";
                        features.blockStatus = "BLOCKSTART";
                    } else if ((n == tokens.size() - 1) || (n+1 > dp2.getTokenDocPos() - block.getStartToken())) {
                        // end of block
                        features.lineStatus = "LINEEND";
                        previousNewline = true;
                        features.blockStatus = "BLOCKEND";
                        endblock = true;
                    } else {
                        // look ahead to see if we are at the end of a line within the block
                        boolean endline = false;

                        int ii = 1;
                        boolean endloop = false;
                        while ((n + ii < tokens.size()) && (!endloop)) {
                            LayoutToken tok = tokens.get(n + ii);
                            if (tok != null) {
                                String toto = tok.getText();
                                if (toto != null) {
                                    if (toto.equals("\n") || text.equals("\r")) {
                                        endline = true;
                                        endloop = true;
                                    } else {
                                        if ((toto.trim().length() != 0)
                                                && (!text.equals("\u00A0"))
                                                && (!(toto.contains("@IMAGE")))
                                                && (!(toto.contains("@PAGE")))
                                                && (!text.contains(".pbm"))
                                                && (!text.contains(".ppm"))
                                                && (!text.contains(".png"))
                                                && (!text.contains(".svg"))
                                                && (!text.contains(".jpg"))) {
                                            endloop = true;
                                        }
                                    }
                                }
                            }

                            if (n + ii == tokens.size() - 1) {
                                endblock = true;
                                endline = true;
                            }

                            ii++;
                        }

                        if ((!endline) && !(newline)) {
                            features.lineStatus = "LINEIN";
                        } else if (!newline) {
                            features.lineStatus = "LINEEND";
                            previousNewline = true;
                        }

                        if ((!endblock) && (features.blockStatus == null))
                            features.blockStatus = "BLOCKIN";
                        else if (features.blockStatus == null)
                            features.blockStatus = "BLOCKEND";
                    }

                    if (indented) {
                        features.alignmentStatus = "LINEINDENT";
                    }
                    else {
                        features.alignmentStatus = "ALIGNEDLEFT";
                    }

                    if (text.length() == 1) {
                        features.singleChar = true;
                    }

                    if (Character.isUpperCase(text.charAt(0))) {
                        features.capitalisation = "INITCAP";
                    }

                    if (featureFactory.test_all_capital(text)) {
                        features.capitalisation = "ALLCAP";
                    }

                    if (featureFactory.test_digit(text)) {
                        features.digit = "CONTAINSDIGITS";
                    }

                    Matcher m = featureFactory.isDigit.matcher(text);
                    if (m.find()) {
                        features.digit = "ALLDIGIT";
                    }

                    if (featureFactory.test_common(text)) {
                        features.commonName = true;
                    }

                    if (featureFactory.test_names(text)) {
                        features.properName = true;
                    }

                    if (featureFactory.test_month(text)) {
                        features.month = true;
                    }

                    Matcher m2 = featureFactory.year.matcher(text);
                    if (m2.find()) {
                        features.year = true;
                    }

                    // check token offsets for email and http address, or known location
                    if (locationPositions != null) {
                        for(OffsetPosition thePosition : locationPositions) {
                            if (n >= thePosition.start && n <= thePosition.end) {
                                features.locationName = true;
                                break;
                            }
                        }
                    }
                    if (emailPositions != null) {
                        for(OffsetPosition thePosition : emailPositions) {
                            if (n >= thePosition.start && n <= thePosition.end) {
                                features.email = true;
                                break;
                            }
                        }
                    }
                    if (urlPositions != null) {
                        for(OffsetPosition thePosition : urlPositions) {
                            if (n >= thePosition.start && n <= thePosition.end) {
                                features.http = true;
                                break;
                            }
                        }
                    }

                    if (currentFont == null) {
                        currentFont = token.getFont();
                        features.fontStatus = "NEWFONT";
                    } else if (!currentFont.equals(token.getFont())) {
                        currentFont = token.getFont();
                        features.fontStatus = "NEWFONT";
                    } else
                        features.fontStatus = "SAMEFONT";

                    int newFontSize = (int) token.getFontSize();
                    if (currentFontSize == -1) {
                        currentFontSize = newFontSize;
                        features.fontSize = "HIGHERFONT";
                    } else if (currentFontSize == newFontSize) {
                        features.fontSize = "SAMEFONTSIZE";
                    } else if (currentFontSize < newFontSize) {
                        features.fontSize = "HIGHERFONT";
                        currentFontSize = newFontSize;
                    } else if (currentFontSize > newFontSize) {
                        features.fontSize = "LOWERFONT";
                        currentFontSize = newFontSize;
                    }

                    if (token.getFontSize() == largestFontSize)
                        features.largestFont = true;
                    if (token.getFontSize() == smallestFontSize)
                        features.smallestFont = true;
                    if (token.getFontSize() > averageFontSize)
                        features.largerThanAverageFont = true;

                    if (token.getBold())
                        features.bold = true;

                    if (token.getItalic())
                        features.italic = true;

                    if (features.capitalisation == null)
                        features.capitalisation = "NOCAPS";

                    if (features.digit == null)
                        features.digit = "NODIGIT";

                    if (features.punctType == null)
                        features.punctType = "NOPUNCT";

                    /*if (spacingPreviousBlock != 0.0) {
                        features.spacingWithPreviousBlock = featureFactory
                            .linearScaling(spacingPreviousBlock-doc.getMinBlockSpacing(), doc.getMaxBlockSpacing()-doc.getMinBlockSpacing(), NBBINS_SPACE);
                    }*/

                    if (density != -1.0) {
                        features.characterDensity = featureFactory
                            .linearScaling(density-doc.getMinCharacterDensity(), doc.getMaxCharacterDensity()-doc.getMinCharacterDensity(), NBBINS_DENSITY);
//System.out.println((density-doc.getMinCharacterDensity()) + " " + (doc.getMaxCharacterDensity()-doc.getMinCharacterDensity()) + " " + NBBINS_DENSITY + " " + features.characterDensity);
                    }

                    if (previousFeatures != null)
                        header.append(previousFeatures.printVector());
                    previousFeatures = features;

                    n++;
                }

                if (previousFeatures != null) {
                    previousFeatures.blockStatus = "BLOCKEND";
                    previousFeatures.lineStatus = "LINEEND";
                    header.append(previousFeatures.printVector());
                    previousFeatures = null;
                }
            }


        }

        return Pair.of(header.toString(), headerTokenizations);
    }

    /**
     * Extract results from a labelled header.
     *
     * @param result        result
     * @param tokenizations list of tokens
     * @param biblio        biblio item
     * @return a biblio item
     */
    public void /*BiblioItem*/ resultExtraction(String result, List<LayoutToken> tokenizations, /*BiblioItem biblio, */ Document doc) {

        TaggingLabel lastClusterLabel = null;
        TaggingTokenClusteror clusteror = new TaggingTokenClusteror(GrobidModels.HEADER, result, tokenizations);

        String tokenLabel = null;
        List<TaggingTokenCluster> clusters = clusteror.cluster();
        for (TaggingTokenCluster cluster : clusters) {
            if (cluster == null) {
                continue;
            }
            TaggingLabel clusterLabel = cluster.getTaggingLabel();
            Engine.getCntManager().i(clusterLabel);

            String clusterContent = LayoutTokensUtil.normalizeDehyphenizeText(cluster.concatTokens());
            String clusterNonDehypenizedContent = LayoutTokensUtil.toText(cluster.concatTokens());
            if (clusterLabel.equals(TaggingLabels.HEADER_TITLE)) {
                /*if (doc.resHeader.getTitle() != null && isDifferentContent(doc.resHeader.getTitle(), clusterContent))
                    doc.resHeader.setTitle(doc.resHeader.getTitle() + clusterContent);
                else*/
                if (doc.resHeader.getTitle() == null) {
                    doc.resHeader.setTitle(clusterContent);
                    List<LayoutToken> tokens = getLayoutTokens(cluster);
                    doc.resHeader.addTitleTokens(tokens);
                }
            } else if (clusterLabel.equals(TaggingLabels.HEADER_AUTHOR)) {
                //if (doc.resHeader.getAuthors() != null && isDifferentandNotIncludedContent(doc.resHeader.getAuthors(), clusterContent)) {
                if (doc.resHeader.getAuthors() != null) {
                    doc.resHeader.setAuthors(doc.resHeader.getAuthors() + "\t" + clusterNonDehypenizedContent);
                    //doc.resHeader.addAuthorsToken(new LayoutToken("\n", TaggingLabels.HEADER_AUTHOR));
                    doc.resHeader.addAuthorsToken(new LayoutToken("\t", TaggingLabels.HEADER_AUTHOR));

                    List<LayoutToken> tokens = cluster.concatTokens();
                    doc.resHeader.addAuthorsTokens(tokens);
                } else {
                    doc.resHeader.setAuthors(clusterNonDehypenizedContent);

                    List<LayoutToken> tokens = cluster.concatTokens();
                    doc.resHeader.addAuthorsTokens(tokens);
                }
            } /*else if (clusterLabel.equals(TaggingLabels.HEADER_TECH)) {
                doc.resHeader.setItem(BiblioItem.TechReport);
                if (doc.resHeader.getBookType() != null) {
                    doc.resHeader.setBookType(doc.resHeader.getBookType() + clusterContent);
                } else
                    doc.resHeader.setBookType(clusterContent);

            } else if (clusterLabel.equals(TaggingLabels.HEADER_LOCATION)) {

                if (doc.resHeader.getLocation() != null) {
                    doc.resHeader.setLocation(doc.resHeader.getLocation() + clusterContent);
                } else
                    doc.resHeader.setLocation(clusterContent);

            }*/
            else if (clusterLabel.equals(TaggingLabels.HEADER_MEETING)) {

                if (doc.resHeader.getMeeting() != null) {
                    doc.resHeader.setMeeting(doc.resHeader.getMeeting() + ", " + clusterContent);
                } else
                    doc.resHeader.setMeeting(clusterContent);

            } else if (clusterLabel.equals(TaggingLabels.HEADER_DATE)) {
                // it appears that the same date is quite often repeated,
                // we should check, before adding a new date segment, if it is
                // not already present

                // alternatively we can only keep the first continuous date

                /*if (doc.resHeader.getPublicationDate() != null && isDifferentandNotIncludedContent(doc.resHeader.getPublicationDate(), clusterContent))
                    doc.resHeader.setPublicationDate(doc.resHeader.getPublicationDate() + " " + clusterContent);
                else*/
                // for checking if the date is a server date, we simply look at the string
                /*if (doc.resHeader.getServerDate() == null) {
                    if (clusterContent.toLowerCase().indexOf("server") != -1) {
                        doc.resHeader.setServerDate(clusterNonDehypenizedContent);
                        continue;
                    }
                }*/
                if (doc.resHeader.getPublicationDate() != null && doc.resHeader.getPublicationDate().length() < clusterNonDehypenizedContent.length())
                    doc.resHeader.setPublicationDate(clusterNonDehypenizedContent);
                else if (doc.resHeader.getPublicationDate() == null)
                    doc.resHeader.setPublicationDate(clusterNonDehypenizedContent);

            } /*else if (clusterLabel.equals(TaggingLabels.HEADER_DATESUB)) {
                // it appears that the same date is quite often repeated,
                // we should check, before adding a new date segment, if it is
                // not already present

                if (doc.resHeader.getSubmissionDate() != null && isDifferentandNotIncludedContent(doc.resHeader.getSubmissionDate(), clusterNonDehypenizedContent)) {
                    doc.resHeader.setSubmissionDate(doc.resHeader.getSubmissionDate() + " " + clusterNonDehypenizedContent);
                } else
                    doc.resHeader.setSubmissionDate(clusterNonDehypenizedContent);
            } else if (clusterLabel.equals(TaggingLabels.HEADER_DOWNLOAD)) {
                // it appears that the same date is quite often repeated,
                // we should check, before adding a new date segment, if it is
                // not already present

                if (doc.resHeader.getDownloadDate() != null && isDifferentandNotIncludedContent(doc.resHeader.getDownloadDate(), clusterNonDehypenizedContent)) {
                    doc.resHeader.setDownloadDate(doc.resHeader.getDownloadDate() + " " + clusterNonDehypenizedContent);
                } else
                    doc.resHeader.setDownloadDate(clusterNonDehypenizedContent);
            }*/ else if (clusterLabel.equals(TaggingLabels.HEADER_PAGE)) {
                /*if (doc.resHeader.getPageRange() != null) {
                    doc.resHeader.setPageRange(doc.resHeader.getPageRange() + clusterContent);
                }*/
                if (doc.resHeader.getPageRange() == null)
                    doc.resHeader.setPageRange(clusterContent);
            } else if (clusterLabel.equals(TaggingLabels.HEADER_EDITOR)) {
                if (doc.resHeader.getEditors() != null) {
                    doc.resHeader.setEditors(doc.resHeader.getEditors() + "\n" + clusterNonDehypenizedContent);
                } else
                    doc.resHeader.setEditors(clusterNonDehypenizedContent);
            } /*else if (clusterLabel.equals(TaggingLabels.HEADER_INSTITUTION)) {
                if (doc.resHeader.getInstitution() != null) {
                    doc.resHeader.setInstitution(doc.resHeader.getInstitution() + clusterContent);
                } else
                    doc.resHeader.setInstitution(clusterContent);
            }*/ else if (clusterLabel.equals(TaggingLabels.HEADER_NOTE)) {
                if (doc.resHeader.getNote() != null) {
                    doc.resHeader.setNote(doc.resHeader.getNote() + " " + clusterContent);
                } else
                    doc.resHeader.setNote(clusterContent);
            } else if (clusterLabel.equals(TaggingLabels.HEADER_ABSTRACT)) {
                if (doc.resHeader.getAbstract() != null) {
                    // this will need to be reviewed with more training data, for the moment
                    // avoid concatenation for abstracts as it brings more noise than correct pieces
                    //doc.resHeader.setAbstract(doc.resHeader.getAbstract() + " " + clusterContent);
                } else {
                    doc.resHeader.setAbstract(clusterContent);
                    List<LayoutToken> tokens = getLayoutTokens(cluster);
                    doc.resHeader.addAbstractTokens(tokens);
                }
            } else if (clusterLabel.equals(TaggingLabels.HEADER_REFERENCE)) {
                //if (doc.resHeader.getReference() != null) {
                if (doc.resHeader.getReference() != null && doc.resHeader.getReference().length() < clusterNonDehypenizedContent.length()) {
                    doc.resHeader.setReference(clusterNonDehypenizedContent);
                } else
                    doc.resHeader.setReference(clusterNonDehypenizedContent);
            } else if (clusterLabel.equals(TaggingLabels.HEADER_FUNDING)) {
                if (doc.resHeader.getFunding() != null) {
                    doc.resHeader.setFunding(doc.resHeader.getFunding() + " \n " + clusterContent);
                } else
                    doc.resHeader.setFunding(clusterContent);
            } else if (clusterLabel.equals(TaggingLabels.HEADER_COPYRIGHT)) {
                if (doc.resHeader.getCopyright() != null) {
                    doc.resHeader.setCopyright(doc.resHeader.getCopyright() + " " + clusterContent);
                } else
                    doc.resHeader.setCopyright(clusterContent);
            } else if (clusterLabel.equals(TaggingLabels.HEADER_AFFILIATION)) {
                // affiliation **makers** should be marked SINGLECHAR LINESTART
                if (doc.resHeader.getAffiliation() != null) {
                    doc.resHeader.setAffiliation(doc.resHeader.getAffiliation() + " ; " + clusterContent);
                } else
                    doc.resHeader.setAffiliation(clusterContent);
            } else if (clusterLabel.equals(TaggingLabels.HEADER_ADDRESS)) {
                if (doc.resHeader.getAddress() != null) {
                    doc.resHeader.setAddress(doc.resHeader.getAddress() + " " + clusterContent);
                } else
                    doc.resHeader.setAddress(clusterContent);
            } else if (clusterLabel.equals(TaggingLabels.HEADER_EMAIL)) {
                if (doc.resHeader.getEmail() != null) {
                    doc.resHeader.setEmail(doc.resHeader.getEmail() + "\t" + clusterNonDehypenizedContent);
                } else
                    doc.resHeader.setEmail(clusterNonDehypenizedContent);
            } else if (clusterLabel.equals(TaggingLabels.HEADER_PUBNUM)) {
                if (doc.resHeader.getPubnum() != null && isDifferentandNotIncludedContent(doc.resHeader.getPubnum(), clusterContent)) {
                    String currentPubnum = doc.resHeader.getPubnum();
                    doc.resHeader.setPubnum(clusterContent);
                    doc.resHeader.checkIdentifier();
                    doc.resHeader.setPubnum(currentPubnum);
                } else {
                    doc.resHeader.setPubnum(clusterContent);
                    doc.resHeader.checkIdentifier();
                }
            } else if (clusterLabel.equals(TaggingLabels.HEADER_KEYWORD)) {
                if (doc.resHeader.getKeyword() != null) {
                    doc.resHeader.setKeyword(doc.resHeader.getKeyword() + " \n " + clusterContent);
                } else
                    doc.resHeader.setKeyword(clusterContent);
            } else if (clusterLabel.equals(TaggingLabels.HEADER_PHONE)) {
                if (doc.resHeader.getPhone() != null) {
                    doc.resHeader.setPhone(doc.resHeader.getPhone() + clusterNonDehypenizedContent);
                } else
                    doc.resHeader.setPhone(clusterNonDehypenizedContent);
            } /*else if (clusterLabel.equals(TaggingLabels.HEADER_DEGREE)) {
                if (doc.resHeader.getDegree() != null) {
                    doc.resHeader.setDegree(doc.resHeader.getDegree() + clusterContent);
                } else
                    doc.resHeader.setDegree(clusterContent);
            }*/ else if (clusterLabel.equals(TaggingLabels.HEADER_WEB)) {
                if (doc.resHeader.getWeb() != null) {
                    doc.resHeader.setWeb(doc.resHeader.getWeb() + clusterNonDehypenizedContent);
                } else
                    doc.resHeader.setWeb(clusterNonDehypenizedContent);
            } /*else if (clusterLabel.equals(TaggingLabels.HEADER_DEDICATION)) {
                if (doc.resHeader.getDedication() != null) {
                    doc.resHeader.setDedication(doc.resHeader.getDedication() + clusterContent);
                } else
                    doc.resHeader.setDedication(clusterContent);
            }*/ else if (clusterLabel.equals(TaggingLabels.HEADER_SUBMISSION)) {
                if (doc.resHeader.getSubmission() != null) {
                    doc.resHeader.setSubmission(doc.resHeader.getSubmission() + " " + clusterContent);
                } else
                    doc.resHeader.setSubmission(clusterContent);
            } /*else if (clusterLabel.equals(TaggingLabels.HEADER_ENTITLE)) {
                if (doc.resHeader.getEnglishTitle() != null) {
//                    if (cluster.getFeatureBlock().contains("LINESTART")) {
//                        doc.resHeader.setEnglishTitle(doc.resHeader.getEnglishTitle() + " " + clusterContent);
//                    } else
                    doc.resHeader.setEnglishTitle(doc.resHeader.getEnglishTitle() + clusterContent);
                } else
                    doc.resHeader.setEnglishTitle(clusterContent);
            } else if (clusterLabel.equals(TaggingLabels.HEADER_VERSION)) {
                if (doc.resHeader.getVersion() != null && isDifferentandNotIncludedContent(doc.resHeader.getVersion(), clusterNonDehypenizedContent)) {
                    doc.resHeader.setVersion(doc.resHeader.getVersion() + clusterNonDehypenizedContent);
                } else
                    doc.resHeader.setVersion(clusterNonDehypenizedContent);
            }*/ else if (clusterLabel.equals(TaggingLabels.HEADER_DOCTYPE)) {
                if (doc.resHeader.getDocumentType() != null && isDifferentContent(doc.resHeader.getDocumentType(), clusterContent)) {
                    doc.resHeader.setDocumentType(doc.resHeader.getDocumentType() + " \n " + clusterContent);
                } else
                    doc.resHeader.setDocumentType(clusterContent);
            } else if (clusterLabel.equals(TaggingLabels.HEADER_WORKINGGROUP)) {
                /*if (doc.resHeader.getWorkingGroup() != null && isDifferentandNotIncludedContent(doc.resHeader.getWorkingGroup(), clusterContent)) {
                    doc.resHeader.setWorkingGroup(doc.resHeader.getWorkingGroup() + " " + clusterContent);
                }*/
                if (doc.resHeader.getWorkingGroup() == null)
                    doc.resHeader.setWorkingGroup(clusterContent);
            } else if (clusterLabel.equals(TaggingLabels.HEADER_PUBLISHER)) {
                /*if (doc.resHeader.getPublisher() != null && isDifferentandNotIncludedContent(doc.resHeader.getPublisher(), clusterContent)) {
                    doc.resHeader.setPublisher(doc.resHeader.getPublisher() + " " + clusterContent);
                }*/
                if (doc.resHeader.getPublisher() == null)
                    doc.resHeader.setPublisher(clusterContent);
            } else if (clusterLabel.equals(TaggingLabels.HEADER_JOURNAL)) {
                /*if (doc.resHeader.getJournal() != null && isDifferentandNotIncludedContent(doc.resHeader.getJournal(), clusterContent)) {
                    doc.resHeader.setJournal(doc.resHeader.getJournal() + " " + clusterContent);
                }*/
                if (doc.resHeader.getJournal() == null)
                    doc.resHeader.setJournal(clusterContent);
            }
            /*else if (clusterLabel.equals(TaggingLabels.HEADER_INTRO)) {
                return doc.resHeader;
            }*/
        }
        /*return doc.resHeader;*/
    }

    /**
     * In the context of field extraction, check if a newly extracted content is not redundant
     * with the already extracted content
     */
    private boolean isDifferentContent(String existingContent, String newContent) {
        if (existingContent == null) {
            return true;
        }
        if (newContent == null) {
            return false;
        }
        String newContentSimplified = newContent.toLowerCase();
        newContentSimplified = newContentSimplified.replace(" ", "").trim();
        String existinContentSimplified = existingContent.toLowerCase();
        existinContentSimplified = existinContentSimplified.replace(" ", "").trim();
        if (newContentSimplified.equals(existinContentSimplified))
            return false;
        else
            return true;
    }

    /**
     * In the context of field extraction, this variant of the previous method check if a newly
     * extracted content is not redundant globally and as any substring combination with the already
     * extracted content
     */
    private boolean isDifferentandNotIncludedContent(String existingContent, String newContent) {
        if (existingContent == null) {
            return true;
        }
        if (newContent == null) {
            return false;
        }
        String newContentSimplified = newContent.toLowerCase();
        newContentSimplified = newContentSimplified.replace(" ", "").trim();
        newContentSimplified = newContentSimplified.replace("-", "").trim();
        String existingContentSimplified = existingContent.toLowerCase();
        existingContentSimplified = existingContentSimplified.replace(" ", "").trim();
        existingContentSimplified = existingContentSimplified.replace("-", "").trim();
        if (newContentSimplified.equals(existingContentSimplified) ||
            existingContentSimplified.indexOf(newContentSimplified) != -1
            )
            return false;
        else
            return true;
    }

    private List<LayoutToken> getLayoutTokens(TaggingTokenCluster cluster) {
        List<LayoutToken> tokens = new ArrayList<>();

        for (LabeledTokensContainer container : cluster.getLabeledTokensContainers()) {
            tokens.addAll(container.getLayoutTokens());
        }

        return tokens;
    }

    /**
     * Extract results from a labelled header in the training format without any
     * string modification.
     *
     * @param result        result
     * @param tokenizations list of tokens
     * @return a result
     */
    public StringBuilder trainingExtraction(String result, List<LayoutToken> tokenizations) {
        // this is the main buffer for the whole header
        StringBuilder buffer = new StringBuilder();

        StringTokenizer st = new StringTokenizer(result, "\n");
        String s1 = null;
        String s2 = null;
        String lastTag = null;

        int p = 0;

        while (st.hasMoreTokens()) {
            boolean addSpace = false;
            String tok = st.nextToken().trim();

            if (tok.length() == 0) {
                continue;
            }
            StringTokenizer stt = new StringTokenizer(tok, "\t");
            // List<String> localFeatures = new ArrayList<String>();
            int i = 0;

            boolean newLine = false;
            int ll = stt.countTokens();
            while (stt.hasMoreTokens()) {
                String s = stt.nextToken().trim();
                if (i == 0) {
                    s2 = TextUtilities.HTMLEncode(s);
                    //s2 = s;

                    boolean strop = false;
                    while ((!strop) && (p < tokenizations.size())) {
                        String tokOriginal = tokenizations.get(p).t();
                        if (tokOriginal.equals(" ")
                                || tokOriginal.equals("\u00A0")) {
                            addSpace = true;
                        } else if (tokOriginal.equals(s)) {
                            strop = true;
                        }
                        p++;
                    }
                } else if (i == ll - 1) {
                    s1 = s;
                } else {
                    if (s.equals("LINESTART"))
                        newLine = true;
                    // localFeatures.add(s);
                }
                i++;
            }

            if (newLine) {
                buffer.append("<lb/>");
            }

            String lastTag0 = null;
            if (lastTag != null) {
                if (lastTag.startsWith("I-")) {
                    lastTag0 = lastTag.substring(2, lastTag.length());
                } else {
                    lastTag0 = lastTag;
                }
            }
            String currentTag0 = null;
            if (s1 != null) {
                if (s1.startsWith("I-")) {
                    currentTag0 = s1.substring(2, s1.length());
                } else {
                    currentTag0 = s1;
                }
            }

            if (lastTag != null) {
                testClosingTag(buffer, currentTag0, lastTag0);
            }

            boolean output;

            output = writeField(buffer, s1, lastTag0, s2, "<title>", "<docTitle>\n\t<titlePart>", addSpace);
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<author>", "<byline>\n\t<docAuthor>", addSpace);
            }
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<location>", "<address>", addSpace);
            }
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<address>", "<address>", addSpace);
            }
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<date>", "<date>", addSpace);
            }
            /*if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<date-submission>", "<date type=\"submission\">", addSpace);
            }
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<booktitle>", "<booktitle>", addSpace);
            }*/
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<page>", "<page>", addSpace);
            }
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<publisher>", "<publisher>", addSpace);
            }
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<journal>", "<journal>", addSpace);
            }
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<institution>", "<byline>\n\t<affiliation>", addSpace);
            }
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<affiliation>", "<byline>\n\t<affiliation>", addSpace);
            }
            /*if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<volume>", "<volume>", addSpace);
            }*/
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<editor>", "<editor>", addSpace);
            }
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<note>", "", addSpace);
            }
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<abstract>", "<div type=\"abstract\">", addSpace);
            }
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<email>", "<email>", addSpace);
            }
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<pubnum>", "<idno>", addSpace);
            }
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<keyword>", "<keyword>", addSpace);
            }
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<phone>", "<phone>", addSpace);
            }
            /*if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<degree>", "<note type=\"degree\">", addSpace);
            }*/
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<web>", "<ptr type=\"web\">", addSpace);
            }
            /*if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<dedication>", "<dedication>", addSpace);
            }*/
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<meeting>", "<meeting>", addSpace);
            }
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<submission>", "<note type=\"submission\">", addSpace);
            }
            /*if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<entitle>", "<note type=\"title\">", addSpace);
            }*/
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<reference>", "<reference>", addSpace);
            }
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<copyright>", "<note type=\"copyright\">", addSpace);
            }
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<funding>", "<note type=\"funding\">", addSpace);
            }
            /*if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<intro>", "<p type=\"introduction\">", addSpace);
            }*/
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<doctype>", "<note type=\"doctype\">", addSpace);
            }
            /*if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<version>", "<note type=\"version\">", addSpace);
            }*/
            /*if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<date-download>", "<date type=\"download\">", addSpace);
            }*/
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<group>", "<note type=\"group\">", addSpace);
            }
            if (!output) {
                output = writeField(buffer, s1, lastTag0, s2, "<other>", "", addSpace);
            }

            /*if (((s1.equals("<intro>")) || (s1.equals("I-<intro>"))) && intro) {
                break;
            }*/
            lastTag = s1;

            if (!st.hasMoreTokens()) {
                if (lastTag != null) {
                    testClosingTag(buffer, "", currentTag0);
                }
            }
        }

        return buffer;
    }

    private void testClosingTag(StringBuilder buffer, String currentTag0, String lastTag0) {
        if (!currentTag0.equals(lastTag0)) {
            // we close the current tag
            if (lastTag0.equals("<title>")) {
                buffer.append("</titlePart>\n\t</docTitle>\n");
            } else if (lastTag0.equals("<author>")) {
                buffer.append("</docAuthor>\n\t</byline>\n");
            } else if (lastTag0.equals("<location>")) {
                buffer.append("</address>\n");
            } else if (lastTag0.equals("<meeting>")) {
                buffer.append("</meeting>\n");
            } else if (lastTag0.equals("<date>")) {
                buffer.append("</date>\n");
            } else if (lastTag0.equals("<abstract>")) {
                buffer.append("</div>\n");
            } else if (lastTag0.equals("<address>")) {
                buffer.append("</address>\n");
            } else if (lastTag0.equals("<date-submission>")) {
                buffer.append("</date>\n");
            } else if (lastTag0.equals("<booktitle>")) {
                buffer.append("</booktitle>\n");
            } else if (lastTag0.equals("<pages>")) {
                buffer.append("</pages>\n");
            } else if (lastTag0.equals("<email>")) {
                buffer.append("</email>\n");
            } else if (lastTag0.equals("<publisher>")) {
                buffer.append("</publisher>\n");
            } else if (lastTag0.equals("<institution>")) {
                buffer.append("</affiliation>\n\t</byline>\n");
            } else if (lastTag0.equals("<keyword>")) {
                buffer.append("</keyword>\n");
            } else if (lastTag0.equals("<affiliation>")) {
                buffer.append("</affiliation>\n\t</byline>\n");
            } else if (lastTag0.equals("<note>")) {
                buffer.append("</note>\n");
            } else if (lastTag0.equals("<reference>")) {
                buffer.append("</reference>\n");
            } else if (lastTag0.equals("<copyright>")) {
                buffer.append("</note>\n");
            } else if (lastTag0.equals("<funding>")) {
                buffer.append("</note>\n");
            } else if (lastTag0.equals("<entitle>")) {
                buffer.append("</note>\n");
            } else if (lastTag0.equals("<submission>")) {
                buffer.append("</note>\n");
            } else if (lastTag0.equals("<dedication>")) {
                buffer.append("</dedication>\n");
            } else if (lastTag0.equals("<web>")) {
                buffer.append("</ptr>\n");
            } else if (lastTag0.equals("<phone>")) {
                buffer.append("</phone>\n");
            } else if (lastTag0.equals("<pubnum>")) {
                buffer.append("</idno>\n");
            } else if (lastTag0.equals("<degree>")) {
                buffer.append("</note>\n");
            } /*else if (lastTag0.equals("<intro>")) {
                buffer.append("</p>\n");
            }*/ else if (lastTag0.equals("<editor>")) {
                buffer.append("</editor>\n");
            } else if (lastTag0.equals("<version>")) {
                buffer.append("</note>\n");
            } else if (lastTag0.equals("<doctype>")) {
                buffer.append("</note>\n");
            } else if (lastTag0.equals("<date-download>")) {
                buffer.append("</date>\n");
            } else if (lastTag0.equals("<group>")) {
                buffer.append("</note>\n");
            }
        }
    }

    private boolean writeField(StringBuilder buffer, String s1, String lastTag0, String s2, String field, String outField, boolean addSpace) {
        boolean result = false;
        if ((s1.equals(field)) || (s1.equals("I-" + field))) {
            result = true;
            if (s1.equals(lastTag0) || (s1).equals("I-" + lastTag0)) {
                if (addSpace)
                    buffer.append(" ").append(s2);
                else
                    buffer.append(s2);
            } else
                buffer.append("\n\t").append(outField).append(s2);
        }
        return result;
    }

    /**
     * Consolidate an existing list of recognized citations based on access to
     * external internet bibliographic databases.
     *
     * @param resHeader original doc.resHeader item
     * @return consolidated doc.resHeader item
     */
    public BiblioItem consolidateHeader(BiblioItem resHeader, int consolidate) {
        if (consolidate == 0) {
            // not consolidation
            return resHeader;
        }
        Consolidation consolidator = null;
        try {
            consolidator = Consolidation.getInstance();
            if (consolidator.getCntManager() == null)
                consolidator.setCntManager(cntManager);
            /*List<BiblioItem> bibis = new ArrayList<BiblioItem>();
            boolean valid = consolidator.consolidate(resHeader, bibis);
            if ((valid) && (bibis.size() > 0)) {
                BiblioItem bibo = bibis.get(0);
                if (bibo != null) {
                    if (consolidate == 1)
                        BiblioItem.correct(resHeader, bibo);
                    else if (consolidate == 2)
                        BiblioItem.injectDOI(resHeader, bibo);
                }
            }*/
            BiblioItem bib = consolidator.consolidate(resHeader, null);
            if (bib != null) {
                if (consolidate == 1)
                    BiblioItem.correct(resHeader, bib);
                else if (consolidate == 2)
                    BiblioItem.injectDOI(resHeader, bib);
            }
        } catch (Exception e) {
            throw new GrobidException("An exception occured while running bibliographical data consolidation.", e);
        }
        return resHeader;
    }

    @Override
    public void close() throws IOException {
        super.close();
    }
}
