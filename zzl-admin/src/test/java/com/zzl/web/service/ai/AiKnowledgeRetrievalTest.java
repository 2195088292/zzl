package com.zzl.web.service.ai;

import com.zzl.web.mapper.ai.AiDocumentChunkMapper;
import com.zzl.web.mapper.ai.AiDocumentChunkRecord;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiKnowledgeRetrievalTest
{
    private final AiDocumentChunkMapper mapper = mock(AiDocumentChunkMapper.class);
    private final AiEmbeddingProvider embedding = new LocalHashEmbeddingProvider(true, 96, "local", "", "", "", 500);

    private AiRagService service() { return new AiRagService(mapper, mock(AiFileService.class), embedding); }

    private AiDocumentChunkRecord chunk(String id, String fileId, String text)
    {
        AiDocumentChunkRecord value = new AiDocumentChunkRecord();
        value.setChunkId(id); value.setFileId(fileId); value.setConversationId("kb:a");
        value.setContent(text); value.setKeywords(text); value.setSourceName(fileId + ".txt");
        value.setChunkIndex(0); value.setStartOffset(0); value.setEndOffset(text.length());
        value.setEmbedding("[]"); value.setEmbeddingModel("disabled");
        return value;
    }

    @Test void exactErrorCodeWinsAndFulltextFailureHasLexicalFallback()
    {
        when(mapper.selectByScope(anyString(), anyString(), anyList(), anyInt())).thenReturn(List.of(
                chunk("general", "f1", "服务器连接失败，可以检查网络"),
                chunk("exact", "f2", "java.net.ConnectException 表示 TCP 连接建立失败")));
        when(mapper.selectKeywordByScope(anyString(), anyString(), anyList(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("FULLTEXT unavailable"));
        var result = service().retrieveDetailed("u1", "", List.of("f1", "f2"), "java.net.ConnectException",
                new AiKnowledgeBaseConfigService.Settings(1200, 180, 6, .1f, true, "global"));
        assertEquals("exact", result.hits().get(0).getChunkId());
        assertFalse(result.trace().fulltextAvailable());
        assertEquals(1, result.trace().keywordCandidates());
    }

    @Test void overlappingChineseBigramsMatchAcrossFormerTokenBoundaries()
    {
        when(mapper.selectByScope(anyString(), anyString(), anyList(), anyInt()))
                .thenReturn(List.of(chunk("a", "f", "设置模型列表")));
        var result = service().retrieve("u1", "", List.of("f"), "模型", 3);
        assertEquals(1, result.size());
    }

    @Test void noLexicalOrVectorMatchDoesNotReturnRandomDocuments()
    {
        when(mapper.selectByScope(anyString(), anyString(), anyList(), anyInt()))
                .thenReturn(List.of(chunk("a", "f", "员工报销规则")));
        assertTrue(service().retrieve("u1", "", List.of("f"), "unrelated_model_123", 6).isEmpty());
    }

    @Test void duplicateContentIsCollapsedAndTopKIsApplied()
    {
        when(mapper.selectByScope(anyString(), anyString(), anyList(), anyInt())).thenReturn(List.of(
                chunk("a", "f1", "报销流程"), chunk("b", "f2", "报销流程"), chunk("c", "f3", "报销额度")));
        assertEquals(2, service().retrieve("u1", "", List.of("f1", "f2", "f3"), "报销", 6).size());
        assertEquals(1, service().retrieve("u1", "", List.of("f1", "f2", "f3"), "报销", 1).size());
    }

    @Test void failedEmbeddingDoesNotDeletePreviouslyWorkingIndex()
    {
        AiEmbeddingProvider failing = mock(AiEmbeddingProvider.class);
        when(failing.model()).thenReturn("openai:test");
        when(failing.embed(anyString())).thenThrow(new IllegalStateException("offline"));
        var file = new AiFileService.FileInfo("f", "a.txt", "txt", "text/plain", 50, "",
                "kb:a", 0, 50, true, "");
        assertThrows(IllegalStateException.class,
                () -> new AiRagService(mapper, null, failing).indexFile("u1", file, "已有文档必须保留"));
        verify(mapper, never()).deleteByFile(anyString(), anyString());
    }

    @Test void blankQuestionDoesNotScanWholeKnowledgeBase()
    {
        assertTrue(service().retrieve("u1", "", List.of("f"), " ", 6).isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test void differentEmbeddingSpacesRankIndependentlyBeforeFusion()
    {
        var first = chunk("a", "f1", "第一份文档");
        var second = chunk("b", "f2", "第二份文档");
        second.setConversationId("kb:b");
        first.setEmbeddingModel("space-a"); first.setEmbedding("[0.5,0.866]");
        second.setEmbeddingModel("space-b"); second.setEmbedding("[0.99,0.1]");
        when(mapper.selectByScope(anyString(), anyString(), anyList(), anyInt())).thenReturn(List.of(first, second));
        var config = mock(AiKnowledgeBaseConfigService.class);
        var optionsA = new AiKnowledgeBaseConfigService.Settings(1200, 180, 6, .1f, false, "model-a");
        var optionsB = new AiKnowledgeBaseConfigService.Settings(1200, 180, 6, .1f, false, "model-b");
        when(config.forStorageConversation("u1", "kb:a")).thenReturn(optionsA);
        when(config.forStorageConversation("u1", "kb:b")).thenReturn(optionsB);
        var embeddings = mock(AiKnowledgeEmbeddingService.class);
        when(embeddings.model(optionsA)).thenReturn("space-a");
        when(embeddings.model(optionsB)).thenReturn("space-b");
        when(embeddings.embed(anyString(), any())).thenReturn(new float[]{1, 0});
        var rag = new AiRagService(mapper, null, embedding, new DisabledAiVectorStore(), config, embeddings, null);
        var result = rag.retrieveDetailed("u1", "", List.of("f1", "f2"), "semantic_question", optionsA);
        assertEquals(2, result.hits().size());
        assertTrue(result.hits().stream().allMatch(hit -> hit.vectorRank() == 1));
        assertEquals(result.hits().get(0).hybridScore(), result.hits().get(1).hybridScore());
    }
}
