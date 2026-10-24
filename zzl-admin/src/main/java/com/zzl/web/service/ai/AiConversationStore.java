package com.zzl.web.service.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zzl.web.mapper.ai.AiConversationMapper;
import com.zzl.web.mapper.ai.AiConversationRecord;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * AI 会话存储。
 *
 * <p>MySQL 是长期存档，Redis 保存热点会话和索引；数据库或 Redis 不可用时仍保留当前进程内存兜底，
 * 不影响本地开发和降级问答。</p>
 */
@Service
public class AiConversationStore
{
    private static final Duration TTL = Duration.ofDays(30);
    private static final String KEY_PREFIX = "zzl:ai:conversation:";
    private static final String INDEX_PREFIX = "zzl:ai:conversation-index:";

    private final StringRedisTemplate redis;
    private final AiConversationMapper mapper;
    private final Map<String, List<Turn>> memory = new ConcurrentHashMap<>();
    private final Map<String, List<Summary>> memoryIndex = new ConcurrentHashMap<>();

    public AiConversationStore(StringRedisTemplate redis, AiConversationMapper mapper)
    {
        this.redis = redis;
        this.mapper = mapper;
    }

    public List<Turn> load(String scope, String conversationId)
    {
        String key = conversationKey(scope, conversationId);
        try
        {
            String json = redis.opsForValue().get(key);
            if (json != null && !json.isBlank())
            {
                List<Turn> turns = parseTurns(JSON.parseArray(json));
                memory.put(key, new ArrayList<>(turns));
                return turns;
            }
        }
        catch (Exception ignored)
        {
            // Redis 不可用时继续读取 MySQL。
        }

        try
        {
            AiConversationRecord record = mapper.selectOne(scope, normalizeConversationId(conversationId));
            if (record != null && record.getTurns() != null && !record.getTurns().isBlank())
            {
                List<Turn> turns = parseTurns(JSON.parseArray(record.getTurns()));
                memory.put(key, new ArrayList<>(turns));
                cache(key, record.getTurns());
                return turns;
            }
        }
        catch (Exception ignored)
        {
            // 数据库未初始化或暂时不可用时使用进程内存副本。
        }
        return new ArrayList<>(memory.getOrDefault(key, List.of()));
    }

    public void save(String scope, String conversationId, List<Turn> turns)
    {
        String id = normalizeConversationId(conversationId);
        String key = conversationKey(scope, id);
        List<Turn> copy = new ArrayList<>(turns == null ? List.of() : turns);
        String json = JSON.toJSONString(copy);
        memory.put(key, copy);
        try
        {
            mapper.upsert(scope, id, null, null, json);
        }
        catch (Exception ignored)
        {
            // DDL 尚未执行或数据库暂时不可用，Redis/内存仍可继续工作。
        }
        cache(key, json);
    }

    public List<Summary> list(String scope)
    {
        String key = INDEX_PREFIX + scope;
        try
        {
            String json = redis.opsForValue().get(key);
            if (json != null && !json.isBlank())
            {
                List<Summary> summaries = sortSummaries(parseSummaries(JSON.parseArray(json)));
                memoryIndex.put(scope, new ArrayList<>(summaries));
                return summaries;
            }
        }
        catch (Exception ignored)
        {
            // Redis 不可用时继续读取 MySQL。
        }

        try
        {
            List<Summary> summaries = mapper.selectByScope(scope).stream()
                    .map(this::toSummary)
                    .toList();
            if (!summaries.isEmpty())
            {
                List<Summary> sorted = sortSummaries(new ArrayList<>(summaries));
                memoryIndex.put(scope, new ArrayList<>(sorted));
                cacheIndex(key, sorted);
                return sorted;
            }
        }
        catch (Exception ignored)
        {
            // 数据库未初始化或暂时不可用时返回进程内存索引。
        }
        return sortSummaries(new ArrayList<>(memoryIndex.getOrDefault(scope, List.of())));
    }

    /** 返回会话已经固定的模型；空字符串表示尚未产生模型消息。 */
    public String model(String scope, String conversationId)
    {
        if (conversationId == null || conversationId.isBlank())
        {
            return "";
        }
        return list(scope).stream()
                .filter(item -> conversationId.equals(item.id()))
                .map(Summary::model)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
    }

    public void touch(String scope, String conversationId, String title, String model)
    {
        String id = normalizeConversationId(conversationId);
        List<Summary> summaries = list(scope);
        String cleanTitle = title == null || title.isBlank() ? "新对话" : title.trim();
        String now = String.valueOf(System.currentTimeMillis());
        summaries.removeIf(item -> id.equals(item.id()));
        summaries.add(new Summary(id, cleanTitle.length() > 42 ? cleanTitle.substring(0, 42) : cleanTitle,
                model == null ? "" : model, now));
        if (summaries.size() > 30)
        {
            summaries = new ArrayList<>(sortSummaries(summaries).subList(0, 30));
        }
        memoryIndex.put(scope, new ArrayList<>(summaries));
        try
        {
            mapper.upsert(scope, id, cleanTitle.length() > 255 ? cleanTitle.substring(0, 255) : cleanTitle,
                    model == null ? "" : model, null);
        }
        catch (Exception ignored)
        {
            // 数据库不可用时保留 Redis/内存索引。
        }
        cacheIndex(INDEX_PREFIX + scope, summaries);
    }

    public void delete(String scope, String conversationId)
    {
        String id = normalizeConversationId(conversationId);
        String key = conversationKey(scope, id);
        memory.remove(key);
        try
        {
            mapper.delete(scope, id);
        }
        catch (Exception ignored)
        {
            // 数据库不可用时忽略，当前缓存副本仍然删除。
        }
        try
        {
            redis.delete(key);
        }
        catch (Exception ignored)
        {
            // Redis 不可用时忽略删除。
        }
        List<Summary> summaries = list(scope);
        summaries.removeIf(item -> id.equals(item.id()));
        memoryIndex.put(scope, new ArrayList<>(summaries));
        cacheIndex(INDEX_PREFIX + scope, summaries);
    }

    private void cache(String key, String value)
    {
        try
        {
            redis.opsForValue().set(key, value, TTL);
        }
        catch (Exception ignored)
        {
            // 内存副本已经保存。
        }
    }

    private void cacheIndex(String key, List<Summary> summaries)
    {
        try
        {
            redis.opsForValue().set(key, JSON.toJSONString(summaries), TTL);
        }
        catch (Exception ignored)
        {
            // 内存索引已经保存。
        }
    }

    private Summary toSummary(AiConversationRecord record)
    {
        return new Summary(record.getConversationId(), record.getTitle(), record.getModel(),
                record.getUpdatedAt() == null ? "" : record.getUpdatedAt().toString());
    }

    private String conversationKey(String scope, String conversationId)
    {
        return KEY_PREFIX + scope + ":" + normalizeConversationId(conversationId);
    }

    private String normalizeConversationId(String conversationId)
    {
        return conversationId == null || conversationId.isBlank() ? "default" : conversationId.trim();
    }

    private List<Turn> parseTurns(JSONArray array)
    {
        List<Turn> turns = new ArrayList<>();
        if (array == null)
        {
            return turns;
        }
        for (Object item : array)
        {
            if (item instanceof JSONObject json)
            {
                String role = json.getString("role");
                String content = json.getString("content");
                if (role != null && content != null)
                {
                    turns.add(new Turn(role, content, json.getString("model"), json.getString("reasoningEffort"),
                            json.getString("providerType"), json.getString("capabilityProtocol"),
                            json.getJSONArray("citations") == null ? List.of()
                                    : json.getJSONArray("citations").toList(JSONObject.class).stream()
                                            .map(value -> (java.util.Map<String, Object>) new java.util.LinkedHashMap<String, Object>(value)).toList()));
                }
            }
        }
        return turns;
    }

    private List<Summary> parseSummaries(JSONArray array)
    {
        List<Summary> summaries = new ArrayList<>();
        if (array == null)
        {
            return summaries;
        }
        for (Object item : array)
        {
            if (item instanceof JSONObject json)
            {
                String id = json.getString("id");
                if (id != null)
                {
                    summaries.add(new Summary(id, json.getString("title"), json.getString("model"), json.getString("updatedAt")));
                }
            }
        }
        return summaries;
    }

    private List<Summary> sortSummaries(List<Summary> summaries)
    {
        summaries.sort(Comparator.comparing(Summary::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return summaries;
    }

    public record Turn(String role, String content, String model, String reasoningEffort,
            String providerType, String capabilityProtocol, List<java.util.Map<String, Object>> citations)
    {
        public Turn
        {
            citations = citations == null ? List.of() : List.copyOf(citations);
        }

        public Turn(String role, String content, String model, String reasoningEffort,
                String providerType, String capabilityProtocol)
        {
            this(role, content, model, reasoningEffort, providerType, capabilityProtocol, List.of());
        }
        public Turn(String role, String content)
        {
            this(role, content, "", "auto", "", "");
        }

        public Turn(String role, String content, String model, String reasoningEffort)
        {
            this(role, content, model, reasoningEffort, "", "");
        }
    }

    public record Summary(String id, String title, String model, String updatedAt)
    {
    }
}
