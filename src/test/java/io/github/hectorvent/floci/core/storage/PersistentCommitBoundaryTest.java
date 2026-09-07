package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PersistentCommitBoundaryTest {
    @TempDir Path directory;
    enum Boundary { FILE_SYNC, RENAME, DIRECTORY_SYNC }

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void uncertainCommitsFenceEveryAccessAndRecoverACompleteSnapshot(Boundary boundary) {
        Path path = directory.resolve("store.json");
        TypeReference<Map<String, String>> type = new TypeReference<>() {};
        var original = new PersistentStorage<String, String>(path, type);
        original.put("key", "old");
        var files = new DurableFiles() {
            @Override protected void syncFile(FileChannel channel) throws IOException {
                if (boundary == Boundary.FILE_SYNC) {
                    throw new IOException("Injected file sync failure");
                }
                super.syncFile(channel);
            }
            @Override protected void move(Path source, Path target) throws IOException {
                if (boundary == Boundary.RENAME) {
                    throw new IOException("Injected rename failure");
                }
                super.move(source, target);
            }
            @Override protected void syncDirectory(Path parent) throws IOException {
                if (boundary == Boundary.DIRECTORY_SYNC) {
                    throw new IOException("Injected directory sync failure");
                }
                super.syncDirectory(parent);
            }
        };
        var storage = new PersistentStorage<String, String>(path, type, files);
        storage.load();
        assertThrows(StoragePersistenceException.class, () -> storage.put("key", "new"));
        assertThrows(StoragePersistenceException.class, () -> storage.get("key"));
        assertThrows(StoragePersistenceException.class, () -> storage.put("other", "value"));
        assertThrows(StoragePersistenceException.class, () -> storage.delete("key"));
        assertThrows(StoragePersistenceException.class, () -> storage.scan(k -> true));
        assertThrows(StoragePersistenceException.class, storage::keys);
        assertThrows(StoragePersistenceException.class, storage::clear);
        assertThrows(StoragePersistenceException.class, storage::flush);
        assertThrows(StoragePersistenceException.class, storage::load);
        var recovered = new PersistentStorage<String, String>(path, type);
        recovered.load();
        assertEquals(boundary == Boundary.DIRECTORY_SYNC ? "new" : "old", recovered.get("key").orElseThrow());
        assertEquals(1, recovered.keys().size());
    }

    @Test
    void failedClearPreservesCommittedEntries() throws Exception {
        Path path = directory.resolve("store.json");
        TypeReference<Map<String, String>> type = new TypeReference<>() {};
        var storage = new PersistentStorage<String, String>(path, type);
        storage.put("key", "old");
        java.nio.file.Files.createDirectory(directory.resolve("store.json.tmp"));
        assertThrows(StoragePersistenceException.class, storage::clear);
        var recovered = new PersistentStorage<String, String>(path, type);
        recovered.load();
        assertEquals("old", recovered.get("key").orElseThrow());
    }
}
