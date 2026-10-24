package com.zzl.web.service.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zzl.web.mapper.ai.AiModelConfigMapper;
import com.zzl.web.mapper.ai.AiModelConfigRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 按用户持久化运行时 AI 模型配置；数据库不可用时保留当前进程内存降级。 */
@Service
public class AiModelStore
{
    private final AiModelConfigMapper mapper;
    private final Map<String, Map<String, AiProperties.ModelProperties>> memory = new LinkedHashMap<>();

    public AiModelStore(AiModelConfigMapper mapper)
    {
        this.mapper = mapper;
    }

    public synchronized Map<String, AiProperties.ModelProperties> list(String scope)
    {
        String safeScope = normalizeScope(scope);
        try
        {
            Map<String, AiProperties.ModelProperties> result = new LinkedHashMap<>();
            for (AiModelConfigRecord record : mapper.selectByScope(safeScope))
            {
                result.put(record.getModelId(), toProperties(record));
            }
            memory.put(safeScope, new LinkedHashMap<>(result));
            return result;
        }
        catch (Exception ignored)
        {
            return new LinkedHashMap<>(memory.getOrDefault(safeScope, Map.of()));
        }
    }

    public synchronized AiProperties.ModelProperties get(String scope, String id)
    {
        return list(scope).get(id);
    }

    public synchronized void save(String scope, String id, AiProperties.ModelProperties model)
    {
        String safeScope = normalizeScope(scope);
        try
        {
            mapper.upsert(fromProperties(safeScope, id, model));
        }
        catch (Exception ignored)
        {
            // 数据库暂不可用时，当前进程仍保留配置；下一次保存会再次写入数据库。
        }
        Map<String, AiProperties.ModelProperties> models = memory.computeIfAbsent(safeScope,
                key -> new LinkedHashMap<>());
        models.put(id, model);
    }

    public synchronized void delete(String scope, String id)
    {
        String safeScope = normalizeScope(scope);
        try
        {
            mapper.delete(safeScope, id);
        }
        catch (Exception ignored)
        {
            // 数据库不可用时继续清理当前进程内存。
        }
        Map<String, AiProperties.ModelProperties> models = memory.get(safeScope);
        if (models != null)
        {
            models.remove(id);
        }
    }

    private AiProperties.ModelProperties toProperties(AiModelConfigRecord record)
    {
        AiProperties.ModelProperties model = new AiProperties.ModelProperties();
        model.setLabel(record.getLabel());
        model.setBaseUrl(record.getBaseUrl());
        model.setApiKey(record.getApiKey());
        model.setModelName(record.getModelName());
        try
        {
            model.setModelCatalog(parseCatalog(record.getModelCatalog()));
        }
        catch (Exception ignored)
        {
            model.setModelCatalog(new ArrayList<>());
        }
        model.setEnabled(record.getEnabled() == null || record.getEnabled());
        model.setTemperature(record.getTemperature() == null ? 0.2D : record.getTemperature());
        model.setMaxTokens(record.getMaxTokens() == null ? 1200 : record.getMaxTokens());
        model.setProviderType(record.getProviderType());
        model.setCapabilityProtocol(record.getCapabilityProtocol());
        if (record.getCapabilities() != null && !record.getCapabilities().isBlank())
        {
            try
            {
                JSONObject capabilities = JSON.parseObject(record.getCapabilities());
                JSONArray levels = capabilities.getJSONArray("reasoningLevels");
                if (levels != null)
                {
                    ArrayList<String> values = new ArrayList<>();
                    for (Object value : levels)
                    {
                        if (value != null && !String.valueOf(value).isBlank())
                        {
                            values.add(String.valueOf(value));
                        }
                    }
                    model.setReasoningLevels(values);
                }
                model.setSupportsVision(capabilities.getBoolean("supportsVision"));
                model.setSupportsTools(capabilities.getBoolean("supportsTools"));
            }
            catch (Exception ignored)
            {
                // 能力 JSON 损坏时使用适配器默认能力。
            }
        }
        model.setReasoningEffort(record.getReasoningEffort());
        return model;
    }

    /** 兼容旧版只保存模型 ID 的数组，同时保留新版的展示名称。 */
    private ArrayList<AiProperties.ModelOption> parseCatalog(String raw)
    {
        ArrayList<AiProperties.ModelOption> result = new ArrayList<>();
        if (raw == null || raw.isBlank())
        {
            return result;
        }
        try
        {
            JSONArray values = JSON.parseArray(raw);
            for (Object value : values)
            {
                String id;
                String label;
                if (value instanceof JSONObject object)
                {
                    id = object.getString("id");
                    label = object.getString("label");
                }
                else
                {
                    id = value == null ? null : String.valueOf(value);
                    label = id;
                }
                if (id != null && !id.isBlank())
                {
                    result.add(new AiProperties.ModelOption(id, label));
                }
            }
        }
        catch (Exception ignored)
        {
            // 损坏的目录不应阻塞模型配置读取。
        }
        return result;
    }

    private AiModelConfigRecord fromProperties(String scope, String id, AiProperties.ModelProperties model)
    {
        AiModelConfigRecord record = new AiModelConfigRecord();
        record.setScope(scope);
        record.setModelId(id);
        record.setLabel(model.getLabel());
        record.setBaseUrl(model.getBaseUrl());
        record.setApiKey(model.getApiKey());
        record.setModelName(model.getModelName());
        record.setModelCatalog(JSON.toJSONString(model.getModelCatalog()));
        record.setEnabled(model.isEnabled());
        record.setTemperature(model.getTemperature());
        record.setMaxTokens(model.getMaxTokens());
        record.setProviderType(model.getProviderType());
        record.setCapabilityProtocol(model.getCapabilityProtocol());
        JSONObject capabilities = new JSONObject();
        capabilities.put("reasoningLevels", model.getReasoningLevels() == null ? List.of() : model.getReasoningLevels());
        if (model.getSupportsVision() != null)
        {
            capabilities.put("supportsVision", model.getSupportsVision());
        }
        if (model.getSupportsTools() != null)
        {
            capabilities.put("supportsTools", model.getSupportsTools());
        }
        record.setCapabilities(capabilities.toJSONString());
        record.setReasoningEffort(model.getReasoningEffort());
        return record;
    }

    private String normalizeScope(String scope)
    {
        if (scope == null || scope.isBlank())
        {
            return "anonymous";
        }
        return scope.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
