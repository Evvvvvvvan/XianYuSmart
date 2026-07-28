package com.xianyusmart.service;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * 工作流定义校验与排序
 */
@Service
public class WorkflowDefinitionService {

    private static final Set<String> NODE_TYPES = Set.of(
            "TRIGGER", "SEARCH", "COLLECT", "FILTER", "MATERIAL", "PUBLISH"
    );

    public List<Map<String, Object>> validateAndSort(Map<String, Object> definition) {
        List<Map<String, Object>> nodes = maps(definition == null ? null : definition.get("nodes"));
        List<Map<String, Object>> edges = maps(definition == null ? null : definition.get("edges"));
        if (nodes.isEmpty() || nodes.size() > 24) {
            throw new IllegalArgumentException("工作流需要包含 1 至 24 个节点");
        }

        Map<String, Map<String, Object>> nodeById = new LinkedHashMap<>();
        int triggerCount = 0;
        String triggerId = null;
        for (Map<String, Object> node : nodes) {
            String id = text(node.get("id"));
            String type = text(node.get("type")).toUpperCase();
            if (id.isBlank() || !NODE_TYPES.contains(type) || nodeById.putIfAbsent(id, node) != null) {
                throw new IllegalArgumentException("工作流节点标识或类型无效");
            }
            if ("TRIGGER".equals(type)) {
                triggerCount++;
                triggerId = id;
            }
        }
        if (triggerCount != 1) {
            throw new IllegalArgumentException("工作流必须且只能包含一个触发器");
        }

        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        nodeById.keySet().forEach(id -> indegree.put(id, 0));
        Set<String> uniqueEdges = new HashSet<>();
        for (Map<String, Object> edge : edges) {
            String source = text(edge.get("source"));
            String target = text(edge.get("target"));
            if (!nodeById.containsKey(source) || !nodeById.containsKey(target) || source.equals(target)) {
                throw new IllegalArgumentException("工作流连线引用了无效节点");
            }
            if (uniqueEdges.add(source + "\u0000" + target)) {
                outgoing.computeIfAbsent(source, ignored -> new ArrayList<>()).add(target);
                indegree.computeIfPresent(target, (ignored, value) -> value + 1);
            }
        }
        // 工作流只保留一个入口，避免孤立节点绕过触发条件独立执行。
        if (indegree.getOrDefault(triggerId, 0) != 0) {
            throw new IllegalArgumentException("工作流触发器不能存在上游节点");
        }
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (!entry.getKey().equals(triggerId) && entry.getValue() == 0) {
                throw new IllegalArgumentException("工作流存在未连接到触发器的节点");
            }
        }

        Queue<String> ready = new ArrayDeque<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });
        List<Map<String, Object>> sorted = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            String id = ready.remove();
            sorted.add(nodeById.get(id));
            for (String target : outgoing.getOrDefault(id, List.of())) {
                int degree = indegree.computeIfPresent(target, (ignored, value) -> value - 1);
                if (degree == 0) {
                    ready.add(target);
                }
            }
        }
        if (sorted.size() != nodes.size()) {
            throw new IllegalArgumentException("工作流存在循环依赖");
        }
        return sorted;
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("工作流节点和连线必须使用对象格式");
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, entry) -> normalized.put(String.valueOf(key), entry));
            result.add(normalized);
        }
        return result;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
