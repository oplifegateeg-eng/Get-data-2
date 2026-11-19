package com.example.wagroupsexporter;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.ContactsContract;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Reads the end-to-end encrypted backup that WhatsApp (or WhatsApp Business) makes on this phone and exports
 * groups, members, admins and last-activity dates to an Excel file. Nothing leaves the phone: the app has no
 * internet permission.
 */
public class MainScreen extends Activity {
    private static final int REQ_PICK = 11;
    private static final int REQ_CONTACTS = 12;
    private static final String MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int GREEN = 0xFF128C7E;
    private static final int GREY = 0xFFD0D0D0;

    private String pkg = null;
    private String folder = null;
    private String appName = "";
    private Uri backupUri = null;
    private long backupSize = 0;
    private Uri resultUri = null;
    private volatile boolean busy = false;

    private Button btnWa, btnBiz, btnOpenWa, btnPick, btnGo, btnOpenResult;
    private TextView instrView, fileView, statusView, logView;
    private EditText keyEdit;
    private CheckBox contactsBox;
    private ProgressBar bar;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setPadding(0, dp(6), 0, dp(2));
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(14), dp(14), dp(14), dp(24));
        scroll.addView(root);
        setContentView(scroll);

        root.addView(text("WhatsApp Groups Exporter", 22, Color.BLACK));
        root.addView(text("Reads the backup file that WhatsApp makes on this phone. Everything stays on the phone: this app has no internet access.", 13, Color.DKGRAY));

        root.addView(text("1. Which app?", 16, Color.BLACK));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        btnWa = button("WhatsApp");
        btnBiz = button("WhatsApp Business");
        row.addView(btnWa, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(btnBiz, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);
        btnWa.setOnClickListener(v -> chooseApp("com.whatsapp", "WhatsApp", "WhatsApp"));
        btnBiz.setOnClickListener(v -> chooseApp("com.whatsapp.w4b", "WhatsApp Business", "WhatsApp Business"));

        instrView = text("", 13, Color.DKGRAY);
        root.addView(instrView);
        btnOpenWa = button("Open the app to make the backup");
        btnOpenWa.setOnClickListener(v -> openWhatsApp());
        root.addView(btnOpenWa);

        root.addView(text("2. Choose the backup file", 16, Color.BLACK));
        btnPick = button("Choose msgstore.db.crypt15");
        btnPick.setOnClickListener(v -> pickFile());
        root.addView(btnPick);
        fileView = text("No file chosen yet.", 13, Color.DKGRAY);
        root.addView(fileView);

        root.addView(text("3. Your 64-digit backup key", 16, Color.BLACK));
        keyEdit = new EditText(this);
        keyEdit.setHint("Paste the 64 digits here");
        keyEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        keyEdit.setMinLines(2);
        root.addView(keyEdit);

        contactsBox = new CheckBox(this);
        contactsBox.setText("Use my phone contacts to show names and \"In My Contacts?\"");
        contactsBox.setChecked(true);
        root.addView(contactsBox);

        btnGo = button("Create the Excel file");
        btnGo.setBackgroundColor(GREEN);
        btnGo.setTextColor(Color.WHITE);
        btnGo.setOnClickListener(v -> onCreateClicked());
        root.addView(btnGo);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setVisibility(View.GONE);
        root.addView(bar);
        statusView = text("", 15, Color.BLACK);
        root.addView(statusView);
        logView = text("", 11, Color.DKGRAY);
        root.addView(logView);
        btnOpenResult = button("Open the Excel file");
        btnOpenResult.setVisibility(View.GONE);
        btnOpenResult.setOnClickListener(v -> openResult());
        root.addView(btnOpenResult);

        chooseApp("com.whatsapp", "WhatsApp", "WhatsApp");
    }

    // ------------------------------------------------------------------ step 1: choose the app
    private void chooseApp(String packageName, String name, String mediaFolder) {
        pkg = packageName;
        appName = name;
        folder = mediaFolder;
        btnWa.setBackgroundColor("com.whatsapp".equals(pkg) ? GREEN : GREY);
        btnWa.setTextColor("com.whatsapp".equals(pkg) ? Color.WHITE : Color.BLACK);
        btnBiz.setBackgroundColor("com.whatsapp.w4b".equals(pkg) ? GREEN : GREY);
        btnBiz.setTextColor("com.whatsapp.w4b".equals(pkg) ? Color.WHITE : Color.BLACK);
        instrView.setText("Make the backup file (once):\n"
                + "1. Open " + name + " > the three dots > Settings > Chats > Chat backup.\n"
                + "2. Tap \"End-to-end encrypted backup\" > Turn on > choose \"Use 64-digit encryption key instead\" > Continue.\n"
                + "3. " + name + " shows a 64-digit key. Copy it or write it down: you paste it below.\n"
                + "4. Finish, then tap BACK UP. Wait until the backup is complete.\n\n"
                + "The backup file is then here (Internal storage):\n"
                + "Android/media/" + packageName + "/" + mediaFolder + "/Databases/msgstore.db.crypt15");
        btnOpenWa.setText("Open " + name);
    }

    private void openWhatsApp() {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(this, appName + " is not installed on this phone.", Toast.LENGTH_LONG).show();
        } else {
            startActivity(i);
        }
    }

    // ------------------------------------------------------------------ step 2: choose the file
    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            Uri start = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
                    "primary:Android/media/" + pkg + "/" + folder + "/Databases");
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, start);
        } catch (Exception ignored) {
            // the hint is optional
        }
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        backupUri = data.getData();
        String name = "(unknown name)";
        backupSize = 0;
        Cursor c = getContentResolver().query(backupUri, null, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int si = c.getColumnIndex(OpenableColumns.SIZE);
                    if (ni >= 0) name = c.getString(ni);
                    if (si >= 0) backupSize = c.getLong(si);
                }
            } finally {
                c.close();
            }
        }
        String warn = "";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".crypt14") || lower.endsWith(".crypt12") || lower.endsWith(".crypt8")) {
            warn = "\nThis is an older, normal backup that cannot be opened with a 64-digit key. Turn on end-to-end encrypted backup and make a new backup: it creates a .crypt15 file.";
        } else if (!lower.contains("crypt15")) {
            warn = "\nThe file name should contain \"crypt15\" (for example msgstore.db.crypt15).";
        }
        fileView.setText("Chosen: " + name + " (" + (backupSize / (1024 * 1024)) + " MB)" + warn);
    }

    // ------------------------------------------------------------------ step 3: run
    private void onCreateClicked() {
        if (busy) return;
        if (backupUri == null) {
            toast("Choose the backup file first.");
            return;
        }
        try {
            Crypt15.parseKey(keyEdit.getText().toString());
        } catch (IllegalArgumentException e) {
            toast(e.getMessage());
            return;
        }
        if (contactsBox.isChecked() && checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.READ_CONTACTS}, REQ_CONTACTS);
            return;
        }
        startRun();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CONTACTS) return;
        boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!ok) {
            contactsBox.setChecked(false);
            toast("Without the contacts permission the file will show numbers without names.");
        }
        startRun();
    }

    private void startRun() {
        if (busy) return;
        busy = true;
        resultUri = null;
        final Uri uri = backupUri;
        final String keyText = keyEdit.getText().toString();
        final boolean useContacts = contactsBox.isChecked()
                && checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        logView.setText("");
        btnOpenResult.setVisibility(View.GONE);
        btnGo.setEnabled(false);
        bar.setVisibility(View.VISIBLE);
        new Thread(() -> run(uri, keyText, useContacts)).start();
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    private void status(String s) {
        runOnUiThread(() -> statusView.setText(s));
    }

    private void log(String s) {
        runOnUiThread(() -> logView.setText(logView.getText() + s + "\n"));
    }

    private ContactBook loadContacts() {
        ContactBook book = new ContactBook();
        Cursor c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[] {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null, null);
        if (c != null) {
            try {
                while (c.moveToNext()) book.add(c.getString(0), c.getString(1));
            } finally {
                c.close();
            }
        }
        return book;
    }

    private void run(final Uri uri, final String keyText, final boolean useContacts) {
        File dbFile = new File(getCacheDir(), "wa-backup-decrypted.db");
        try {
            byte[] key = Crypt15.parseKey(keyText);
            if (backupSize > 0 && getCacheDir().getUsableSpace() < backupSize * 3) {
                throw new IOException("Not enough free space on the phone. About " + (backupSize * 3 / (1024 * 1024)) + " MB are needed temporarily.");
            }
            status("Step 1 of 3: opening the backup (this can take several minutes)...");
            final long total = Math.max(1, backupSize);
            Crypt15.decryptToFile(() -> {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new IOException("Cannot open the chosen file.");
                return in;
            }, key, dbFile, done -> status("Step 1 of 3: opening the backup... " + Math.min(99, done * 100 / total) + "%"));

            status("Step 2 of 3: reading groups, members and message dates (can take several minutes)...");
            SQLiteDatabase sq = SQLiteDatabase.openDatabase(dbFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            BackupReader.Result res;
            try {
                res = BackupReader.read(new AndroidSql(sq), m -> log(m));
            } finally {
                sq.close();
            }

            ContactBook book = useContacts ? loadContacts() : null;
            if (book != null) log("Phone contacts read: " + book.size());

            status("Step 3 of 3: creating the Excel file...");
            String name = "whatsapp_groups_backup_" + new SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US).format(new Date()) + ".xlsx";
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, MIME);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri out = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (out == null) throw new IOException("Could not create the file in the Downloads folder.");
            OutputStream os = getContentResolver().openOutputStream(out);
            if (os == null) throw new IOException("Could not write to the Downloads folder.");
            try {
                ReportBuilder.write(os, res, book, appName);
            } finally {
                os.close();
            }
            resultUri = out;
            status("Finished. The Excel file is in your Downloads folder: " + name);
            runOnUiThread(() -> btnOpenResult.setVisibility(View.VISIBLE));
        } catch (OutOfMemoryError e) {
            status("Stopped: the phone ran out of memory while reading this backup.");
        } catch (Throwable t) {
            String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            status("Stopped: " + m);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            dbFile.delete();
            busy = false;
            runOnUiThread(() -> {
                btnGo.setEnabled(true);
                bar.setVisibility(View.GONE);
            });
        }
    }

    private void openResult() {
        if (resultUri == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(resultUri, MIME);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("Saved in your Downloads folder. Open it with an Excel app or Google Sheets.");
        }
    }
}
