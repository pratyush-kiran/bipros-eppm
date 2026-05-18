package com.bipros.ai.document;

import com.bipros.common.exception.BusinessRuleException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Streams a PDF off disk via PDFBox and walks pages one-at-a-time. PDFBox keeps
 * page-on-demand state instead of holding the entire document in heap, so
 * concurrent uploads scale with the working page rather than total document size.
 *
 * <p>Uses the PDFBox 2.x {@code PDDocument.load(File)} entry point — PDFBox is
 * pinned to 2.0.x repo-wide to keep openhtmltopdf 1.0.10 happy (see DBS Bug 3).
 */
@Component
@Slf4j
public class PdfTextExtractor implements DocumentTextExtractor {

    private static final String MIME = "application/pdf";

    @Override
    public boolean supports(String mimeType, String originalFileName) {
        if (MIME.equalsIgnoreCase(mimeType)) return true;
        return originalFileName != null && originalFileName.toLowerCase().endsWith(".pdf");
    }

    @Override
    public ExtractedText extract(Path file, String mimeType, String originalFileName) {
        try (PDDocument doc = PDDocument.load(file.toFile())) {

            if (doc.isEncrypted()) {
                throw new BusinessRuleException("DOCUMENT_ENCRYPTED",
                        "Encrypted PDFs are not supported. Please remove the password and re-upload.");
            }

            int pageCount = doc.getNumberOfPages();
            StringBuilder out = new StringBuilder();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(doc);
                out.append("--- Page ").append(page).append(" ---\n");
                out.append(pageText);
                if (!pageText.endsWith("\n")) out.append('\n');
                if (out.length() > MAX_CHARS) break;
            }
            return ExtractedText.cap(out.toString());
        } catch (BusinessRuleException e) {
            throw e;
        } catch (InvalidPasswordException e) {
            throw new BusinessRuleException("DOCUMENT_ENCRYPTED",
                    "Encrypted PDFs are not supported. Please remove the password and re-upload.");
        } catch (Exception e) {
            log.warn("PDF extraction failed for {}: {}", originalFileName, e.getMessage());
            throw new BusinessRuleException("DOCUMENT_PARSE_FAILED",
                    "Failed to read PDF: " + e.getMessage());
        }
    }
}
