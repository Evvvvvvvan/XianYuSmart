package com.xianyusmart.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 商机候选商品评分
 */
@Service
public class OpportunityAnalysisService {

    public List<Map<String, Object>> rank(String keyword, List<Map<String, Object>> candidates) {
        String normalizedKeyword = normalize(keyword);
        Set<String> tokens = tokenize(keyword);
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map<String, Object> candidate : candidates == null ? List.<Map<String, Object>>of() : candidates) {
            Map<String, Object> item = new HashMap<>(candidate);
            String title = normalize(item.get("title"));
            int score = calculateScore(normalizedKeyword, tokens, title, item);
            item.put("opportunityScore", score);
            item.put("riskLevel", score >= 65 ? "LOW" : score >= 40 ? "MEDIUM" : "HIGH");
            item.put("matchReason", matchReason(normalizedKeyword, tokens, title, item));
            ranked.add(item);
        }
        ranked.sort(Comparator
                .comparingInt((Map<String, Object> item) -> intValue(item.get("opportunityScore"))).reversed()
                .thenComparing(item -> String.valueOf(item.getOrDefault("itemId", ""))));
        return ranked;
    }

    private int calculateScore(String keyword, Set<String> tokens, String title, Map<String, Object> item) {
        int score = 0;
        if (!keyword.isBlank() && title.contains(keyword)) {
            score += 55;
        } else if (!tokens.isEmpty()) {
            long matched = tokens.stream().filter(title::contains).count();
            score += (int) Math.round(45D * matched / tokens.size());
        }
        if (item.get("images") instanceof List<?> images && !images.isEmpty()) {
            score += 15;
        }
        if (!text(item.get("sourceUrl")).isBlank()) {
            score += 10;
        }
        if (!text(item.get("itemId")).isBlank()) {
            score += 10;
        }
        if (hasPrice(item)) {
            score += 10;
        }
        return Math.min(score, 100);
    }

    private String matchReason(String keyword, Set<String> tokens, String title, Map<String, Object> item) {
        List<String> reasons = new ArrayList<>();
        if (!keyword.isBlank() && title.contains(keyword)) {
            reasons.add("标题高度匹配");
        } else {
            long matched = tokens.stream().filter(title::contains).count();
            reasons.add("命中关键词 " + matched + "/" + tokens.size());
        }
        if (item.get("images") instanceof List<?> images && !images.isEmpty()) {
            reasons.add("素材完整");
        }
        if (hasPrice(item)) {
            reasons.add("价格可用");
        }
        return String.join(" · ", reasons);
    }

    private Set<String> tokenize(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalize(value).split("[\\s,，/|+_-]+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private boolean hasPrice(Map<String, Object> item) {
        String value = text(item.get("price"));
        if (value.isBlank()) {
            value = text(item.get("amount"));
        }
        try {
            return !value.isBlank() && Double.parseDouble(value.replaceAll("[^0-9.]", "")) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private String normalize(Object value) {
        return text(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }
}
