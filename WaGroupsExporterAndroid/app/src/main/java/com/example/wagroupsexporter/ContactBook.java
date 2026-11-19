package com.example.wagroupsexporter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The phone's address book, matched with WhatsApp numbers by their last digits. */
public final class ContactBook {
    private final Map<String, String> byKey = new HashMap<>();
    public final List<String[]> contacts = new ArrayList<>();   // {name, digits}

    static String digits(String s) {
        StringBuilder sb = new StringBuilder();
        if (s != null) for (char c : s.toCharArray()) if (c >= '0' && c <= '9') sb.append(c);
        return sb.toString();
    }

    public static String key(String digits) {
        return digits.length() > 9 ? digits.substring(digits.length() - 9) : digits;
    }

    public void add(String name, String number) {
        String d = digits(number);
        if (d.length() < 7) return;
        String n = name == null ? "" : name;
        contacts.add(new String[] {n, d});
        if (!byKey.containsKey(key(d))) byKey.put(key(d), n);
    }

    /** Saved name for this WhatsApp number, or null when it is not in the address book. */
    public String lookup(String number) {
        String d = digits(number);
        if (d.length() < 7) return null;
        return byKey.get(key(d));
    }

    public int size() {
        return contacts.size();
    }
}
