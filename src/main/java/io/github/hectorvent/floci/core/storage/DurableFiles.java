package io.github.hectorvent.floci.core.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Local-filesystem commit boundary, without non-atomic or unsynced fallbacks. */
public class DurableFiles {
    public void replace(Path path, byte[] bytes) throws IOException {
        Path absolute = path.toAbsolutePath();
        createDirectories(absolute.getParent());
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            syncFile(channel);
        }
        move(temporary, absolute);
        // Ancestors may have been created by the service before this call.
        for (Path directory = absolute.getParent(); directory != null; directory = directory.getParent()) {
            syncDirectory(directory);
        }
    }

    private void createDirectories(Path directory) throws IOException {
        if (Files.isDirectory(directory)) {
            return;
        }
        createDirectories(directory.getParent());
        try {
            Files.createDirectory(directory);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            if (!Files.isDirectory(directory)) {
                throw e;
            }
        }
        syncDirectory(directory.getParent());
    }

    protected void syncFile(FileChannel channel) throws IOException {
        channel.force(true);
    }

    protected void move(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    protected void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
