package com.xianyusmart.config.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AIReasoningEffortTest {

    @Test
    void onlySupportedEffortValuesAreAccepted() {
        assertEquals("low", DynamicAIChatClientManager.normalizeReasoningEffort("low"));
        assertEquals("medium", DynamicAIChatClientManager.normalizeReasoningEffort(" MEDIUM "));
        assertEquals("high", DynamicAIChatClientManager.normalizeReasoningEffort("High"));
        // 未选择、非法值、历史脏数据一律视为“不指定”，避免把未知参数透传给平台。
        assertEquals("", DynamicAIChatClientManager.normalizeReasoningEffort(""));
        assertEquals("", DynamicAIChatClientManager.normalizeReasoningEffort(null));
        assertEquals("", DynamicAIChatClientManager.normalizeReasoningEffort("extreme"));
    }

    @Test
    void sceneModelInheritsGlobalModelWhenEmpty() {
        assertEquals("global-model", DynamicAIChatClientManager.resolveSceneModel("global-model", ""));
        assertEquals("global-model", DynamicAIChatClientManager.resolveSceneModel("global-model", null));
        assertEquals("fast-model", DynamicAIChatClientManager.resolveSceneModel("global-model", "fast-model"));
        assertEquals("fast-model", DynamicAIChatClientManager.resolveSceneModel("global-model", " fast-model "));
    }

    @Test
    void claudeThinkingBudgetFollowsSelectedEffort() {
        assertEquals(0, DynamicAIChatClientManager.thinkingBudgetTokens(""));
        assertEquals(0, DynamicAIChatClientManager.thinkingBudgetTokens("unknown"));
        assertEquals(1024, DynamicAIChatClientManager.thinkingBudgetTokens("low"));
        assertEquals(4096, DynamicAIChatClientManager.thinkingBudgetTokens("medium"));
        assertEquals(8192, DynamicAIChatClientManager.thinkingBudgetTokens("high"));
    }
}
