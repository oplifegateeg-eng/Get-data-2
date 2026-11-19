package com.example.wagroupsexporter;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Turns the data read from the backup into the Excel workbook. */
public final class ReportBuilder {
    private ReportBuilder() { }

    private static final long DAY = 86400000L;

    private static String fmt(long t) {
        return t <= 0 ? "" : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(t));
    }

    private static String cap(String s) {
        return s.length() > 32000 ? s.substring(0, 32000) + " | ..." : s;
    }

    private static String shown(BackupReader.Member m) {
        if (m.isMe) return "(you)";
        return m.number.isEmpty() ? "Hidden" : m.number;
    }

    public static void write(OutputStream os, BackupReader.Result res, ContactBook book, String appName) throws IOException {
        long now = System.currentTimeMillis();
        List<BackupReader.Group> groups = new ArrayList<>(res.groups);
        groups.sort(Comparator.comparing(g -> g.name.toLowerCase(Locale.ROOT)));

        // saved names from the phone's address book
        if (book != null) {
            for (BackupReader.Group g : groups) {
                for (BackupReader.Member m : g.members) {
                    if (m.isMe || m.number.isEmpty()) continue;
                    String n = book.lookup(m.number);
                    if (n != null) {
                        m.saved = true;
                        m.savedName = n;
                    }
                }
            }
        }

        // in which groups each person is
        Map<String, List<BackupReader.Group>> inGroups = new HashMap<>();
        for (BackupReader.Group g : groups) {
            for (BackupReader.Member m : g.members) {
                if (m.isMe) continue;
                inGroups.computeIfAbsent(m.key, k -> new ArrayList<>()).add(g);
            }
        }

        List<XlsxWriter.Sheet> sheets = new ArrayList<>();

        // ---- Groups
        List<Object[]> groupRows = new ArrayList<>();
        List<Object[]> adminRows = new ArrayList<>();
        int adminGroups = 0;
        for (BackupReader.Group g : groups) {
            long last = g.lastMsg > 0 ? g.lastMsg : 0;
            String lastText = last > 0 ? fmt(last) : (g.created > 0 ? fmt(g.created) + " (creation date, no messages in backup)" : "");
            Object days = last > 0 ? (Object) Long.valueOf((now - last) / DAY) : (Object) "";
            int admins = 0, saved = 0, dup = 0;
            StringBuilder adminNames = new StringBuilder();
            for (BackupReader.Member m : g.members) {
                if (m.isMe) continue;
                if (m.rank > 0) {
                    admins++;
                    if (adminNames.length() > 0) adminNames.append(" | ");
                    adminNames.append(m.savedName.isEmpty() ? "" : m.savedName + ":").append(shown(m));
                }
                if (m.saved) saved++;
                if (inGroups.get(m.key) != null && inGroups.get(m.key).size() > 1) dup++;
            }
            String amAdmin = !g.meKnown ? "" : (g.meAdmin ? "Yes" : "No");
            groupRows.add(new Object[] {g.name, fmt(g.created), lastText, days, fmt(g.firstMsg), g.msgCount, g.members.size(), admins,
                    cap(adminNames.toString()), amAdmin, saved, dup});
            if (g.meAdmin) {
                adminGroups++;
                adminRows.add(new Object[] {g.name, fmt(g.created), lastText, days, g.members.size()});
            }
        }
        sheets.add(new XlsxWriter.Sheet("Groups", new Object[] {"Group Name", "Creation Date", "Last Activity Date", "Days Inactive",
                "First Message In Backup", "Messages In Backup", "Members Count", "Admins Count", "Admins (name:number)", "Am I Admin?",
                "Saved Contacts Count", "Duplicated Members"}, groupRows, new int[] {34, 16, 30, 12, 18, 14, 12, 12, 50, 12, 14, 14}));
        sheets.add(new XlsxWriter.Sheet("My Admin Groups", new Object[] {"Group Name", "Creation Date", "Last Activity Date", "Days Inactive",
                "Members Count"}, adminRows, new int[] {34, 16, 30, 12, 12}));

        // ---- Members (split if Excel's row limit would be exceeded)
        Object[] mHead = {"Group Name", "Group Last Activity Date", "Member Last Message In This Group", "My Saved Name", "In My Contacts?",
                "Number", "Is Admin?", "In Other Groups?", "In Groups Count", "Other Groups"};
        int[] mW = {34, 22, 26, 26, 14, 18, 10, 14, 14, 60};
        List<Object[]> memberRows = new ArrayList<>();
        Set<String> people = new HashSet<>();
        int hiddenPeople = 0;
        Set<String> hiddenSeen = new HashSet<>();
        for (BackupReader.Group g : groups) {
            String groupLast = g.lastMsg > 0 ? fmt(g.lastMsg) : "";
            for (BackupReader.Member m : g.members) {
                String lastText;
                if (m.lastMsg > 0) lastText = fmt(m.lastMsg);
                else if (g.msgCount == 0) lastText = "(no messages in backup)";
                else lastText = "(no message in backup)";
                List<BackupReader.Group> others = m.isMe ? new ArrayList<>() : inGroups.getOrDefault(m.key, new ArrayList<>());
                StringBuilder on = new StringBuilder();
                for (BackupReader.Group o : others) {
                    if (o == g) continue;
                    if (on.length() > 0) on.append(" | ");
                    on.append(o.name);
                }
                if (!m.isMe) {
                    people.add(m.key);
                    if (m.hidden && hiddenSeen.add(m.key)) hiddenPeople++;
                }
                memberRows.add(new Object[] {g.name, groupLast, lastText, m.savedName, m.isMe ? "" : (m.saved ? "Yes" : "No"), shown(m),
                        m.rank > 0 ? "Yes" : "No", others.size() > 1 ? "Yes" : "No", m.isMe ? "" : (Object) Integer.valueOf(others.size()),
                        cap(on.toString())});
            }
        }
        final int chunk = 1000000;
        for (int k = 0, part = 1; k < Math.max(memberRows.size(), 1); k += chunk, part++) {
            List<Object[]> sub = memberRows.subList(k, Math.min(memberRows.size(), k + chunk));
            sheets.add(new XlsxWriter.Sheet(part == 1 ? "Members" : "Members " + part, mHead, new ArrayList<>(sub), mW));
        }

        // ---- Phone contacts and their groups
        if (book != null && book.size() > 0) {
            Map<String, List<BackupReader.Group>> byContactKey = new HashMap<>();
            Map<String, Integer> adminIn = new HashMap<>();
            for (BackupReader.Group g : groups) {
                for (BackupReader.Member m : g.members) {
                    if (m.isMe || m.number.isEmpty()) continue;
                    String k = ContactBook.key(m.number);
                    byContactKey.computeIfAbsent(k, x -> new ArrayList<>()).add(g);
                    if (m.rank > 0) adminIn.merge(k, 1, Integer::sum);
                }
            }
            List<Object[]> rows = new ArrayList<>();
            for (String[] c : book.contacts) {
                String k = ContactBook.key(c[1]);
                List<BackupReader.Group> gs = byContactKey.getOrDefault(k, new ArrayList<>());
                StringBuilder names = new StringBuilder();
                for (BackupReader.Group g : gs) {
                    if (names.length() > 0) names.append(" | ");
                    names.append(g.name);
                }
                rows.add(new Object[] {c[0], c[1], gs.size(), gs.isEmpty() ? "No group" : (gs.size() > 1 ? "Duplicated" : "Unique"),
                        adminIn.getOrDefault(k, 0), cap(names.toString())});
            }
            rows.sort(Comparator.comparing(r -> String.valueOf(r[0]).toLowerCase(Locale.ROOT)));
            sheets.add(new XlsxWriter.Sheet("Contacts", new Object[] {"Contact Name", "Number", "Groups Count", "Duplicated?",
                    "Admin In (count)", "Groups"}, rows, new int[] {28, 18, 12, 12, 14, 70}));
        }

        // ---- Issues
        List<Object[]> issues = new ArrayList<>();
        for (BackupReader.Group g : groups) {
            if (g.members.isEmpty()) issues.add(new Object[] {g.name, "No member list in the backup"});
            if (g.msgCount == 0) issues.add(new Object[] {g.name, "No messages in the backup (last activity falls back to the creation date)"});
        }
        for (String n : res.notes) issues.add(new Object[] {"(general)", n});
        if (issues.isEmpty()) issues.add(new Object[] {"(none)", "Every group was read completely"});
        sheets.add(new XlsxWriter.Sheet("Issues", new Object[] {"Group", "Problem"}, issues, new int[] {40, 100}));

        // ---- Summary (first sheet)
        int noMessages = 0, noMembers = 0;
        for (BackupReader.Group g : groups) {
            if (g.msgCount == 0) noMessages++;
            if (g.members.isEmpty()) noMembers++;
        }
        List<Object[]> sum = new ArrayList<>();
        sum.add(new Object[] {"Export time", fmt(now)});
        sum.add(new Object[] {"Source", appName + " backup file"});
        sum.add(new Object[] {"Account owner detected", res.ownDescription});
        sum.add(new Object[] {"Groups in the backup", groups.size()});
        sum.add(new Object[] {"Groups where I am admin", adminGroups});
        sum.add(new Object[] {"Groups without messages in the backup", noMessages});
        sum.add(new Object[] {"Groups without a member list", noMembers});
        sum.add(new Object[] {"Member rows (person x group)", memberRows.size()});
        sum.add(new Object[] {"Unique people", people.size()});
        sum.add(new Object[] {"People whose number WhatsApp hides", hiddenPeople});
        sum.add(new Object[] {"Group messages counted", res.totalMessages});
        sum.add(new Object[] {"Backup covers messages from", fmt(res.oldestMsg)});
        sum.add(new Object[] {"Backup covers messages until", fmt(res.newestMsg)});
        sum.add(new Object[] {"Phone contacts used", book == null ? "not used" : (Object) Integer.valueOf(book.size())});
        sum.add(new Object[] {"Invite links", "Not stored in a WhatsApp backup, so they are not included"});
        sheets.add(0, new XlsxWriter.Sheet("Summary", new Object[] {"Check", "Value"}, sum, new int[] {44, 60}));

        XlsxWriter.write(os, sheets);
    }
}
