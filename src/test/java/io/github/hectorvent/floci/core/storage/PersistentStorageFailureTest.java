package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PersistentStorageFailureTest {
    @TempDir Path directory;

    private PersistentStorage<String, String> open(Path path) {
        var storage = new PersistentStorage<String, String>(path, new TypeReference<Map<String, String>>() {});
        storage.load();
        return storage;
    }

    @Test
    void failedOverwriteRequiresRestartAndPreservesCommittedValue() throws Exception {
        Path path = directory.resolve("objects.json");
        var storage = open(path);
        storage.put("key", "old");
        Files.createDirectory(directory.resolve("objects.json.tmp"));
        assertThrows(RuntimeException.class, () -> storage.put("key", "new"));
        assertThrows(RuntimeException.class, () -> storage.get("key"));
        assertThrows(RuntimeException.class, storage::flush);
        Files.delete(directory.resolve("objects.json.tmp"));
        assertEquals("old", open(path).get("key").orElseThrow());
    }

    @Test
    void failedDeleteDoesNotRemoveCommittedValue() throws Exception {
        Path path = directory.resolve("objects.json");
        var storage = open(path);
        storage.put("key", "old");
        Files.createDirectory(directory.resolve("objects.json.tmp"));
        assertThrows(RuntimeException.class, () -> storage.delete("key"));
        assertThrows(RuntimeException.class, storage::keys);
        assertEquals("old", open(path).get("key").orElseThrow());
    }

    @Test
    void corruptMetadataPreventsStartupAndPreservesEvidence() throws Exception {
        Path path = directory.resolve("objects.json");
        Files.writeString(path, "broken json");
        assertThrows(RuntimeException.class, () -> open(path));
        assertEquals("broken json", Files.readString(path));
    }
}
