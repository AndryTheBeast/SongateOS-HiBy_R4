package com.andre.hibyr4.settings;

import java.io.FileOutputStream;

/**
 * Draws a custom LED colour immediately (same formula as hiby_settings.sh led_custom_pattern: gamma 2.2,
 * channel balance R 1.0 / G 0.45 / B 0.8, max level 0x60, HiBy's breathing timing). Only used while the
 * module reports that the custom colour is on (sys.hibyr4.st_led=custom) and has handed the LED file to
 * Settings (sys.hibyr4.st_ledw=1). Saving is still done by the module.
 */
final class LedLive {
    private static final String FILE = "/sys/bus/i2c/devices/i2c-2/2-0030/leds/sgm31324-leds/write_pattern";

    static boolean usable() { return "custom".equals(Hb.get("st_led", "")) && "1".equals(Hb.get("st_ledw", "")); }

    private static int level(int c, double k, int br) {
        if (c <= 0) return 0;
        int v = (int) (96 * Math.pow(c / 255.0, 2.2) * k * br / 100 + 0.5);
        return Math.max(1, v);
    }

    static String pattern(String hex, int br, boolean breath) {
        int r = Integer.parseInt(hex.substring(0, 2), 16), g = Integer.parseInt(hex.substring(2, 4), 16),
                b = Integer.parseInt(hex.substring(4, 6), 16);
        int R = level(r, 1.0, br), G = level(g, 0.45, br), B = level(b, 0.8, br);
        int m = breath ? 2 : 1, t1 = breath ? 30 : 0, t2 = breath ? 125 : 0, t5 = breath ? 187 : 0;
        int mr = R > 0 ? m : 0, mg = G > 0 ? m : 0, mb = B > 0 ? m : 0;
        int e = (mr + mg + mb) > 0 ? (0x40 | (mb << 4) | (mg << 2) | mr) : 0;
        return String.format("00%02X%02X%02X%02X%02X%02X%02X%02X06", t1, t2, 0, e, t5, R, G, B);
    }

    static void draw(String hex, int br, boolean breath) {
        if (!usable()) return;
        try (FileOutputStream o = new FileOutputStream(FILE)) {
            o.write((pattern(hex, br, breath) + "\n").getBytes());
        } catch (Exception ignored) { }   // the module's fallback still updates the LED
    }
}
