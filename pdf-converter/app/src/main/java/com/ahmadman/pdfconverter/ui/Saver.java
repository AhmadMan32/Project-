package com.ahmadman.pdfconverter.ui;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import com.ahmadman.pdfconverter.convert.Sources;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** Copies outputs to Downloads or to a place the user picked. */
final class Saver {

    static final String FOLDER = "PDF Dönüştürücü";

    private Saver() {
    }

    /** Android 10+: no permission needed. Older versions need WRITE_EXTERNAL_STORAGE. */
    static boolean downloadsNeedPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
    }

    /** Saves into Download/PDF Dönüştürücü; returns the number of saved files. */
    @SuppressWarnings("deprecation")
    static int toDownloads(Context c, List<File> files) throws IOException {
        int saved = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver cr = c.getContentResolver();
            for (File f : files) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
                v.put(MediaStore.MediaColumns.MIME_TYPE, OutputProvider.mimeOf(f.getName()));
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) {
                    throw new IOException("MediaStore insert failed");
                }
                try (OutputStream out = cr.openOutputStream(uri); InputStream in = new FileInputStream(f)) {
                    if (out == null) {
                        throw new IOException("cannot write");
                    }
                    Sources.copy(in, out);
                } catch (IOException e) {
                    cr.delete(uri, null, null);
                    throw e;
                }
                v.clear();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, v, null, null);
                saved++;
            }
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            String[] paths = new String[files.size()];
            for (File f : files) {
                File target = unique(dir, f.getName());
                try (InputStream in = new FileInputStream(f); OutputStream out = new FileOutputStream(target)) {
                    Sources.copy(in, out);
                }
                paths[saved++] = target.getAbsolutePath();
            }
            MediaScannerConnection.scanFile(c, paths, null, null);
        }
        return saved;
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        String stem = Sources.stem(name);
        String ext = Sources.extension(name);
        int i = 2;
        while (f.exists()) {
            f = new File(dir, stem + " (" + i++ + ")" + (ext.isEmpty() ? "" : "." + ext));
        }
        return f;
    }

    static void copyTo(Context c, File f, Uri target) throws IOException {
        try (InputStream in = new FileInputStream(f); OutputStream out = c.getContentResolver().openOutputStream(target)) {
            if (out == null) {
                throw new IOException("cannot write");
            }
            Sources.copy(in, out);
        }
    }

    /** Writes every file into a folder chosen with ACTION_OPEN_DOCUMENT_TREE. */
    static int toTree(Context c, Uri tree, List<File> files) throws IOException {
        Uri dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        int saved = 0;
        for (File f : files) {
            Uri doc = DocumentsContract.createDocument(c.getContentResolver(), dir,
                    OutputProvider.mimeOf(f.getName()), f.getName());
            if (doc == null) {
                throw new IOException("cannot create " + f.getName());
            }
            copyTo(c, f, doc);
            saved++;
        }
        return saved;
    }
}
