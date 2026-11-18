package org.spring.claude.spring_claude_tools.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档解析工具：从 PDF / Word / Excel / 纯文本中提取文本，供模型阅读。
 *
 * <p>PDF 使用 Apache PDFBox，Office 文档使用 Apache POI。
 * 路径限制在当前项目工作目录内，提取结果截断到 20000 字符。
 */
@Component
public class DocumentTools {

    private static final int MAX_CHARS = 20_000;

    private final Path baseDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

    @Tool(description = "解析本地文档并提取纯文本内容，支持 pdf / docx / xlsx / csv / txt / md / json / xml。适合让 AI 阅读项目里的需求文档、报表、说明文件")
    public String extractDocumentText(
            @ToolParam(description = "文件路径（相对项目工作目录或绝对路径），例如 docs/需求.pdf") String path) {

        Path target = resolve(path);
        if (target == null) {
            return "解析失败：路径不在项目目录内或无效";
        }
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            return "解析失败：文件不存在 " + target;
        }
        String name = target.getFileName().toString().toLowerCase();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        try {
            String text = switch (ext) {
                case "pdf" -> readPdf(target);
                case "docx" -> readDocx(target);
                case "xlsx", "xls" -> readExcel(target);
                case "txt", "md", "json", "xml", "yml", "yaml", "properties", "csv", "log", "java" ->
                        Files.readString(target, StandardCharsets.UTF_8);
                default -> "不支持的文件类型 [" + ext + "]，支持 pdf/docx/xlsx/csv/txt/md/json/xml 等纯文本";
            };
            if (text.length() > MAX_CHARS) {
                text = text.substring(0, MAX_CHARS) + "\n...(已截断，原始长度 " + text.length() + " 字符)";
            }
            return "文件：" + baseDir.relativize(target) + "\n内容：\n" + text;
        } catch (Exception e) {
            return "解析失败：" + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }

    @Tool(description = "列出文档型文件（pdf/docx/xlsx/doc/csv）的位置与大小，适合在不知道具体文件名时先探查目录")
    public String listDocuments(
            @ToolParam(description = "起始目录（相对项目工作目录），留空为项目根目录") String startDir) {
        Path start = (startDir == null || startDir.isBlank()) ? baseDir : baseDir.resolve(startDir).normalize();
        if (!start.startsWith(baseDir) || !Files.isDirectory(start)) {
            return "列取失败：目录无效或不在项目目录内";
        }
        List<String> docs = new ArrayList<>();
        try (var stream = Files.walk(start)) {
            stream.filter(Files::isRegularFile)
                    .filter(f -> {
                        String n = f.getFileName().toString().toLowerCase();
                        return n.endsWith(".pdf") || n.endsWith(".docx") || n.endsWith(".doc")
                                || n.endsWith(".xlsx") || n.endsWith(".xls") || n.endsWith(".csv");
                    })
                    .limit(300)
                    .forEach(f -> {
                        try {
                            docs.add(baseDir.relativize(f) + "  (" + Files.size(f) / 1024 + " KB)");
                        } catch (IOException e) {
                            docs.add(baseDir.relativize(f).toString());
                        }
                    });
        } catch (IOException e) {
            return "列取失败：" + e.getMessage();
        }
        if (docs.isEmpty()) {
            return "目录下没有找到文档型文件";
        }
        return "共 " + docs.size() + " 个文档：\n- " + String.join("\n- ", docs);
    }

    private String readPdf(Path path) throws IOException {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(path.toFile())) {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setSortByPosition(true);
            return "（共 " + doc.getNumberOfPages() + " 页）\n" + stripper.getText(doc);
        }
    }

    private String readDocx(Path path) throws IOException {
        try (var is = Files.newInputStream(path);
             var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(is)) {
            StringBuilder sb = new StringBuilder();
            doc.getParagraphs().forEach(p -> sb.append(p.getText()).append('\n'));
            doc.getTables().forEach(table -> table.getRows().forEach(row -> {
                row.getTableCells().forEach(cell -> sb.append(cell.getText()).append(" | "));
                sb.append('\n');
            }));
            return sb.toString();
        }
    }

    private String readExcel(Path path) throws IOException {
        try (var is = Files.newInputStream(path);
             var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(is)) {
            StringBuilder sb = new StringBuilder();
            var formatter = new org.apache.poi.ss.usermodel.DataFormatter();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                var sheet = wb.getSheetAt(i);
                sb.append("== Sheet: ").append(sheet.getSheetName()).append(" ==\n");
                int rows = 0;
                for (var row : sheet) {
                    if (rows++ > 200) {
                        sb.append("...(该表超过 200 行，已截断)\n");
                        break;
                    }
                    List<String> cells = new ArrayList<>();
                    row.forEach(cell -> cells.add(formatter.formatCellValue(cell)));
                    sb.append(String.join(" | ", cells)).append('\n');
                }
            }
            return sb.toString();
        }
    }

    private Path resolve(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            Path p = Path.of(path);
            Path target = p.isAbsolute() ? p.normalize() : baseDir.resolve(p).normalize();
            return target.startsWith(baseDir) ? target : null;
        } catch (Exception e) {
            return null;
        }
    }
}
