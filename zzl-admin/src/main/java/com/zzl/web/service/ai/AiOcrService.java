package com.zzl.web.service.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 可选的本机 OCR；未安装引擎时不影响文本知识库和图片视觉问答。 */
@Service
public class AiOcrService
{
    private final boolean enabled;
    private final String executable;
    private final String languages;

    public AiOcrService(@Value("${zzl.ai.rag.ocr.enabled:false}") boolean enabled,
            @Value("${zzl.ai.rag.ocr.command:tesseract}") String executable,
            @Value("${zzl.ai.rag.ocr.languages:chi_sim+eng}") String languages)
    {
        this.enabled = enabled;
        this.executable = executable;
        this.languages = languages;
    }

    public boolean enabled() { return enabled; }

    public String extract(Path image) throws IOException
    {
        if (!enabled) return "";
        Path output = Files.createTempFile("ai-ocr-", ".output");
        Path errors = Files.createTempFile("ai-ocr-", ".error");
        Process process = null;
        try
        {
            process = new ProcessBuilder(executable, image.toAbsolutePath().toString(), "stdout",
                    "-l", languages, "--psm", "3")
                    .redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
            if (!process.waitFor(45, TimeUnit.SECONDS))
                throw new IOException("OCR 超时，请缩小图片或检查识别引擎");
            if (process.exitValue() != 0)
                throw new IOException("OCR 失败，请检查 Tesseract 和语言包：" + Files.readString(errors, StandardCharsets.UTF_8));
            return Files.readString(output, StandardCharsets.UTF_8).trim();
        }
        catch (InterruptedException error)
        {
            Thread.currentThread().interrupt();
            throw new IOException("OCR 已中断", error);
        }
        finally
        {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(output);
            Files.deleteIfExists(errors);
        }
    }
}
