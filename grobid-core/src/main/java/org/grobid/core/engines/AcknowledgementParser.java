package org.grobid.core.engines;

import org.grobid.core.GrobidModels;
import org.grobid.core.document.Document;
import org.grobid.core.document.DocumentPiece;
import org.grobid.core.engines.label.SegmentationLabels;
import org.grobid.core.layout.LayoutToken;
import org.grobid.core.layout.LayoutTokenization;
import org.grobid.core.utilities.Pair;

import java.util.List;
import java.util.SortedSet;

/**
 * @author Aaron Gray
 */
public class AcknowledgementParser extends AbstractParser {

    public AcknowledgementParser() {
        super(GrobidModels.ACKNOWLEDGEMENT);
    }

    public Pair<String, List<LayoutToken>> processing(Document doc) {
       	String resultAcknowledgement = null;
        List<LayoutToken> tokenizationsAcknowledgement = null;

        // acknowledgement is in the back
        SortedSet<DocumentPiece> documentAcknowledgementParts =
        	doc.getDocumentPart(SegmentationLabels.ACKNOWLEDGEMENT);

        Pair<String, LayoutTokenization> featSeg =
        	FullTextParser.getBodyTextFeatured(doc, documentAcknowledgementParts);

        if (featSeg != null) {
        	// if doc.featSeg is null, it usually means that no body segment is found in the
        	// document segmentation
        	String acknowledgementText = featSeg.getLeft();
        	tokenizationsAcknowledgement = featSeg.getRight().getTokenization();
        	if ( (acknowledgementText != null) && (acknowledgementText.length() >0) )
        		reseAcknowledgement = label(acknowledgementText); // HACK
        }
        return new Pair<String, List<LayoutToken>>(reseAcknowledgement, tokenizationsAcknowledgement);
    }
}
