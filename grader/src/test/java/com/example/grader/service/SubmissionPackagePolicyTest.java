package com.example.grader.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmissionPackagePolicyTest {

    @TempDir
    Path temp;

    @Test
    void externalPackageIsScoredAsStudentFaultNotASystemIncident() throws Exception {
        Path lib = Files.createDirectories(temp.resolve("lib"));
        Files.writeString(lib.resolve("main.dart"),
                "import 'package:http/http.dart';\nvoid main() {}\n");
        SubmissionPackagePolicy.Policy policy = new SubmissionPackagePolicy.Policy(
                Set.of("flutter"), Set.of("exam_project"), true);

        GradingDiagnosticException error = assertThrows(GradingDiagnosticException.class,
                () -> new SubmissionPackagePolicy().validateAndNormalize(lib, policy));

        assertEquals("EXTERNAL_PACKAGE", error.code());
        assertEquals(GradingDiagnosticException.Origin.STUDENT, error.origin());
        // Khung có sẵn pubspec ⇒ thêm package ngoài là sai hướng dẫn: ghi 0 điểm, KHÔNG báo
        // lên cột sự cố hệ thống của người chấm.
        assertFalse(error.manualReview());
    }

    @Test
    void utf8BomCannotHideExternalPackageOnFirstLine() throws Exception {
        Path lib = Files.createDirectories(temp.resolve("lib"));
        Files.writeString(lib.resolve("main.dart"),
                "\uFEFFimport 'package:http/http.dart';\nvoid main() {}\n");
        SubmissionPackagePolicy.Policy policy = new SubmissionPackagePolicy.Policy(
                Set.of("flutter"), Set.of("exam_project"), true);

        GradingDiagnosticException error = assertThrows(GradingDiagnosticException.class,
                () -> new SubmissionPackagePolicy().validateAndNormalize(lib, policy));

        assertEquals("EXTERNAL_PACKAGE", error.code());
        assertEquals("DEPENDENCY_PREFLIGHT", error.stage());
        assertTrue(error.teacherMessage().contains("http"));
    }

    @Test
    void localStarterPackageIsRewrittenWithoutBeingTreatedAsExternal() throws Exception {
        Path lib = Files.createDirectories(temp.resolve("lib"));
        Files.createDirectories(lib.resolve("models"));
        Files.writeString(lib.resolve("models/person.dart"), "class Person {}\n");
        Path main = lib.resolve("main.dart");
        Files.writeString(main,
                "import 'package:starter_exam/models/person.dart';\nvoid main() {}\n");
        SubmissionPackagePolicy.Policy policy = new SubmissionPackagePolicy.Policy(
                Set.of("flutter"), Set.of("exam_project", "starter_exam"), true);

        new SubmissionPackagePolicy().validateAndNormalize(lib, policy);

        assertTrue(Files.readString(main).contains("package:exam_project/models/person.dart"));
    }

    @Test
    void commentedPackageExampleIsNotTreatedAsExternal() throws Exception {
        Path lib = Files.createDirectories(temp.resolve("lib"));
        Files.writeString(lib.resolve("main.dart"), """
                // import 'package:http/http.dart';
                /*
                export 'package:dio/dio.dart';
                */
                import 'package:flutter/material.dart';
                void main() {}
                """);
        SubmissionPackagePolicy.Policy policy = new SubmissionPackagePolicy.Policy(
                Set.of("flutter"), Set.of("exam_project"), true);

        new SubmissionPackagePolicy().validateAndNormalize(lib, policy);

        assertTrue(Files.readString(lib.resolve("main.dart")).contains("package:http/http.dart"),
                "preflight must not rewrite or reject commented documentation");
    }
}
