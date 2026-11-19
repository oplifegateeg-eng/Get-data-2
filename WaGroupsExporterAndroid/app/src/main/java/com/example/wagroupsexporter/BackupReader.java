package com.example.wagroupsexporter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads groups, members, admins and message dates out of a decrypted WhatsApp msgstore.db. */
public final class BackupReader {
    private BackupReader() { }

    public interface Log {
        void log(String message);
    }

    public static final class Member {
        public String key = "";
        public String number = "";        // digits; empty when WhatsApp hides the number
        public boolean hidden;
        public int rank;                  // 0 member, 1 admin, 2 group creator/super admin
        public boolean isMe;
        public long lastMsg;
        public long msgCount;
        public String savedName = "";
        public boolean saved;
    }

    public static final class Group {
        public long chatId, jidRow;
        public String jid = "", name = "";
        public long created, sortTs, firstMsg, lastMsg, msgCount, meLast;
        public final List<Member> members = new ArrayList<>();
        public boolean meKnown, meAdmin;
    }

    public static final class Result {
        public final List<Group> groups = new ArrayList<>();
        public String ownDescription = "not found";
        public long totalMessages, oldestMsg, newestMsg;
        public final List<String> notes = new ArrayList<>();
    }

    private static final class Ident {
        String key = "";
        String number = "";
        boolean hidden;
    }

    // ------------------------------------------------------------------ small helpers
    private static long asLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    private static String asStr(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static long ms(long t) {
        if (t <= 0) return 0;
        return t < 100000000000L ? t * 1000L : t;     // some versions store seconds
    }

    static String digits(String s) {
        return ContactBook.digits(s);
    }

    private static Set<String> columns(SqlSource db, String table) throws Exception {
        Set<String> out = new HashSet<>();
        db.query("PRAGMA table_info(" + table + ")", r -> out.add(asStr(r[1]).toLowerCase()));
        return out;
    }

    private static Ident identity(long row, Map<Long, String> jidRaw, Map<Long, Long> lidToPhone) {
        Ident id = new Ident();
        String raw = jidRaw.get(row);
        if (raw == null || raw.isEmpty()) {
            id.key = "row:" + row;
            id.hidden = true;
            return id;
        }
        int at = raw.indexOf('@');
        String user = at > 0 ? raw.substring(0, at) : raw;
        String server = at > 0 ? raw.substring(at + 1) : "";
        int colon = user.indexOf(':');
        if (colon > 0) user = user.substring(0, colon);          // device suffix
        if (server.startsWith("lid")) {
            Long pr = lidToPhone.get(row);
            String praw = pr == null ? null : jidRaw.get(pr);
            if (praw != null && praw.indexOf('@') > 0) {
                String d = digits(praw.substring(0, praw.indexOf('@')));
                if (!d.isEmpty()) {
                    id.key = d;
                    id.number = d;
                    return id;
                }
            }
            id.key = "lid:" + user;
            id.hidden = true;
            return id;
        }
        String d = digits(user);
        if (d.isEmpty()) {
            id.key = "jid:" + raw;
            id.hidden = true;
            return id;
        }
        id.key = d;
        id.number = d;
        return id;
    }

    // ------------------------------------------------------------------ main entry
    public static Result read(SqlSource db, Log log) throws Exception {
        Result res = new Result();
        Set<String> tables = new HashSet<>();
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'", r -> tables.add(asStr(r[0]).toLowerCase()));
        for (String need : new String[] {"jid", "chat", "message"}) {
            if (!tables.contains(need)) {
                throw new IOException("This does not look like a WhatsApp message database (table \"" + need + "\" is missing).");
            }
        }

        // jid table: row id -> "number@server"
        log.log("Reading contacts and groups...");
        Map<Long, String> jidRaw = new HashMap<>();
        db.query("SELECT _id, raw_string FROM jid", r -> jidRaw.put(asLong(r[0]), asStr(r[1])));

        // newer WhatsApp: hidden (LID) ids mapped to phone numbers
        Map<Long, Long> lidToPhone = new HashMap<>();
        if (tables.contains("jid_map")) {
            Set<String> c = columns(db, "jid_map");
            if (c.contains("lid_row_id") && c.contains("jid_row_id")) {
                db.query("SELECT lid_row_id, jid_row_id FROM jid_map", r -> lidToPhone.put(asLong(r[0]), asLong(r[1])));
            }
        }

        // groups = chats whose jid ends with @g.us
        Set<String> cc = columns(db, "chat");
        String sub = cc.contains("subject") ? "c.subject" : "NULL";
        String cre = cc.contains("created_timestamp") ? "c.created_timestamp" : "0";
        String srt = cc.contains("sort_timestamp") ? "c.sort_timestamp" : "0";
        Map<Long, Group> byJidRow = new LinkedHashMap<>();
        Map<Long, Group> byChat = new HashMap<>();
        Map<String, Group> byRaw = new HashMap<>();
        db.query("SELECT c._id, c.jid_row_id, " + sub + ", " + cre + ", " + srt + ", j.raw_string FROM chat c "
                + "JOIN jid j ON j._id = c.jid_row_id WHERE j.raw_string LIKE '%@g.us'", r -> {
            Group g = new Group();
            g.chatId = asLong(r[0]);
            g.jidRow = asLong(r[1]);
            g.name = asStr(r[2]);
            g.created = ms(asLong(r[3]));
            g.sortTs = ms(asLong(r[4]));
            g.jid = asStr(r[5]);
            if (g.name.isEmpty()) g.name = "(no name) " + g.jid;
            byJidRow.put(g.jidRow, g);
            byChat.put(g.chatId, g);
            byRaw.put(g.jid, g);
        });
        if (byChat.isEmpty()) {
            throw new IOException("No groups were found in this backup.");
        }
        log.log("Groups found in the backup: " + byChat.size());

        // participants: {groupRow, userRow, rank}
        List<long[]> parts = new ArrayList<>();
        if (tables.contains("group_participant_user")) {
            Set<String> pc = columns(db, "group_participant_user");
            String rk = pc.contains("rank") ? "rank" : (pc.contains("admin") ? "admin" : "0");
            if (pc.contains("group_jid_row_id") && pc.contains("user_jid_row_id")) {
                db.query("SELECT group_jid_row_id, user_jid_row_id, " + rk + " FROM group_participant_user",
                        r -> parts.add(new long[] {asLong(r[0]), asLong(r[1]), asLong(r[2])}));
            }
        } else if (tables.contains("group_participants")) {                // older layout
            long[] synth = {-1};
            Map<String, Long> synthRow = new HashMap<>();
            db.query("SELECT gjid, jid, admin FROM group_participants", r -> {
                Group g = byRaw.get(asStr(r[0]));
                if (g == null) return;
                String raw = asStr(r[1]);
                long userRow = 0;
                if (!raw.isEmpty()) {
                    Long ex = synthRow.get(raw);
                    if (ex == null) {
                        ex = synth[0]--;
                        synthRow.put(raw, ex);
                        jidRaw.put(ex, raw);
                    }
                    userRow = ex;
                }
                parts.add(new long[] {g.jidRow, userRow, asLong(r[2])});
            });
        } else {
            res.notes.add("The backup has no group member table, so member lists are empty.");
        }

        log.log("Group participant rows: " + parts.size());
        if (parts.isEmpty()) {
            List<String> names = new ArrayList<>(tables);
            java.util.Collections.sort(names);
            res.notes.add("No group participants were found. Tables in this database: " + String.join(", ", names));
        }

        // the account owner is the participant that appears in the most groups
        Map<Long, Integer> seen = new HashMap<>();
        for (long[] p : parts) if (byJidRow.containsKey(p[0])) seen.merge(p[1], 1, Integer::sum);
        long ownRow = Long.MIN_VALUE;
        int best = 0;
        for (Map.Entry<Long, Integer> e : seen.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                ownRow = e.getKey();
            }
        }
        boolean ownKnown = best >= 3;
        if (ownKnown) {
            if (ownRow == 0) res.ownDescription = "your own account (stored without a number)";
            else {
                Ident oi = identity(ownRow, jidRaw, lidToPhone);
                res.ownDescription = oi.number.isEmpty() ? "your own account" : "+" + oi.number;
            }
        } else {
            res.notes.add("Could not tell which participant is you, so \"Am I Admin?\" is empty.");
        }

        Map<Long, Map<String, Member>> membersByChat = new HashMap<>();
        Map<Long, Ident> identCache = new HashMap<>();
        for (long[] p : parts) {
            Group g = byJidRow.get(p[0]);
            if (g == null) continue;
            Map<String, Member> map = membersByChat.computeIfAbsent(g.chatId, k -> new LinkedHashMap<>());
            boolean me = ownKnown && p[1] == ownRow;
            Ident id;
            if (me) {
                id = new Ident();
                id.key = "me";
            } else {
                id = identCache.get(p[1]);
                if (id == null) {
                    id = identity(p[1], jidRaw, lidToPhone);
                    identCache.put(p[1], id);
                }
            }
            Member m = map.get(id.key);
            if (m == null) {
                m = new Member();
                m.key = id.key;
                m.number = id.number;
                m.hidden = id.hidden;
                m.isMe = me;
                map.put(id.key, m);
                g.members.add(m);
            }
            m.rank = Math.max(m.rank, (int) Math.min(2, Math.max(0, p[2])));
            if (me) {
                g.meKnown = true;
                g.meAdmin = m.rank > 0;
            }
        }

        // message dates per group and per sender (can take a while on big backups)
        Set<String> mc = columns(db, "message");
        if (!mc.contains("sender_jid_row_id") || !mc.contains("chat_row_id") || !mc.contains("timestamp")) {
            res.notes.add("This WhatsApp database layout is not supported for message dates. Columns of the message table: " + mc);
        } else {
            log.log("Reading message dates (this can take several minutes)...");
            String typeFilter = mc.contains("message_type") ? "AND m.message_type != 7 " : "";
            String fromMe = mc.contains("from_me") ? "m.from_me" : "0";
            String sql = "SELECT m.chat_row_id, m.sender_jid_row_id, " + fromMe + ", MAX(m.timestamp), MIN(m.timestamp), COUNT(*) "
                    + "FROM message m WHERE m.chat_row_id IN (SELECT c._id FROM chat c JOIN jid j ON j._id = c.jid_row_id "
                    + "WHERE j.raw_string LIKE '%@g.us') " + typeFilter
                    + "GROUP BY m.chat_row_id, m.sender_jid_row_id, " + fromMe;
            long[] rows = {0};
            db.query(sql, r -> {
                Group g = byChat.get(asLong(r[0]));
                if (g == null) return;
                long senderRow = asLong(r[1]);
                boolean mine = asLong(r[2]) == 1;
                long max = ms(asLong(r[3])), min = ms(asLong(r[4])), cnt = asLong(r[5]);
                g.msgCount += cnt;
                if (max > g.lastMsg) g.lastMsg = max;
                if (min > 0 && (g.firstMsg == 0 || min < g.firstMsg)) g.firstMsg = min;
                res.totalMessages += cnt;
                if (max > res.newestMsg) res.newestMsg = max;
                if (min > 0 && (res.oldestMsg == 0 || min < res.oldestMsg)) res.oldestMsg = min;
                Map<String, Member> map = membersByChat.get(g.chatId);
                if (map == null) return;
                Member m;
                if (mine) {
                    if (max > g.meLast) g.meLast = max;
                    m = map.get("me");
                } else {
                    Ident id = identCache.get(senderRow);
                    if (id == null) {
                        id = identity(senderRow, jidRaw, lidToPhone);
                        identCache.put(senderRow, id);
                    }
                    m = map.get(id.key);
                }
                if (m != null) {
                    if (max > m.lastMsg) m.lastMsg = max;
                    m.msgCount += cnt;
                }
                rows[0]++;
            });
            log.log("Message summaries read: " + rows[0]);
        }

        res.groups.addAll(byJidRow.values());
        return res;
    }
}
