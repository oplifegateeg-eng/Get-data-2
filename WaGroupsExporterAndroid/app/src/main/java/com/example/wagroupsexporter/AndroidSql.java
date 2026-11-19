package com.example.wagroupsexporter;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/** SqlSource on top of Android's SQLite. */
final class AndroidSql implements SqlSource {
    private final SQLiteDatabase db;

    AndroidSql(SQLiteDatabase db) {
        this.db = db;
    }

    @Override
    public void query(String sql, Row handler) throws Exception {
        Cursor c = db.rawQuery(sql, null);
        try {
            int n = c.getColumnCount();
            while (c.moveToNext()) {
                Object[] r = new Object[n];
                for (int i = 0; i < n; i++) {
                    switch (c.getType(i)) {
                        case Cursor.FIELD_TYPE_INTEGER:
                            r[i] = Long.valueOf(c.getLong(i));
                            break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            r[i] = Double.valueOf(c.getDouble(i));
                            break;
                        case Cursor.FIELD_TYPE_STRING:
                            r[i] = c.getString(i);
                            break;
                        default:
                            r[i] = null;
                    }
                }
                handler.accept(r);
            }
        } finally {
            c.close();
        }
    }
}
