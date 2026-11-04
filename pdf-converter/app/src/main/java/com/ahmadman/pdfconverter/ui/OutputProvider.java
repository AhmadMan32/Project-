package com.ahmadman.pdfconverter.ui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import com.ahmadman.pdfconverter.convert.Sources;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Read-only provider exposing finished outputs to other apps (share / open with). */
public class OutputProvider extends ContentProvider {

    static String authority(Context c) {
        return c.getPackageName() + ".files";
    }

    /** content:// URI for a file inside the jobs folder. */
    static Uri uriFor(Context c, File f) {
        String root = JobRunner.jobsDir(c).getAbsolutePath();
        String rel = f.getAbsolutePath().substring(root.length() + 1);
        Uri.Builder b = new Uri.Builder().scheme("content").authority(authority(c));
        for (String part : rel.split("/")) {
            b.appendPath(part);
        }
        return b.build();
    }

    static String mimeOf(String name) {
        String ext = Sources.extension(name);
        switch (ext) {
            case "md":
                return "text/markdown";
            case "epub":
                return "application/epub+zip";
            case "rtf":
                return "application/rtf";
            case "csv":
                return "text/csv";
            default:
                String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                return m == null ? "application/octet-stream" : m;
        }
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        Context c = getContext();
        if (c == null) {
            throw new FileNotFoundException();
        }
        File root = JobRunner.jobsDir(c);
        File f = new File(root, uri.getPath() == null ? "" : uri.getPath());
        try {
            String canonical = f.getCanonicalPath();
            if (!canonical.startsWith(root.getCanonicalPath() + File.separator) || !f.isFile()) {
                throw new FileNotFoundException(uri.toString());
            }
        } catch (IOException e) {
            throw new FileNotFoundException(uri.toString());
        }
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("read only");
        }
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                row[i] = f.getName();
            } else if (OpenableColumns.SIZE.equals(cols[i])) {
                row[i] = f.length();
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return mimeOf(uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment());
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
