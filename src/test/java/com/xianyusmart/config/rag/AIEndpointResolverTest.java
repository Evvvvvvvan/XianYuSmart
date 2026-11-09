package com.xianyusmart.config.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AIEndpointResolverTest {

    private static String openAiEndpoint(String baseUrl) {
        return AIEndpointResolver.resolve(baseUrl, AIEndpointResolver.Capability.OPENAI_CHAT).endpoint();
    }

    private static String anthropicEndpoint(String baseUrl) {
        return AIEndpointResolver.resolve(baseUrl, AIEndpointResolver.Capability.ANTHROPIC_CHAT).endpoint();
    }

    @Test
    void openAiCompatibleProvidersResolveToChatCompletions() {
        // 各厂商官方 Base URL 应拼出正确的 /chat/completions 地址，避免“配了但不通”。
        assertEquals("https://api.openai.com/v1/chat/completions", openAiEndpoint("https://api.openai.com"));
        assertEquals("https://api.deepseek.com/v1/chat/completions", openAiEndpoint("https://api.deepseek.com"));
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                openAiEndpoint("https://dashscope.aliyuncs.com/compatible-mode/v1"));
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions",
                openAiEndpoint("https://open.bigmodel.cn/api/paas/v4"));
        assertEquals("https://ark.cn-beijing.volces.com/api/v3/chat/completions",
                openAiEndpoint("https://ark.cn-beijing.volces.com/api/v3"));
        assertEquals("https://qianfan.baidubce.com/v2/chat/completions",
                openAiEndpoint("https://qianfan.baidubce.com/v2"));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                openAiEndpoint("https://generativelanguage.googleapis.com/v1beta/openai"));
        assertEquals("https://api.moonshot.cn/v1/chat/completions",
                openAiEndpoint("https://api.moonshot.cn/v1"));
        assertEquals("https://api.siliconflow.cn/v1/chat/completions",
                openAiEndpoint("https://api.siliconflow.cn/v1"));
        assertEquals("https://openrouter.ai/api/v1/chat/completions",
                openAiEndpoint("https://openrouter.ai/api/v1"));
        assertEquals("http://localhost:11434/v1/chat/completions",
                openAiEndpoint("http://localhost:11434/v1"));
        // 中转站常见的写法：根地址、带/ v1、或直接给完整 endpoint。
        assertEquals("https://proxy.example.com/v1/chat/completions",
                openAiEndpoint("https://proxy.example.com"));
        assertEquals("https://proxy.example.com/v1/chat/completions",
                openAiEndpoint("https://proxy.example.com/v1"));
        assertEquals("https://proxy.example.com/v1/chat/completions",
                openAiEndpoint("https://proxy.example.com/v1/chat/completions"));
    }

    @Test
    void anthropicCompatibleProvidersResolveToMessages() {
        assertEquals("https://api.anthropic.com/v1/messages", anthropicEndpoint("https://api.anthropic.com"));
        assertEquals("https://api.deepseek.com/anthropic/v1/messages",
                anthropicEndpoint("https://api.deepseek.com/anthropic"));
        assertEquals("https://dashscope.aliyuncs.com/apps/anthropic/v1/messages",
                anthropicEndpoint("https://dashscope.aliyuncs.com/apps/anthropic"));
        assertEquals("https://proxy.example.com/v1/messages",
                anthropicEndpoint("https://proxy.example.com"));
    }

    @Test
    void invalidBaseUrlsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> openAiEndpoint(""));
        assertThrows(IllegalArgumentException.class, () -> openAiEndpoint("ftp://api.openai.com"));
        assertThrows(IllegalArgumentException.class, () -> openAiEndpoint("https://api.openai.com?key=1"));
        assertThrows(IllegalArgumentException.class, () -> openAiEndpoint("https://user:pass@api.openai.com"));
    }
}
