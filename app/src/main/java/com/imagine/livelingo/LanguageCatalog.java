package com.imagine.livelingo;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class LanguageCatalog {
    public static final LinkedHashMap<String, String> TARGETS = new LinkedHashMap<>();
    static {
        TARGETS.put("Русский", "ru"); TARGETS.put("English", "en"); TARGETS.put("Deutsch", "de");
        TARGETS.put("Español", "es"); TARGETS.put("Français", "fr"); TARGETS.put("Italiano", "it");
        TARGETS.put("Português", "pt"); TARGETS.put("Polski", "pl"); TARGETS.put("Türkçe", "tr");
        TARGETS.put("Українська", "uk"); TARGETS.put("中文", "zh"); TARGETS.put("日本語", "ja");
        TARGETS.put("한국어", "ko"); TARGETS.put("العربية", "ar"); TARGETS.put("हिन्दी", "hi");
    }
    private LanguageCatalog() {}
    public static Locale localeFor(String tag) { return Locale.forLanguageTag(tag == null ? "ru" : tag); }
    public static String displayForCode(String code) {
        if (code == null || code.isBlank()) return "авто";
        Locale l = Locale.forLanguageTag(code); String name = l.getDisplayLanguage(new Locale("ru"));
        return name == null || name.isBlank() ? code : name;
    }
    public static String codeForDisplay(String display) {
        for (Map.Entry<String,String> e : TARGETS.entrySet()) if (e.getKey().equals(display)) return e.getValue();
        return "ru";
    }
}
