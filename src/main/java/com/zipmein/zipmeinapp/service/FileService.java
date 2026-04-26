package com.zipmein.zipmeinapp.service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileService {

    public FileProcessResult processFiles(MultipartFile[] files, String password) throws IOException {
        Path baseTempDirectory = Files.createTempDirectory("zipmein-upload-");
        List<String> storedFilePaths = new ArrayList<>();

        if (files == null || files.length == 0) {
            Path zipPath = createZipFile(baseTempDirectory, storedFilePaths, password);
            return new FileProcessResult(zipPath, baseTempDirectory);
        }

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }

            String originalFilename = file.getOriginalFilename();
            String safeFilename = (originalFilename == null || originalFilename.isBlank())
                    ? "file-" + UUID.randomUUID()
                    : Path.of(originalFilename).getFileName().toString();

            Path targetPath = baseTempDirectory.resolve(safeFilename);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            storedFilePaths.add(targetPath.toAbsolutePath().toString());
        }

        Path zipPath = createZipFile(baseTempDirectory, storedFilePaths, password);
        return new FileProcessResult(zipPath, baseTempDirectory);
    }

    public void deleteTempDirectorySafely(Path tempDirectory) {
        if (tempDirectory == null || !Files.exists(tempDirectory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(tempDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup: skip files that cannot be deleted.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup: skip if traversal fails.
        }
    }

    private Path createZipFile(Path baseTempDirectory, List<String> storedFilePaths, String password) throws IOException {
        Path zipPath = baseTempDirectory.resolve("uploaded-files.zip");
        boolean hasPassword = password != null && !password.isBlank();

        ZipFile zipFile = hasPassword
                ? new ZipFile(zipPath.toFile(), Objects.requireNonNull(password).toCharArray())
                : new ZipFile(zipPath.toFile());

        ZipParameters zipParameters = new ZipParameters();
        zipParameters.setCompressionMethod(CompressionMethod.DEFLATE);
        zipParameters.setCompressionLevel(CompressionLevel.NORMAL);

        if (hasPassword) {
            zipParameters.setEncryptFiles(true);
            zipParameters.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD);
        }

        try {
            if (storedFilePaths.isEmpty()) {
                ZipParameters placeholderParameters = new ZipParameters(zipParameters);
                placeholderParameters.setFileNameInZip(".placeholder");
                zipFile.addStream(new ByteArrayInputStream(new byte[0]), placeholderParameters);
                zipFile.removeFile(".placeholder");
            } else {
                List<File> sourceFiles = new ArrayList<>();
                for (String storedFilePath : storedFilePaths) {
                    sourceFiles.add(Path.of(storedFilePath).toFile());
                }
                zipFile.addFiles(sourceFiles, zipParameters);
            }
        } catch (ZipException e) {
            throw new IOException("Failed to create zip file", e);
        }

        return zipPath.toAbsolutePath();
    }

    public static class FileProcessResult {
        private final Path zipPath;
        private final Path tempDirectory;

        public FileProcessResult(Path zipPath, Path tempDirectory) {
            this.zipPath = zipPath;
            this.tempDirectory = tempDirectory;
        }

        public Path getZipPath() {
            return zipPath;
        }

        public Path getTempDirectory() {
            return tempDirectory;
        }
    }
}
