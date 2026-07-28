package com.xianyusmart.websocket;

import java.util.Map;

/**
 * WebSocket 消息同步游标
 */
public record WebSocketSyncCursor(long pts, long seq, long timestamp) {

    public static WebSocketSyncCursor initial() {
        long timestamp = System.currentTimeMillis();
        return new WebSocketSyncCursor(timestamp * 1000, 0, timestamp);
    }

    public static WebSocketSyncCursor fromSyncPackage(Map<String, Object> syncPackage,
                                                      WebSocketSyncCursor previous) {
        if (syncPackage == null) {
            return previous;
        }
        long pts = firstPositive(syncPackage.get("pts"), syncPackage.get("endPts"),
                syncPackage.get("highPts"));
        if (pts <= 0 || (previous != null && pts <= previous.pts())) {
            return previous;
        }
        long seq = longValue(syncPackage.get("seq"));
        long timestamp = longValue(syncPackage.get("timestamp"));
        return new WebSocketSyncCursor(pts, seq, timestamp > 0 ? timestamp : pts / 1000);
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static long firstPositive(Object... values) {
        for (Object value : values) {
            long parsed = longValue(value);
            if (parsed > 0) {
                return parsed;
            }
        }
        return 0;
    }
}
