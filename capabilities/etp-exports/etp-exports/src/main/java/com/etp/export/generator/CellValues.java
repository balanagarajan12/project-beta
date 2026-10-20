package com.etp.export.generator;

import java.math.BigDecimal;

/** Shared value-to-text rules for generators. */
final class CellValues {

    private CellValues() {
    }

    static String asText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        if (value instanceof Double d && d == Math.rint(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(value);
    }

    /**
     * Protects against CSV/Excel formula injection: text starting with = + - @ TAB CR
     * is prefixed with an apostrophe. Numbers are never changed.
     */
    static String safeText(String s) {
        if (s.isEmpty()) {
            return s;
        }
        char c = s.charAt(0);
        boolean risky = c == '=' || c == '+' || c == '@' || c == '\t' || c == '\r'
                || (c == '-' && !isNumeric(s));
        return risky ? "'" + s : s;
    }

    private static boolean isNumeric(String s) {
        try {
            new BigDecimal(s.trim());
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
