package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class PersistentProcessRecoveryTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"before-rename", "after-commit", "after-acknowledgement"})
    void abruptProcessDeathRecoversTheCommittedSnapshot(String boundary) throws Exception {
        Path path = directory.resolve("state.json");
        TypeReference<Map<String, String>> type = new TypeReference<>() {};
        new PersistentStorage<String, String>(path, type).put("key", "old");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), CrashWriter.class.getName(),
                path.toString(), boundary).inheritIO().start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(137, process.exitValue());
        } finally {
            process.destroyForcibly();
        }
        var recovered = new PersistentStorage<String, String>(path, type);
        recovered.load();
        assertEquals(boundary.equals("before-rename") ? "old" : "new", recovered.get("key").orElseThrow());
    }

    public static class CrashWriter {
        public static void main(String[] args) {
            String boundary = args[1];
            DurableFiles files = new DurableFiles() {
                @Override protected void move(Path source, Path target) throws IOException {
                    if (boundary.equals("before-rename")) {
                        Runtime.getRuntime().halt(137);
                    }
                    super.move(source, target);
                }
                @Override protected void syncDirectory(Path path) throws IOException {
                    super.syncDirectory(path);
                    if (boundary.equals("after-commit")) {
                        Runtime.getRuntime().halt(137);
                    }
                }
            };
            var storage = new PersistentStorage<String, String>(Path.of(args[0]),
                    new TypeReference<Map<String, String>>() {}, files);
            storage.load();
            storage.put("key", "new");
            Runtime.getRuntime().halt(137);
        }
    }
}
