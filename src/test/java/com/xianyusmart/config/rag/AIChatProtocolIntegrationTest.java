package com.xianyusmart.config.rag;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用本地假服务校验“保存的配置真的会按对应协议发出去”，
 * 避免出现界面上支持某厂商、实际请求结构却不兼容的情况。
 */
class AIChatProtocolIntegrationTest {

    private HttpServer server;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String path = exchange.getRequestURI().getPath();
            String response = path.endsWith("/messages") ? ANTHROPIC_RESPONSE : OPENAI_RESPONSE;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static final String OPENAI_RESPONSE = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"mock-model",
             "choices":[{"index":0,"message":{"role":"assistant","content":"pong"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
            """;

    private static final String ANTHROPIC_RESPONSE = """
            {"id":"msg_1","type":"message","role":"assistant","model":"mock-model",
             "content":[{"type":"text","text":"pong"}],"stop_reason":"end_turn",
             "usage":{"input_tokens":1,"output_tokens":1}}
            """;

    private DynamicAIChatClientManager.ChatConfig config(String protocol, String model, String effort) {
        return new DynamicAIChatClientManager.ChatConfig(
                "custom", protocol, "本地假服务", "test-key",
                "http://127.0.0.1:" + server.getAddress().getPort(), model, effort);
    }

    @Test
    void openAiProtocolSendsBearerAuthModelAndReasoningEffort() {
        DynamicAIChatClientManager manager = new DynamicAIChatClientManager();
        DynamicAIChatClientManager.ConnectionTestResult result =
                manager.testConnection(config("openai", "mock-model", "high"), "ping");

        assertTrue(result.success(), result.message());
        assertEquals("pong", result.reply());
        assertEquals("/v1/chat/completions", lastPath.get());
        assertEquals("Bearer test-key", lastAuth.get());
        assertNotNull(lastBody.get());
        assertTrue(lastBody.get().contains("\"model\":\"mock-model\""), lastBody.get());
        assertTrue(lastBody.get().contains("\"reasoning_effort\":\"high\""), lastBody.get());
    }

    @Test
    void openAiProtocolOmitsReasoningEffortWhenNotSelected() {
        DynamicAIChatClientManager manager = new DynamicAIChatClientManager();
        DynamicAIChatClientManager.ConnectionTestResult result =
                manager.testConnection(config("openai", "mock-model", ""), "ping");

        assertTrue(result.success(), result.message());
        assertFalse(lastBody.get().contains("reasoning_effort"), lastBody.get());
    }

    @Test
    void anthropicProtocolSendsMessagesPathAndThinkingBudget() {
        DynamicAIChatClientManager manager = new DynamicAIChatClientManager();
        DynamicAIChatClientManager.ConnectionTestResult result =
                manager.testConnection(config("anthropic", "mock-model", "medium"), "ping");

        assertTrue(result.success(), result.message());
        assertEquals("pong", result.reply());
        assertEquals("/v1/messages", lastPath.get());
        assertNotNull(lastBody.get());
        assertTrue(lastBody.get().contains("\"thinking\""), lastBody.get());
        assertTrue(lastBody.get().contains("\"budget_tokens\":4096"), lastBody.get());
        // 扩展思考要求 max_tokens 大于预算。
        assertTrue(lastBody.get().contains("\"max_tokens\":6144"), lastBody.get());
    }

    @Test
    void anthropicProtocolWithoutEffortHasNoThinkingBlock() {
        DynamicAIChatClientManager manager = new DynamicAIChatClientManager();
        manager.testConnection(config("anthropic", "mock-model", ""), "ping");

        assertNotNull(lastBody.get());
        assertFalse(lastBody.get().contains("thinking"), lastBody.get());
        assertFalse(lastBody.get().contains("budget_tokens"), lastBody.get());
    }
}
