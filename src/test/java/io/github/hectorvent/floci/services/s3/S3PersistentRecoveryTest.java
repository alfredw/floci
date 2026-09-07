package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class S3PersistentRecoveryTest {
    @TempDir Path directory;

    private S3Service open() {
        var buckets = new PersistentStorage<String, Bucket>(directory.resolve("buckets.json"),
                new TypeReference<Map<String, Bucket>>() {});
        var objects = new PersistentStorage<String, S3Object>(directory.resolve("objects.json"),
                new TypeReference<Map<String, S3Object>>() {});
        buckets.load();
        objects.load();
        return new S3Service(buckets, objects, directory.resolve("s3"), false);
    }

    @Test
    void failedOverwriteRecoversTheOriginalBodyAndEtag() throws Exception {
        var service = open();
        service.createBucket("bucket", "us-east-1");
        byte[] original = {0, -1, 2};
        String etag = service.putObject("bucket", "key", original, "application/octet-stream", Map.of()).getETag();
        Files.createDirectory(directory.resolve("objects.json.tmp"));
        assertThrows(RuntimeException.class,
                () -> service.putObject("bucket", "key", new byte[]{3, 4}, "text/plain", Map.of()));
        Files.delete(directory.resolve("objects.json.tmp"));
        var recovered = open().getObject("bucket", "key");
        assertArrayEquals(original, recovered.getData());
        assertEquals(etag, recovered.getETag());
    }

    @Test
    void acknowledgedReplacementAndDeletionSurviveReload() throws Exception {
        var service = open();
        service.createBucket("bucket", "us-east-1");
        service.putObject("bucket", "key", new byte[]{1}, "text/plain", Map.of());
        service.putObject("bucket", "key", new byte[]{2}, "text/plain", Map.of());
        var recovered = open();
        assertArrayEquals(new byte[]{2}, recovered.getObject("bucket", "key").getData());
        try (var stream = recovered.openObjectStream("bucket", "key", null)) {
            assertArrayEquals(new byte[]{2}, stream.readAllBytes());
        }
        recovered.deleteObject("bucket", "key");
        assertFalse(open().objectExists("bucket", "key"));
    }

    @Test
    void missingReferencedBodyIsAnErrorRatherThanAnAbsentObject() throws Exception {
        var service = open();
        service.createBucket("bucket", "us-east-1");
        service.putObject("bucket", "key", new byte[]{1}, "text/plain", Map.of());
        try (var paths = Files.walk(directory.resolve("s3"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                Files.delete(path);
            }
        }
        var recovered = open();
        assertThrows(io.github.hectorvent.floci.core.storage.StoragePersistenceException.class,
                () -> recovered.getObject("bucket", "key"));
        assertThrows(io.github.hectorvent.floci.core.storage.StoragePersistenceException.class,
                () -> recovered.objectExists("bucket", "key"));
    }

    @Test
    void aBodyWriteFailureFencesTheStoreWithoutCreatingAnObject() throws Exception {
        var service = open();
        service.createBucket("bucket", "us-east-1");
        Path account = directory.resolve("s3/.accounts/000000000000");
        Files.createDirectories(account);
        Path obstacle = account.resolve(".generations");
        Files.writeString(obstacle, "blocked");
        assertThrows(io.github.hectorvent.floci.core.storage.StoragePersistenceException.class,
                () -> service.putObject("bucket", "key", new byte[]{1}, "text/plain", Map.of()));
        assertThrows(io.github.hectorvent.floci.core.storage.StoragePersistenceException.class,
                () -> service.objectExists("bucket", "key"));
        Files.delete(obstacle);
        assertFalse(open().objectExists("bucket", "key"));
    }

    @Test
    void legacyBodiesRemainReadableAfterAnUpgradeAndFailedReplacement() throws Exception {
        var buckets = new PersistentStorage<String, Bucket>(directory.resolve("buckets.json"),
                new TypeReference<Map<String, Bucket>>() {});
        var objects = new PersistentStorage<String, S3Object>(directory.resolve("objects.json"),
                new TypeReference<Map<String, S3Object>>() {}) {
            @Override public boolean synchronousPersistence() { return false; }
        };
        var legacy = new S3Service(buckets, objects, directory.resolve("s3"), false);
        legacy.createBucket("bucket", "us-east-1");
        legacy.putObject("bucket", "key", new byte[]{7}, "text/plain", Map.of());
        var upgraded = open();
        assertArrayEquals(new byte[]{7}, upgraded.getObject("bucket", "key").getData());
        Files.createDirectory(directory.resolve("objects.json.tmp"));
        assertThrows(io.github.hectorvent.floci.core.storage.StoragePersistenceException.class,
                () -> upgraded.putObject("bucket", "key", new byte[]{9}, "text/plain", Map.of()));
        Files.delete(directory.resolve("objects.json.tmp"));
        assertArrayEquals(new byte[]{7}, open().getObject("bucket", "key").getData());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void conditionalWritesHaveOneWinnerAndRecoverItsBytes(boolean replacement) throws Exception {
        var service = open();
        service.createBucket("bucket", "us-east-1");
        String etag = replacement
                ? service.putObject("bucket", "key", new byte[]{0}, "text/plain", Map.of()).getETag() : null;
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(16)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 1; i <= 16; i++) {
                final int value = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        service.putObject("bucket", "key", new byte[]{(byte) value}, "text/plain", Map.of(),
                                replacement
                                        ? new io.github.hectorvent.floci.services.s3.model.PutObjectOptions().withIfMatch(etag)
                                        : new io.github.hectorvent.floci.services.s3.model.PutObjectOptions().withIfNoneMatch("*"));
                        return value;
                    } catch (io.github.hectorvent.floci.core.common.AwsException e) {
                        assertEquals(412, e.getHttpStatus());
                        return 0;
                    }
                }));
            }
            start.countDown();
            var winners = new java.util.ArrayList<Integer>();
            for (var future : futures) {
                int result = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
                if (result != 0) {
                    winners.add(result);
                }
            }
            assertEquals(1, winners.size());
            assertArrayEquals(new byte[]{winners.getFirst().byteValue()}, open().getObject("bucket", "key").getData());
        }
    }
}
