package com.example.server.service;

import com.example.server.dto.DocumentContext;
import com.example.server.dto.DocumentSegment;
import com.example.server.dto.DocumentType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class DocumentParsingService {

    public DocumentContext parse(Long documentId, String source, String filename, Path file) throws IOException {
        DocumentType type = DocumentType.fromFilename(filename);
        List<DocumentSegment> segments = switch (type) {
            case PDF -> parsePdf(file);
            case TXT -> parseText(file);
            case MARKDOWN -> parseMarkdown(file);
            case DOCX -> parseDocx(file);
            case PPTX -> parsePptx(file);
        };
        if (segments.stream().noneMatch(segment -> !segment.text().isBlank())) {
            throw new IllegalArgumentException("文档中没有可提取的文本");
        }
        String title = segments.stream()
                .map(DocumentSegment::headingPath)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElseGet(() -> stripExtension(filename));
        return new DocumentContext(documentId, source, title, type, List.copyOf(segments));
    }

    private List<DocumentSegment> parsePdf(Path file) throws IOException {
        List<DocumentSegment> result = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = normalize(stripper.getText(pdf));
                if (text.length() < 40) text = ocrPage(pdf, page - 1, text);
                if (!text.isBlank()) {
                    result.add(segment("page-" + page, null, page, null, null, null, null, text));
                }
            }
        }
        return result;
    }

    private String ocrPage(PDDocument pdf, int pageIndex, String extracted) {
        Path image = null;
        Path output = null;
        try {
            image = Files.createTempFile("knowledge-loop-ocr-", ".png");
            output = Files.createTempFile("knowledge-loop-ocr-result-", "");
            ImageIO.write(new PDFRenderer(pdf).renderImageWithDPI(pageIndex, 180), "png", image.toFile());
            Process process = new ProcessBuilder("tesseract", image.toString(), output.toString(), "-l", "chi_sim+eng")
                    .redirectErrorStream(true).start();
            if (process.waitFor() != 0) return extracted;
            Path textFile = Path.of(output + ".txt");
            if (!Files.exists(textFile)) return extracted;
            String ocr = normalize(Files.readString(textFile, StandardCharsets.UTF_8));
            Files.deleteIfExists(textFile);
            return ocr.length() > extracted.length() ? ocr : extracted;
        } catch (Exception ignored) {
            return extracted;
        } finally {
            if (image != null) try { Files.deleteIfExists(image); } catch (IOException ignored) { }
            if (output != null) try { Files.deleteIfExists(output); } catch (IOException ignored) { }
        }
    }

    private List<DocumentSegment> parseDocx(Path file) throws IOException {
        List<DocumentSegment> result = new ArrayList<>();
        try (InputStream input = Files.newInputStream(file); XWPFDocument doc = new XWPFDocument(input)) {
            int index = 0;
            String heading = "";
            for (XWPFParagraph paragraph : doc.getParagraphs()) {
                String text = normalize(paragraph.getText());
                if (text.isBlank()) continue;
                String style = paragraph.getStyle();
                if (style != null && style.toLowerCase().contains("heading")) heading = text;
                result.add(segment("paragraph-" + index, heading, null, null, index, null, null, text));
                index++;
            }
        }
        return result;
    }

    private List<DocumentSegment> parsePptx(Path file) throws IOException {
        List<DocumentSegment> result = new ArrayList<>();
        try (InputStream input = Files.newInputStream(file); XMLSlideShow ppt = new XMLSlideShow(input)) {
            for (int i = 0; i < ppt.getSlides().size(); i++) {
                List<String> texts = new ArrayList<>();
                for (XSLFShape shape : ppt.getSlides().get(i).getShapes()) {
                    if (shape instanceof XSLFTextShape textShape && !textShape.getText().isBlank()) {
                        texts.add(textShape.getText().strip());
                    }
                }
                if (!texts.isEmpty()) result.add(segment("slide-" + (i + 1), texts.getFirst(), null,
                        i + 1, null, null, null, String.join("\n", texts)));
            }
        }
        return result;
    }

    private List<DocumentSegment> parseText(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<DocumentSegment> result = new ArrayList<>();
        int blockStart = 1;
        StringBuilder block = new StringBuilder();
        for (int i = 0; i <= lines.size(); i++) {
            String line = i < lines.size() ? lines.get(i) : "";
            if (line.isBlank() && !block.isEmpty()) {
                result.add(segment("lines-" + blockStart + "-" + i, null, null, null,
                        result.size(), blockStart, i, block.toString().strip()));
                block.setLength(0);
            } else if (!line.isBlank()) {
                if (block.isEmpty()) blockStart = i + 1;
                if (!block.isEmpty()) block.append('\n');
                block.append(line);
            }
        }
        return result;
    }

    private List<DocumentSegment> parseMarkdown(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> headings = new ArrayList<>();
        List<DocumentSegment> result = new ArrayList<>();
        String currentHeading = "";
        int start = 1;
        StringBuilder block = new StringBuilder();
        for (int i = 0; i <= lines.size(); i++) {
            String line = i < lines.size() ? lines.get(i) : "";
            if (i < lines.size() && line.matches("^#{1,6}\\s+.+$")) {
                flushMarkdownBlock(result, currentHeading, start, i, block);
                int level = line.indexOf(' ');
                while (headings.size() >= level) headings.remove(headings.size() - 1);
                headings.add(line.substring(level + 1).trim());
                currentHeading = String.join(" > ", headings);
                start = i + 2;
            } else if ((line.isBlank() || i == lines.size()) && !block.isEmpty()) {
                flushMarkdownBlock(result, currentHeading, start, i, block);
                start = i + 2;
            } else if (!line.isBlank()) {
                if (block.isEmpty()) start = i + 1;
                if (!block.isEmpty()) block.append('\n');
                block.append(line);
            }
        }
        return result;
    }

    private void flushMarkdownBlock(List<DocumentSegment> result, String heading, int start,
                                    int end, StringBuilder block) {
        if (block.isEmpty()) return;
        result.add(segment("lines-" + start + "-" + end, heading, null, null,
                result.size(), start, end, block.toString().strip()));
        block.setLength(0);
    }

    private DocumentSegment segment(String id, String heading, Integer page, Integer slide,
                                    Integer paragraph, Integer start, Integer end, String text) {
        return new DocumentSegment(id, heading, page, slide, paragraph, start, end,
                text, List.of(), List.of());
    }

    private String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
