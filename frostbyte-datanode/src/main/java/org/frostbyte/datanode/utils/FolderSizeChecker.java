package org.frostbyte.datanode.utils;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicLong;

public class FolderSizeChecker
{
    public static float getFolderSize(Path folder) throws IOException {
        AtomicLong sizeInBytes = new AtomicLong(0);

        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                sizeInBytes.addAndGet(attrs.size());
                return FileVisitResult.CONTINUE;
            }
        });

        // Convert to GB as double
        return (float) (sizeInBytes.get() / (1024.0 * 1024 * 1024));
    }
}
