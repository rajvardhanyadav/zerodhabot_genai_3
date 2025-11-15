package com.tradingbot.util;

public final class CurrentUserContext {
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    private CurrentUserContext() {}

    public static void setUserId(String userId) {
        USER_ID.set(userId);
    }

    public static String getUserId() {
        return USER_ID.get();
    }

    public static String getRequiredUserId() {
        String id = USER_ID.get();
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("User context is missing. Provide X-User-Id header.");
        }
        return id;
    }

    public static void clear() {
        USER_ID.remove();
    }
}

