package com.xianyusmart.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaywrightManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void cleanTempFilesPreservesJavaDriverAndDeletesExpiredBrowserArtifacts() throws Exception {
        Path driver = Files.createDirectory(tempDir.resolve("playwright-java-driver"));
        Files.writeString(driver.resolve("node"), "driver");
        Path profile = Files.createDirectory(tempDir.resolve("playwright_chromiumdev_profile-old"));
        Files.writeString(profile.resolve("state"), "temporary");
        Path unrelated = Files.createDirectory(tempDir.resolve("chromium-user-data"));

        FileTime expired = FileTime.from(Instant.now().minusSeconds(2 * 60 * 60));
        Files.setLastModifiedTime(driver, expired);
        Files.setLastModifiedTime(profile, expired);
        Files.setLastModifiedTime(unrelated, expired);

        String originalTmpDir = System.getProperty("java.io.tmpdir");
        try {
            System.setProperty("java.io.tmpdir", tempDir.toString());
            new PlaywrightManager().cleanTempFiles();
        } finally {
            if (originalTmpDir == null) {
                System.clearProperty("java.io.tmpdir");
            } else {
                System.setProperty("java.io.tmpdir", originalTmpDir);
            }
        }

        assertTrue(Files.exists(driver.resolve("node")));
        assertFalse(Files.exists(profile));
        assertTrue(Files.exists(unrelated));
    }

    @Test
    void javaDriverDirectoryIsNeverACleanupCandidate() {
        assertFalse(PlaywrightManager.isCleanableTempEntry("playwright-java-123456"));
        assertTrue(PlaywrightManager.isCleanableTempEntry("playwright-artifacts-123456"));
        assertTrue(PlaywrightManager.isCleanableTempEntry(".org.chromium.Chromium.123456"));
    }

    @Test
    void initCreatesConfiguredDriverParentDirectory() {
        Path driverTmpDir = tempDir.resolve("data/pw-driver");
        String originalDriverTmpDir = System.getProperty("playwright.driver.tmpdir");
        try {
            System.setProperty("playwright.driver.tmpdir", driverTmpDir.toString());
            new PlaywrightManager().init();
        } finally {
            if (originalDriverTmpDir == null) {
                System.clearProperty("playwright.driver.tmpdir");
            } else {
                System.setProperty("playwright.driver.tmpdir", originalDriverTmpDir);
            }
        }

        assertTrue(Files.isDirectory(driverTmpDir));
    }
}
