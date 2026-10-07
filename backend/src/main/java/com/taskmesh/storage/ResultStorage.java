package com.taskmesh.storage;

import com.taskmesh.config.TaskMeshProperties;
import com.taskmesh.controller.ApiException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Filesystem result storage rooted at TASKMESH_STORAGE_DIR. Only relative paths below
 * the storage root are served; absolute paths, ".." segments and symlink escapes are
 * rejected before any byte is read.
 */
@Component
public class ResultStorage {

    private final Path root;

    public ResultStorage(TaskMeshProperties properties) {
        Path configured = Path.of(properties.storageDir() == null ? "./data" : properties.storageDir());
        this.root = configured.toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create storage directory " + root, e);
        }
    }

    public Path root() {
        return root;
    }

    /**
     * Resolves a stored result path for serving, enforcing path-traversal protection.
     *
     * @throws ApiException.Validation when the path is malformed
     * @throws ApiException.NotFound   when the file does not exist
     */
    public Path resolveExisting(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new ApiException.Validation("Result has no file path");
        }
        if (relativePath.startsWith("/") || relativePath.contains("\\")) {
            throw new ApiException.Validation("Result file path must be relative: " + relativePath);
        }
        Path candidate = root.resolve(relativePath).normalize();
        if (!candidate.startsWith(root)) {
            throw new ApiException.Validation("Result file path escapes the storage root: " + relativePath);
        }
        // toRealPath resolves symlinks: a symlink pointing outside the root is rejected.
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException e) {
            throw new ApiException.NotFound("Result file not found in storage");
        }
        if (!real.startsWith(root) || !Files.isRegularFile(real)) {
            throw new ApiException.Validation("Result file path is not a regular file inside the storage root");
        }
        return real;
    }

    /**
     * Resolves a path for writing (parent directories are created), with the same
     * traversal guarantees as {@link #resolveExisting(String)}.
     */
    public Path resolveForWrite(String relativePath) {
        if (relativePath == null || relativePath.isBlank()
                || relativePath.startsWith("/") || relativePath.contains("\\")) {
            throw new ApiException.Validation("Result file path must be a relative path");
        }
        Path candidate = root.resolve(relativePath).normalize();
        if (!candidate.startsWith(root)) {
            throw new ApiException.Validation("Result file path escapes the storage root: " + relativePath);
        }
        try {
            Files.createDirectories(candidate.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create result directory " + candidate.getParent(), e);
        }
        return candidate;
    }
}
