package com.example.login;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Cada contexto de test usa su propio archivo SQLite temporal (dentro de target/, que se limpia y se ignora). */
public final class TestDb {

    private TestDb() {
    }

    public static String newUrl() {
        try {
            Path dir = Files.createDirectories(Path.of("target", "test-db"));
            return "jdbc:sqlite:" + dir.resolve(UUID.randomUUID() + ".db").toAbsolutePath()
                    + "?foreign_keys=true&busy_timeout=5000&journal_mode=WAL";
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
