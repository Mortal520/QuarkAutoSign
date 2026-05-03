package com.qurk.autosign;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public class LogProvider extends ContentProvider {
    public static final String AUTHORITY = "com.qurk.autosign.logprovider";
    public static final Uri LOG_URI = Uri.parse("content://" + AUTHORITY + "/log");
    private static final String LOG_FILE = "autosign_log.txt";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"log"});
        try {
            File logFile = new File(getContext().getFilesDir(), LOG_FILE);
            if (logFile.exists()) {
                BufferedReader br = new BufferedReader(new FileReader(logFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(line);
                }
                br.close();
                cursor.addRow(new Object[]{sb.toString()});
            }
        } catch (Throwable ignored) {}
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        try {
            String log = values.getAsString("log");
            if (log != null && getContext() != null) {
                File logFile = new File(getContext().getFilesDir(), LOG_FILE);
                FileWriter fw = new FileWriter(logFile, false);
                fw.write(log);
                fw.close();
            }
        } catch (Throwable ignored) {}
        return uri;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override
    public String getType(Uri uri) { return "text/plain"; }
}
