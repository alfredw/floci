package io.github.hectorvent.floci.core.storage;

/** Persistence is unavailable or uncertain. Restart after repairing the store. */
public class StoragePersistenceException extends RuntimeException {
    public StoragePersistenceException(Throwable cause) {
        super("Persistent storage is unavailable; repair the storage and restart", cause);
    }
}
