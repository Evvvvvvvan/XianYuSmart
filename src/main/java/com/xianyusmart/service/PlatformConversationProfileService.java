package com.xianyusmart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.utils.XianyuApiCallUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话买家资料查询
 */
@Service
@RequiredArgsConstructor
public class PlatformConversationProfileService {

    private static final long CACHE_SECONDS = 1800;
    private static final int MAX_SESSIONS = 20;

    private final XianyuAccountMapper accountMapper;
    private final AccountService accountService;
    private final XianyuApiCallUtils apiCallUtils;
    private final ObjectMapper objectMapper;
    private final Map<String, CachedProfile> cache = new ConcurrentHashMap<>();

    public List<Map<String, Object>> query(Long accountId, List<String> sessionIds) {
        XianyuAccount account = accountId == null ? null : accountMapper.selectById(accountId);
        if (account == null) {
            throw new IllegalArgumentException("账号不存在或无权访问");
        }
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        String cookie = accountService.getCookieByAccountId(accountId);
        if (cookie == null || cookie.isBlank()) {
            throw new IllegalStateException("账号Cookie不可用");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        sessionIds.stream().filter(value -> value != null && !value.isBlank()).distinct().limit(MAX_SESSIONS)
                .forEach(sessionId -> result.add(queryOne(accountId, cookie, sessionId)));
        return result;
    }

    private Map<String, Object> queryOne(Long accountId, String cookie, String sessionId) {
        String cacheKey = accountId + ":" + sessionId;
        CachedProfile cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.profile();
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", 0);
        request.put("sessionType", 1);
        request.put("sessionId", sessionId);
        request.put("isOwner", false);
        XianyuApiCallUtils.ApiCallResult response = apiCallUtils.callApiWithRetry(
                accountId, "mtop.taobao.idlemessage.pc.user.query", "4.0",
                request, cookie, null, Map.of(
                        "spm_cnt", "a21ybx.im.0.0",
                        "spm_pre", "a21ybx.home.sidebar.2.0",
                        "log_id", accountId + "-" + sessionId + "-" + System.currentTimeMillis()
                ));
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("sid", sessionId);
        if (response.isSuccess()) {
            Map<String, Object> user = extractUser(response.getResponse());
            profile.put("avatar", https(firstValue(user, "logo", "avatar")));
            profile.put("nick", firstValue(user, "nick", "nickname"));
        } else {
            profile.put("avatar", "");
            profile.put("nick", "");
        }
        Map<String, Object> immutable = Map.copyOf(profile);
        if (!String.valueOf(immutable.get("avatar")).isBlank() || !String.valueOf(immutable.get("nick")).isBlank()) {
            if (cache.size() > 1000) {
                cache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
            }
            cache.put(cacheKey, new CachedProfile(immutable, Instant.now().plusSeconds(CACHE_SECONDS)));
        }
        return immutable;
    }

    private Map<String, Object> extractUser(String response) {
        try {
            Map<String, Object> root = objectMapper.readValue(response, new TypeReference<>() { });
            Map<String, Object> data = map(root.get("data"));
            Map<String, Object> module = map(data.get("module"));
            for (Object candidate : new Object[]{data.get("userInfo"), module.get("userInfo"), data, module}) {
                Map<String, Object> user = map(candidate);
                if (!firstValue(user, "logo", "avatar", "nick", "nickname").isBlank()) {
                    return user;
                }
            }
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, child) -> result.put(String.valueOf(key), child));
        return result;
    }

    private String firstValue(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private String https(String value) {
        if (value.startsWith("//")) {
            return "https:" + value;
        }
        return value.startsWith("http://") ? "https://" + value.substring(7) : value;
    }

    private record CachedProfile(Map<String, Object> profile, Instant expiresAt) {
    }
}
