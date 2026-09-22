package com.example.simpleeconomy;

import java.util.Locale;
import java.util.OptionalDouble;

public final class MoneyUtil {
    private MoneyUtil() {}

    /** Parses "500", "1.5k", "2m", "3b", "1t". */
    public static OptionalDouble parse(String input) {
        if (input == null) return OptionalDouble.empty();
        String s = input.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("$", "");
        if (s.isEmpty()) return OptionalDouble.empty();
        double mult = 1;
        switch (s.charAt(s.length() - 1)) {
            case 'k' -> mult = 1e3;
            case 'm' -> mult = 1e6;
            case 'b' -> mult = 1e9;
            case 't' -> mult = 1e12;
            default -> {}
        }
        if (mult != 1) s = s.substring(0, s.length() - 1);
        try {
            double v = Double.parseDouble(s) * mult;
            if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) return OptionalDouble.empty();
            return OptionalDouble.of(round(v));
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    public static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static String format(double v) {
        String[] suffix = {"", "K", "M", "B", "T"};
        int i = 0;
        double x = v;
        while (Math.abs(x) >= 1000 && i < suffix.length - 1) {
            x /= 1000;
            i++;
        }
        String s = String.format(Locale.US, "%.2f", x);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return "$" + s + suffix[i];
    }
}
