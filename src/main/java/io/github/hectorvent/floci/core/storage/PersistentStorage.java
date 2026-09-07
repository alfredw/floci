package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Synchronous snapshots. Failed commits fence this instance until restart. */
public class PersistentStorage<K, V> implements StorageBackend<K, V> {
    private Map<K, V> store = new HashMap<>();
    private final Path filePath;
    private final ObjectMapper mapper;
    private final TypeReference<Map<K, V>> type;
    private final DurableFiles files;
    private StoragePersistenceException failure;

    public PersistentStorage(Path path, TypeReference<Map<K, V>> type) {
        this(path, type, new DurableFiles());
    }

    PersistentStorage(Path path, TypeReference<Map<K, V>> type, DurableFiles files) {
        this.filePath = path;
        this.type = type;
        this.files = files;
        mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public boolean synchronousPersistence() {
        return true;
    }

    @Override
    public synchronized StoragePersistenceException persistenceFailure(IOException cause) {
        if (failure == null) {
            failure = new StoragePersistenceException(cause);
        }
        return failure;
    }

    private void checkHealthy() {
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public synchronized void put(K key, V value) {
        checkHealthy();
        Map<K, V> candidate = new HashMap<>(store);
        candidate.put(key, value);
        commit(candidate);
    }

    @Override
    public synchronized Optional<V> get(K key) {
        checkHealthy();
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public synchronized void delete(K key) {
        checkHealthy();
        Map<K, V> candidate = new HashMap<>(store);
        candidate.remove(key);
        commit(candidate);
    }

    @Override
    public synchronized List<V> scan(Predicate<K> filter) {
        checkHealthy();
        return store.entrySet().stream().filter(e -> filter.test(e.getKey()))
                .map(Map.Entry::getValue).collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    public synchronized Set<K> keys() {
        checkHealthy();
        return Set.copyOf(store.keySet());
    }

    @Override
    public synchronized void flush() {
        checkHealthy();
        commit(new HashMap<>(store));
    }

    @Override
    public synchronized void load() {
        checkHealthy();
        if (Files.notExists(filePath)) {
            return;
        }
        try {
            Map<K, V> loaded = mapper.readValue(filePath.toFile(), type);
            if (loaded == null) {
                throw new IOException("Persistent snapshot must be an object");
            }
            store = loaded;
        } catch (IOException e) {
            throw persistenceFailure(e);
        }
    }

    @Override
    public synchronized void clear() {
        checkHealthy();
        commit(new HashMap<>());
    }

    private void commit(Map<K, V> candidate) {
        try {
            files.replace(filePath, mapper.writeValueAsBytes(candidate));
            store = candidate;
        } catch (IOException e) {
            throw persistenceFailure(e);
        }
    }
}
