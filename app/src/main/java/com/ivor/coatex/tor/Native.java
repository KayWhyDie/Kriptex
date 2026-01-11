/*
 * Chat.onion - P2P Instant Messenger
 *
 * http://play.google.com/store/apps/details?id=onion.chat
 * http://onionapps.github.io/Chat.onion/
 * http://github.com/onionApps/Chat.onion
 *
 * Author: http://github.com/onionApps - http://jkrnk73uid7p5thz.onion - bitcoin:1kGXfWx8PHZEVriCNkbP5hzD15HS4AyKf
 */

package com.ivor.kriptex.tor;

public class Native {

    private static final boolean AVAILABLE;

    static {
        boolean ok;
        try {
            System.loadLibrary("app");
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        AVAILABLE = ok;
    }

    native public static void killTor();

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static void killTorSafe() {
        if (!AVAILABLE) return;
        try {
            killTor();
        } catch (Throwable ignored) {
        }
    }

}
