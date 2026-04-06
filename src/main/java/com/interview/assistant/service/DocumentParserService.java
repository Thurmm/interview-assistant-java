package com.interview.assistant.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档解析服务
 *
 * 支持解析：
 * - PDF 文件（.pdf）
 * - Word 文件（.docx）
 * - 纯文本文件（.txt）
 */
@Slf4j
@Service
public class DocumentParserService {

    /**
     * 解析文档文件，提取纯文本
     *
     * @param bytes   文件字节数组
     * @param filename 文件名（用于判断格式）
     * @return 提取的纯文本
     * @throws IOException 解析失败
     */
    public String parse(byte[] bytes, String filename) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("文件内容为空");
        }

        String lowerName = filename.toLowerCase();

        if (lowerName.endsWith(".pdf")) {
            return parsePdf(bytes);
        } else if (lowerName.endsWith(".docx")) {
            return parseDocx(bytes);
        } else if (lowerName.endsWith(".txt")) {
            return parseTxt(bytes);
        } else {
            // 尝试根据文件头判断类型
            return parseByMagic(bytes, lowerName);
        }
    }

    /**
     * 解析 MultipartFile（方便 Controller 直接使用）
     */
    public String parse(MultipartFile file) throws IOException {
        return parse(file.getBytes(), file.getOriginalFilename());
    }

    // ============ PDF 解析 ============

    private String parsePdf(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);

            if (text == null || text.isBlank()) {
                throw new IOException("PDF 中未提取到文本（可能是扫描版图片 PDF）");
            }

            return cleanText(text);
        } catch (IOException e) {
            throw new IOException("PDF 解析失败: " + e.getMessage(), e);
        }
    }

    // ============ DOCX 解析 ============

    private String parseDocx(byte[] bytes) throws IOException {
        try (InputStream is = new ByteArrayInputStream(bytes);
             XWPFDocument document = new XWPFDocument(is)) {

            List<XWPFParagraph> paragraphs = document.getParagraphs();

            String text = paragraphs.stream()
                    .map(XWPFParagraph::getText)
                    .filter(t -> t != null && !t.isBlank())
                    .collect(Collectors.joining("\n"));

            if (text.isBlank()) {
                throw new IOException("DOCX 中未提取到文本");
            }

            return cleanText(text);
        } catch (IOException e) {
            throw new IOException("DOCX 解析失败: " + e.getMessage(), e);
        }
    }

    // ============ TXT 解析 ============

    private String parseTxt(byte[] bytes) throws IOException {
        String text = new String(bytes, "UTF-8");
        if (text.isBlank()) {
            throw new IOException("TXT 文件内容为空");
        }
        return cleanText(text);
    }

    // ============ 文件头判断（magic bytes）============

    /**
     * 通过文件头（magic bytes）判断文件类型并解析
     */
    private String parseByMagic(byte[] bytes, String filename) throws IOException {
        if (bytes.length < 4) {
            throw new IOException("文件太小，无法判断类型: " + filename);
        }

        // PDF: 25 50 44 46 (%PDF)
        if (bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44 && bytes[3] == 0x46) {
            return parsePdf(bytes);
        }

        // DOCX: 50 4B 03 04 (PK zip header - same as DOCX)
        if (bytes[0] == 0x50 && bytes[1] == 0x4B) {
            // Could be DOCX (which is a ZIP)
            try {
                return parseDocx(bytes);
            } catch (Exception e) {
                log.warn("DOCX 解析失败，尝试按纯文本解析: {}", e.getMessage());
            }
        }

        // Fallback: 尝试 UTF-8 文本
        try {
            String text = new String(bytes, "UTF-8");
            if (text.codePointCount(0, text.length()) > 50) {
                return cleanText(text);
            }
        } catch (Exception ignored) {
        }

        throw new IOException("不支持的文件格式: " + filename);
    }

    // ============ 文本清理工具 ============

    /**
     * 清理提取的原始文本，去除多余空白、特殊字符等
     */
    private String cleanText(String text) {
        if (text == null) return "";

        return text
                // Windows 换行符统一
                .replace("\r\n", "\n")
                // 合并多个连续空格
                .replaceAll("[ \t]+", " ")
                // 合并多个连续空行
                .replaceAll("\n{3,}", "\n\n")
                // 去除零宽字符等隐藏字符
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "")
                .trim();
    }
}
