package com.zzl.web.service.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zzl.common.config.ZzlConfig;
import com.zzl.common.utils.uuid.IdUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import com.zzl.web.mapper.ai.AiAttachmentMapper;
import com.zzl.web.mapper.ai.AiAttachmentRecord;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 全功能 AI 工作区文件服务。
 *
 * <p>文件存放在 zzl.profile/ai-files/{用户}/ 下，元数据保存在 Redis；Redis
 * 不可用时使用当前进程内存索引。该服务只负责上传、预览和抽取文本，模型调用
 * 由 AiChatService 负责。</p>
 */
@Service
public class AiFileService
{
    private static final String INDEX_PREFIX = "zzl:ai:file-index:";
    private static final Duration INDEX_TTL = Duration.ofDays(30);
    private static final int MAX_EXTRACT_CHARS = 120_000;
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "java", "js", "ts",
            "jsx", "tsx", "vue", "css", "scss", "less", "sql", "yaml", "yml", "properties", "log", "py",
            "go", "rs", "sh", "bat", "ps1", "conf", "ini", "env");
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of("docx", "xlsx", "xls", "pdf", "pptx");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");

    private final StringRedisTemplate redis;
    private final AiAttachmentMapper attachmentMapper;
    private final AiOcrService ocr;
    private final Map<String, List<FileInfo>> memory = new ConcurrentHashMap<>();

    public AiFileService(StringRedisTemplate redis)
    {
        this(redis, null, new AiOcrService(false, "tesseract", "chi_sim+eng"));
    }

    @Autowired
    public AiFileService(StringRedisTemplate redis, AiAttachmentMapper attachmentMapper, AiOcrService ocr)
    {
        this.redis = redis;
        this.attachmentMapper = attachmentMapper;
        this.ocr = ocr;
    }

    /** 上传文件并抽取可读文本。 */
    public FileInfo upload(String scope, String conversationId, MultipartFile file) throws IOException
    {
        if (file == null || file.isEmpty())
        {
            throw new IOException("上传文件不能为空");
        }
        String originalName = file.getOriginalFilename();
        String extension = extension(originalName);
        if (!TEXT_EXTENSIONS.contains(extension) && !DOCUMENT_EXTENSIONS.contains(extension)
                && !IMAGE_EXTENSIONS.contains(extension))
        {
            throw new IOException("暂不支持该文件类型，支持图片、文本、Markdown、CSV、JSON、代码、PDF、DOCX、PPTX 和 Excel");
        }

        String safeScope = safeSegment(scope, "anonymous");
        String id = IdUtils.fastSimpleUUID();
        String storedName = id + (extension.isBlank() ? "" : "." + extension);
        Path directory = Paths.get(ZzlConfig.getProfile(), "ai-files", safeScope).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path target = directory.resolve(storedName).normalize();
        if (!target.startsWith(directory.toAbsolutePath().normalize()))
        {
            throw new IOException("文件路径非法");
        }
        file.transferTo(target);

        String content = extract(target, extension);
        FileInfo info = new FileInfo(id, cleanName(originalName), extension, file.getContentType(), file.getSize(),
                target.toString(), conversationId == null ? "" : conversationId, System.currentTimeMillis(),
                content.length(), content.length() > 0 && !content.startsWith("[暂不支持"),
                content.startsWith("[暂不支持") ? content : "");
        List<FileInfo> files = listInternal(safeScope);
        files.removeIf(item -> item.id().equals(id));
        files.add(info);
        files.sort(Comparator.comparing(FileInfo::uploadedAt).reversed());
        // 长期知识库文件不因会话附件数量增长而自动删除。
        if (attachmentMapper != null)
            attachmentMapper.upsert(info.id(), safeScope, info.conversationId(), info.originalName(), info.extension(),
                    info.contentType(), info.size(), info.path(), info.extractedChars(), info.textExtracted(), info.extractMessage());
        persist(safeScope, files);
        return publicInfo(info);
    }

    public List<FileInfo> list(String scope, String conversationId)
    {
        String safeScope = safeSegment(scope, "anonymous");
        List<FileInfo> files = listInternal(safeScope);
        if (conversationId == null || conversationId.isBlank())
        {
            return files.stream().map(this::publicInfo).toList();
        }
        return files.stream().filter(item -> conversationId.equals(item.conversationId()))
                .map(this::publicInfo).toList();
    }

    public FileInfo get(String scope, String fileId)
    {
        if (fileId == null || fileId.isBlank())
        {
            return null;
        }
        FileInfo found = findStored(scope, fileId);
        return found == null ? null : publicInfo(found);
    }

    public ExtractedFile content(String scope, String fileId) throws IOException
    {
        FileInfo info = findStored(scope, fileId);
        if (info == null)
        {
            throw new IOException("文件不存在或已过期");
        }
        Path path = Paths.get(info.path()).normalize();
        if (!Files.exists(path))
        {
            throw new IOException("文件内容不存在，请重新上传");
        }
        String text = extract(path, info.extension());
        FileInfo current = new FileInfo(info.id(), info.originalName(), info.extension(), info.contentType(),
                info.size(), info.path(), info.conversationId(), info.uploadedAt(), text.length(),
                !text.isBlank() && !text.startsWith("[暂不支持"), text.startsWith("[暂不支持") ? text : "");
        if (attachmentMapper != null)
            attachmentMapper.upsert(current.id(), safeSegment(scope, "anonymous"), current.conversationId(),
                    current.originalName(), current.extension(), current.contentType(), current.size(), current.path(),
                    current.extractedChars(), current.textExtracted(), current.extractMessage());
        return new ExtractedFile(publicInfo(current), text);
    }

    /** 返回图片的本地路径和媒体类型，供视觉模型构造多模态消息。 */
    public ImageAttachment image(String scope, String fileId) throws IOException
    {
        FileInfo info = findStored(scope, fileId);
        if (info == null)
        {
            throw new IOException("图片不存在或已过期");
        }
        if (!IMAGE_EXTENSIONS.contains(info.extension()))
        {
            throw new IOException("该附件不是图片");
        }
        Path path = Paths.get(info.path()).normalize();
        if (!Files.exists(path))
        {
            throw new IOException("图片内容不存在，请重新上传");
        }
        String contentType = info.contentType();
        if (contentType == null || contentType.isBlank() || !contentType.toLowerCase(Locale.ROOT).startsWith("image/"))
        {
            contentType = "image/" + ("jpg".equals(info.extension()) ? "jpeg" : info.extension());
        }
        return new ImageAttachment(info.id(), info.originalName(), path, contentType);
    }

    public void delete(String scope, String fileId)
    {
        String safeScope = safeSegment(scope, "anonymous");
        List<FileInfo> files = listInternal(safeScope);
        FileInfo target = files.stream().filter(item -> fileId != null && fileId.equals(item.id())).findFirst().orElse(null);
        if (target == null)
        {
            return;
        }
        files.remove(target);
        deletePhysical(target);
        if (attachmentMapper != null) attachmentMapper.delete(safeScope, fileId);
        persist(safeScope, files);
    }

    private FileInfo findStored(String scope, String fileId)
    {
        String safeScope = safeSegment(scope, "anonymous");
        if (attachmentMapper != null)
        {
            try
            {
                AiAttachmentRecord row = attachmentMapper.selectOne(safeScope, fileId);
                return row == null ? null : storedInfo(safeScope, row);
            }
            catch (Exception ignored) { /* 数据库暂不可用时读缓存。 */ }
        }
        FileInfo cached = listInternal(safeScope).stream().filter(item -> fileId != null && fileId.equals(item.id())).findFirst().orElse(null);
        return cached;
    }

    private List<FileInfo> listInternal(String scope)
    {
        if (attachmentMapper != null)
        {
            try
            {
                List<FileInfo> stored = attachmentMapper.selectByScope(scope, "").stream()
                        .map(row -> storedInfo(scope, row)).toList();
                memory.put(scope, new ArrayList<>(stored));
                return new ArrayList<>(stored);
            }
            catch (Exception ignored) { /* 数据库暂不可用时继续读缓存。 */ }
        }
        String key = INDEX_PREFIX + scope;
        try
        {
            String json = redis.opsForValue().get(key);
            if (json != null && !json.isBlank())
            {
                List<FileInfo> result = parse(JSON.parseArray(json));
                memory.put(scope, new ArrayList<>(result));
                return result;
            }
        }
        catch (Exception ignored)
        {
            // Redis 不可用时走内存索引。
        }
        return new ArrayList<>(memory.getOrDefault(scope, List.of()));
    }

    private void persist(String scope, List<FileInfo> files)
    {
        List<FileInfo> copy = new ArrayList<>(files);
        memory.put(scope, copy);
        try
        {
            redis.opsForValue().set(INDEX_PREFIX + scope, JSON.toJSONString(copy), INDEX_TTL);
        }
        catch (Exception ignored)
        {
            // 内存索引已经更新。
        }
    }

    private FileInfo storedInfo(String scope, AiAttachmentRecord row)
    {
        // V6.5 第一阶段的附件记录可能没有保存路径，按已有文件命名规则恢复。
        String path = row.getPath();
        if (path == null || path.isBlank())
            path = Paths.get(ZzlConfig.getProfile(), "ai-files", scope,
                    row.getFileId() + "." + row.getExtension()).toAbsolutePath().normalize().toString();
        return new FileInfo(row.getFileId(), row.getOriginalName(), row.getExtension(), row.getContentType(),
                row.getSize() == null ? 0 : row.getSize(), path, row.getConversationId(),
                row.getUploadedAt() == null ? 0 : row.getUploadedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                row.getExtractedChars() == null ? 0 : row.getExtractedChars(),
                Boolean.TRUE.equals(row.getTextExtracted()), row.getExtractMessage());
    }

    private List<FileInfo> parse(JSONArray array)
    {
        List<FileInfo> files = new ArrayList<>();
        for (Object item : array)
        {
            if (item instanceof JSONObject object)
            {
                try
                {
                    files.add(new FileInfo(object.getString("id"), object.getString("originalName"),
                            object.getString("extension"), object.getString("contentType"),
                            object.getLongValue("size"), object.getString("path"), object.getString("conversationId"),
                            object.getLongValue("uploadedAt"), object.getIntValue("extractedChars"),
                            object.getBooleanValue("textExtracted"), object.getString("extractMessage")));
                }
                catch (Exception ignored)
                {
                    // 忽略损坏的单条元数据，不影响其它文件。
                }
            }
        }
        files.sort(Comparator.comparing(FileInfo::uploadedAt).reversed());
        return files;
    }

    private String extract(Path path, String extension) throws IOException
    {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        if (TEXT_EXTENSIONS.contains(ext))
        {
            byte[] bytes = Files.readAllBytes(path);
            return limit(new String(bytes, StandardCharsets.UTF_8));
        }
        if ("docx".equals(ext))
        {
            return limit(extractDocx(path));
        }
        if ("xlsx".equals(ext) || "xls".equals(ext))
        {
            return limit(extractWorkbook(path));
        }
        if ("pdf".equals(ext))
        {
            return limit(extractPdf(path));
        }
        if ("pptx".equals(ext)) return limit(extractSlides(path));
        if (IMAGE_EXTENSIONS.contains(ext) && ocr.enabled())
        {
            try { return limit(ocr.extract(path)); }
            catch (IOException error) { return "[暂不支持：图片 OCR 未成功，" + error.getMessage() + "]"; }
        }
        return "[暂不支持抽取该文件的文本内容，可先下载后再分析]";
    }

    private String extractPdf(Path path) throws IOException
    {
        try (PDDocument document = Loader.loadPDF(path.toFile()))
        {
            StringBuilder text = new StringBuilder();
            PDFTextStripper stripper = new PDFTextStripper();
            org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(document);
            for (int page = 0; page < document.getNumberOfPages() && text.length() < MAX_EXTRACT_CHARS; page++)
            {
                stripper.setStartPage(page + 1);
                stripper.setEndPage(page + 1);
                String part = stripper.getText(document);
                if (part.isBlank() && ocr.enabled())
                {
                    if (page >= 30) throw new IOException("扫描 PDF 单次最多识别 30 页，请拆分文件");
                    Path image = Files.createTempFile("ai-pdf-ocr-", ".png");
                    try
                    {
                        javax.imageio.ImageIO.write(renderer.renderImageWithDPI(page, 150), "png", image.toFile());
                        part = ocr.extract(image);
                    }
                    finally { Files.deleteIfExists(image); }
                }
                if (!part.isBlank()) text.append("[第 ").append(page + 1).append(" 页]\n").append(part).append('\n');
            }
            return text.isEmpty() ? "[暂不支持：该 PDF 没有可提取文字，扫描文档需要启用 OCR]" : text.toString();
        }
    }

    private String extractSlides(Path path) throws IOException
    {
        StringBuilder result = new StringBuilder();
        try (InputStream input = Files.newInputStream(path);
                org.apache.poi.xslf.usermodel.XMLSlideShow slides = new org.apache.poi.xslf.usermodel.XMLSlideShow(input))
        {
            int page = 1;
            for (org.apache.poi.xslf.usermodel.XSLFSlide slide : slides.getSlides())
            {
                appendLine(result, "[幻灯片 " + page++ + "]");
                for (org.apache.poi.xslf.usermodel.XSLFShape shape : slide.getShapes())
                {
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape text) appendLine(result, text.getText());
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTable table)
                        for (org.apache.poi.xslf.usermodel.XSLFTableRow row : table.getRows())
                            appendLine(result, row.getCells().stream().map(org.apache.poi.xslf.usermodel.XSLFTableCell::getText)
                                    .collect(java.util.stream.Collectors.joining(" | ")));
                }
            }
        }
        return result.toString();
    }

    private String extractDocx(Path path) throws IOException
    {
        StringBuilder result = new StringBuilder();
        try (InputStream input = Files.newInputStream(path); XWPFDocument document = new XWPFDocument(input))
        {
            for (XWPFParagraph paragraph : document.getParagraphs())
            {
                appendLine(result, paragraph.getText());
            }
            for (XWPFTable table : document.getTables())
            {
                for (XWPFTableRow row : table.getRows())
                {
                    for (XWPFTableCell cell : row.getTableCells())
                    {
                        appendLine(result, cell.getText());
                    }
                }
            }
        }
        return result.toString();
    }

    private String extractWorkbook(Path path) throws IOException
    {
        StringBuilder result = new StringBuilder();
        DataFormatter formatter = new DataFormatter();
        try (InputStream input = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(input))
        {
            for (Sheet sheet : workbook)
            {
                appendLine(result, "[工作表] " + sheet.getSheetName());
                for (Row row : sheet)
                {
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row)
                    {
                        cells.add(formatter.formatCellValue(cell));
                    }
                    appendLine(result, String.join(" | ", cells));
                }
            }
        }
        return result.toString();
    }

    private void appendLine(StringBuilder result, String value)
    {
        if (value != null && !value.isBlank())
        {
            result.append(value.trim()).append('\n');
        }
    }

    private String limit(String content)
    {
        if (content == null)
        {
            return "";
        }
        return content.length() <= MAX_EXTRACT_CHARS
                ? content
                : content.substring(0, MAX_EXTRACT_CHARS) + "\n[文件内容过长，已截取前 " + MAX_EXTRACT_CHARS + " 个字符]";
    }

    private void deletePhysical(FileInfo info)
    {
        try
        {
            Files.deleteIfExists(Paths.get(info.path()));
        }
        catch (Exception ignored)
        {
            // 删除元数据不应阻断其它文件操作。
        }
    }

    private FileInfo publicInfo(FileInfo info)
    {
        return new FileInfo(info.id(), info.originalName(), info.extension(), info.contentType(), info.size(), "",
                info.conversationId(), info.uploadedAt(), info.extractedChars(), info.textExtracted(), info.extractMessage());
    }

    private String extension(String name)
    {
        if (name == null || name.lastIndexOf('.') < 0)
        {
            return "";
        }
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private String cleanName(String name)
    {
        if (name == null || name.isBlank())
        {
            return "未命名文件";
        }
        return Paths.get(name).getFileName().toString();
    }

    private String safeSegment(String value, String fallback)
    {
        String candidate = value == null ? "" : value.trim();
        if (!StringUtils.hasText(candidate))
        {
            return fallback;
        }
        return candidate.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    public record FileInfo(String id, String originalName, String extension, String contentType, long size,
            String path, String conversationId, long uploadedAt, int extractedChars, boolean textExtracted,
            String extractMessage)
    {
    }

    public record ExtractedFile(FileInfo file, String content)
    {
    }

    public record ImageAttachment(String fileId, String originalName, Path path, String contentType)
    {
    }
}
