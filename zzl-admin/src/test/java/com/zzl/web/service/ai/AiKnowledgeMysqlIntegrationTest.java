package com.zzl.web.service.ai;

import com.zzl.common.config.ZzlConfig;
import com.zzl.web.mapper.ai.AiAttachmentMapper;
import com.zzl.web.mapper.ai.AiDocumentChunkMapper;
import com.zzl.web.mapper.ai.AiRagIndexTaskMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 显式开启后，仅写入独立测试作用域；不调用实际聊天模型，也不启动 HTTP 服务。 */
@EnabledIfEnvironmentVariable(named = "ZZL_AI_MYSQL_SMOKE", matches = "true")
class AiKnowledgeMysqlIntegrationTest
{
    @TempDir Path directory;

    @Test void persistConfigureIndexSearchAndDeleteKnowledgeBases() throws Exception
    {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-druid.yml"));
        Properties props = yaml.getObject();
        DriverManagerDataSource source = new DriverManagerDataSource(
                props.getProperty("spring.datasource.druid.master.url"),
                props.getProperty("spring.datasource.druid.master.username"),
                props.getProperty("spring.datasource.druid.master.password"));
        JdbcTemplate jdbc = new JdbcTemplate(source);
        new AiConversationSchemaInitializer(jdbc).initialize();
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/ai/*.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        AiAttachmentMapper attachments = session.getMapper(AiAttachmentMapper.class);
        var chunks = session.getMapper(AiDocumentChunkMapper.class);
        var taskMapper = session.getMapper(AiRagIndexTaskMapper.class);
        new ZzlConfig().setProfile(directory.toString());
        AiOcrService ocr = new AiOcrService(false, "tesseract", "chi_sim+eng");
        AiFileService files = new AiFileService(null, attachments, ocr);
        AiKnowledgeBaseConfigService config = new AiKnowledgeBaseConfigService(jdbc);
        AiEmbeddingProvider local = new LocalHashEmbeddingProvider(true, 96, "local", "", "", "", 500);
        AiRagService rag = new AiRagService(chunks, files, local, new DisabledAiVectorStore(),
                config, new AiKnowledgeEmbeddingService(local), new DataSourceTransactionManager(source));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.initialize();
        var indexing = new AiRagIndexingService(rag, files, taskMapper, executor);
        var service = new AiKnowledgeBaseService(jdbc, files, mock(AiRunService.class), rag, indexing, config);
        String scope = "v65-test-" + UUID.randomUUID().toString().substring(0, 16);
        try
        {
            String first = String.valueOf(service.create(scope, "产品文档", "").get("id"));
            String second = String.valueOf(service.create(scope, "另一个知识库", "").get("id"));
            config.update(scope, first, 300, 40, 2, .1f, true, "global", "global", "", "");
            String text = "产品文档：java.net.ConnectException 需要检查模型服务连接。\n".repeat(35);
            var uploaded = service.upload(scope, first, new MockMultipartFile("file", "guide.txt",
                    "text/plain", text.getBytes(StandardCharsets.UTF_8)));
            String fileId = String.valueOf(uploaded.get("fileId"));
            waitFor(indexing, scope, fileId);
            assertEquals(AiRagIndexingService.Status.SUCCEEDED, indexing.status(scope, fileId).status());
            assertEquals(100, indexing.status(scope, fileId).progress());
            assertTrue(indexing.status(scope, fileId).chunks() > 1);
            assertEquals(300, config.forKnowledgeBase(scope, first).chunkSize());
            assertFalse(Boolean.TRUE.equals(service.documents(scope, first).get(0).get("needsReindex")));
            assertNotNull(new AiFileService(null, attachments, ocr).get(scope, fileId));
            assertFalse(jdbc.queryForObject("SELECT path FROM zzl_ai_attachment WHERE file_id=?", String.class, fileId).isBlank());
            var search = service.searchDetail(scope, first, "java.net.ConnectException", 6);
            assertFalse(((List<?>) search.get("items")).isEmpty());
            assertTrue(((List<?>) search.get("items")).size() <= 2);
            assertTrue(((List<?>) service.searchDetail(scope, second, "java.net.ConnectException", 6).get("items")).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> service.deleteDocument(scope, second, fileId));
            config.update(scope, first, 400, 40, 3, .1f, false, null, null, null, null);
            assertFalse(config.forKnowledgeBase(scope, first).rerankEnabled());
            assertTrue(Boolean.TRUE.equals(service.documents(scope, first).get(0).get("needsReindex")));
            service.reindexDocument(scope, first, fileId);
            waitFor(indexing, scope, fileId);
            assertFalse(Boolean.TRUE.equals(service.documents(scope, first).get(0).get("needsReindex")));
            service.update(scope, first, null, null, false);
            assertTrue(service.fileIds(scope, List.of(first)).isEmpty());
            service.delete(scope, first);
            assertTrue(service.documents(scope, second).isEmpty());
            assertNull(files.get(scope, fileId));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM zzl_ai_document_chunk WHERE scope=?", Integer.class, scope));
            service.delete(scope, second);
        }
        finally
        {
            executor.shutdown();
            // 只清理此次 UUID 作用域，保留其他用户数据。
            for (String table : List.of("zzl_ai_document_chunk", "zzl_ai_rag_index_task", "zzl_ai_knowledge_document",
                    "zzl_ai_knowledge_base", "zzl_ai_attachment"))
                jdbc.update("DELETE FROM " + table + " WHERE scope=?", scope);
        }
    }

    private void waitFor(AiRagIndexingService indexing, String scope, String fileId) throws InterruptedException
    {
        for (int i = 0; i < 150; i++)
        {
            var status = indexing.status(scope, fileId).status();
            if (status != AiRagIndexingService.Status.QUEUED && status != AiRagIndexingService.Status.RUNNING) return;
            Thread.sleep(100);
        }
        fail("索引任务未在 15 秒内完成");
    }
}
