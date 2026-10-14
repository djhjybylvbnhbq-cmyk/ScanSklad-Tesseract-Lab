package com.scansklad.tesseractlab;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Suggest-only validation against a user-supplied snapshot of warehouse shelf codes.
 *  IMPORTANT: a match based on the digits alone is NOT a safe automatic correction. */
public final class AddressCatalog {
    private final Set<String> known = new LinkedHashSet<>();

    public AddressCatalog(InputStream file) throws IOException {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(file, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) known.add(canonical(line));
            }
        }
    }

    public int size() { return known.size(); }

    public static String canonical(String value) {
        String s = (value == null ? "" : value).trim().toUpperCase(Locale.ROOT)
                .replace('／', '/').replace('⁄', '/').replace('∕', '/')
                .replaceAll("\\s+", "");
        // Strip marker only in a named location, so shelf addresses remain unchanged.
        s = s.replace("ШКАФ№", "ШКАФ").replace("ШКАФ#", "ШКАФ")
                .replace("ШКАФNO", "ШКАФ").replace("ШКАФN0", "ШКАФ")
                .replace("СЕЙФ№", "СЕЙФ").replace("СЕЙФ#", "СЕЙФ")
                .replace("СЕЙФNO", "СЕЙФ").replace("СЕЙФN0", "СЕЙФ");
        return s;
    }

    private static String digitSkeleton(String s) {
        return s.replaceAll("[^0-9/]", "");
    }

    public String inspect(String text) {
        String input = canonical(text);
        if (input.isEmpty()) return "Справочник: адрес не распознан";
        if (known.contains(input)) return "Справочник: точное совпадение ✓  " + input;
        if (!input.matches("^[0-9]{2,4}/.+$")) return "Справочник: не удалось разобрать адрес. Не исправляем автоматически.";
        String signature = digitSkeleton(input);
        List<String> options = new ArrayList<>();
        for (String candidate : known) {
            if (digitSkeleton(candidate).equals(signature)) options.add(candidate);
        }
        if (options.isEmpty()) return "Справочник: совпадения нет. Адрес может быть новым; проверьте.";
        if (options.size() == 1) return "Справочник: возможный адрес " + options.get(0)
                + " (только по цифрам; НЕ подтверждено).";
        int shown = Math.min(6, options.size());
        StringBuilder out = new StringBuilder("Справочник: неоднозначно (");
        for (int i = 0; i < shown; ++i) {
            if (i != 0) out.append(", ");
            out.append(options.get(i));
        }
        if (options.size() > shown) out.append(" …");
        out.append("). Нельзя выбирать автоматически.");
        return out.toString();
    }
}
