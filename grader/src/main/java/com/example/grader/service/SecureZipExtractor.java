package com.example.grader.service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Shared, bounded ZIP extraction for Golden build, capture and preflight. */
final class SecureZipExtractor {
    private SecureZipExtractor() {
    }

    static void extract(Path zipPath, Path destination, int maxEntries, long maxExpandedBytes)
            throws Exception {
        Files.createDirectories(destination);
        Path root = destination.toAbsolutePath().normalize();
        long expanded = 0;
        int count = 0;
        byte[] buffer = new byte[64 * 1024];

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > maxEntries) {
                    throw new IllegalArgumentException("Golden ZIP có quá nhiều file (tối đa "
                            + maxEntries + ")");
                }
                String entryName = entry.getName().replace('\\', '/');
                if (entryName.isBlank() || entryName.indexOf('\0') >= 0) {
                    throw new IllegalArgumentException("Golden ZIP chứa tên file không hợp lệ");
                }
                Path target = root.resolve(entryName).normalize();
                if (!target.startsWith(root)) {
                    throw new IllegalArgumentException("Golden ZIP chứa đường dẫn vượt thư mục đích");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }

                Files.createDirectories(target.getParent());
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream output = Files.newOutputStream(target,
                             StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING,
                             StandardOpenOption.WRITE)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        expanded += read;
                        if (expanded > maxExpandedBytes) {
                            throw new IllegalArgumentException("Golden ZIP giải nén vượt giới hạn "
                                    + maxExpandedBytes + " byte");
                        }
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
    }
}
