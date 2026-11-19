package com.example.wagroupsexporter;

/** Minimal read-only access to the SQLite database (Android's SQLite in the app, a stand-in in the tests). */
public interface SqlSource {
    interface Row {
        void accept(Object[] values) throws Exception;
    }

    void query(String sql, Row handler) throws Exception;
}
