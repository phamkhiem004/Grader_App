package com.example.grader.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecureZipExtractorTest {
    @TempDir Path temp;

    @Test
    void extractsAValidArchiveInsideDestination() throws Exception {
        Path zip = archive(Map.of("app/lib/main.dart", "void main() {}"));
        Path destination = temp.resolve("valid");

        SecureZipExtractor.extract(zip, destination, 10, 1_024);

        assertEquals("void main() {}",
                Files.readString(destination.resolve("app/lib/main.dart"), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsTraversalBeforeWritingOutsideDestination() throws Exception {
        Path zip = archive(Map.of("../outside.txt", "blocked"));

        assertThrows(IllegalArgumentException.class,
                () -> SecureZipExtractor.extract(zip, temp.resolve("target"), 10, 1_024));
    }

    @Test
    void countsRealExpandedBytesInsteadOfTrustingZipMetadata() throws Exception {
        Path zip = archive(Map.of("large.txt", "1234567890"));

        assertThrows(IllegalArgumentException.class,
                () -> SecureZipExtractor.extract(zip, temp.resolve("bounded"), 10, 5));
    }

    @Test
    void rejectsArchivesWithTooManyEntries() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("one.txt", "1");
        files.put("two.txt", "2");
        Path zip = archive(files);

        assertThrows(IllegalArgumentException.class,
                () -> SecureZipExtractor.extract(zip, temp.resolve("entries"), 1, 1_024));
    }

    private Path archive(Map<String, String> files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        Path path = temp.resolve("archive-" + files.hashCode() + ".zip");
        Files.write(path, bytes.toByteArray());
        return path;
    }
}
