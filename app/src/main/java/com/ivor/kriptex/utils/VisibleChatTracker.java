package com.ivor.kriptex.utils;

import java.util.concurrent.atomic.AtomicReference;

public final class VisibleChatTracker {

    private static final AtomicReference<String> visibleChatId = new AtomicReference<>(null);

    private VisibleChatTracker() {
    }

    private static String normalize(String chatId) {
        if (chatId == null) return null;
        String s = chatId.trim();
        return s.isEmpty() ? null : s;
    }

    public static void setVisibleChatId(String chatId) {
        visibleChatId.set(normalize(chatId));
    }

    public static void clearVisibleChatId(String chatId) {
        String current = visibleChatId.get();
        String normalized = normalize(chatId);
        if (current == null) return;
        if (normalized == null || current.equals(normalized)) {
            visibleChatId.compareAndSet(current, null);
        }
    }

    public static String getVisibleChatId() {
        return visibleChatId.get();
    }

    public static boolean isChatVisible(String chatId) {
        String current = visibleChatId.get();
        String normalized = normalize(chatId);
        return current != null && current.equals(normalized);
    }
}
