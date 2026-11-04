package com.ahmadman.pdfconverter.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Helpers for the ZIP based formats (DOCX, XLSX, PPTX, EPUB). */
public final class Zip {

    /** Per-entry and total limits to protect against huge or malicious archives. */
    public static final long MAX_ENTRY = 64L * 1024 * 1024;
    public static final long MAX_TOTAL = 256L * 1024 * 1024;

    private Zip() {
    }

    /** Reads every entry into memory (names without leading slash). */
    public static Map<String, byte[]> readAll(InputStream in) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                long size = 0;
                boolean skipped = false;
                while ((n = zin.read(buf)) > 0) {
                    size += n;
                    if (size > MAX_ENTRY) {
                        skipped = true;
                        continue; // drain without keeping
                    }
                    bos.write(buf, 0, n);
                }
                total += Math.min(size, MAX_ENTRY);
                if (total > MAX_TOTAL) {
                    throw new IOException("archive too large");
                }
                if (!skipped) {
                    String name = e.getName().replace('\\', '/');
                    while (name.startsWith("/")) {
                        name = name.substring(1);
                    }
                    out.put(name, bos.toByteArray());
                }
            }
        }
        if (out.isEmpty()) {
            throw new IOException("not a zip archive");
        }
        return out;
    }

    /** Resolves a relationship target against the folder of the part that refers to it. */
    public static String resolve(String partPath, String target) {
        if (target.startsWith("/")) {
            return target.substring(1);
        }
        int slash = partPath.lastIndexOf('/');
        String base = slash >= 0 ? partPath.substring(0, slash + 1) : "";
        String[] parts = (base + target).split("/");
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".")) {
                continue;
            }
            if (p.equals("..")) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
            } else {
                stack.addLast(p);
            }
        }
        return String.join("/", stack);
    }

    /** Rels file path of a part: {@code word/document.xml -> word/_rels/document.xml.rels}. */
    public static String relsPath(String partPath) {
        int slash = partPath.lastIndexOf('/');
        String dir = slash >= 0 ? partPath.substring(0, slash + 1) : "";
        return dir + "_rels/" + partPath.substring(slash + 1) + ".rels";
    }

    /** Relationship id to resolved part path. */
    public static Map<String, String> rels(Map<String, byte[]> zip, String partPath) {
        Map<String, String> out = new LinkedHashMap<>();
        byte[] data = zip.get(relsPath(partPath));
        if (data == null) {
            return out;
        }
        try {
            org.w3c.dom.Document d = Xml.parse(data);
            for (org.w3c.dom.Element r : Xml.descendants(d, "Relationship")) {
                String mode = r.getAttribute("TargetMode");
                if ("External".equals(mode)) {
                    continue;
                }
                out.put(r.getAttribute("Id"), resolve(partPath, r.getAttribute("Target")));
            }
        } catch (Exception ignored) {
            // broken rels: treat as none
        }
        return out;
    }

    /** Small writer that keeps track of entries. */
    public static final class Writer implements AutoCloseable {
        private final ZipOutputStream zos;

        public Writer(OutputStream out) {
            zos = new ZipOutputStream(out);
        }

        public void put(String name, String text) throws IOException {
            put(name, text.getBytes(StandardCharsets.UTF_8));
        }

        public void put(String name, byte[] data) throws IOException {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(data);
            zos.closeEntry();
        }

        /** Uncompressed entry (EPUB requires "mimetype" to be stored). */
        public void putStored(String name, byte[] data) throws IOException {
            ZipEntry e = new ZipEntry(name);
            e.setMethod(ZipEntry.STORED);
            e.setSize(data.length);
            e.setCompressedSize(data.length);
            CRC32 crc = new CRC32();
            crc.update(data);
            e.setCrc(crc.getValue());
            zos.putNextEntry(e);
            zos.write(data);
            zos.closeEntry();
        }

        @Override
        public void close() throws IOException {
            zos.close();
        }
    }
}
