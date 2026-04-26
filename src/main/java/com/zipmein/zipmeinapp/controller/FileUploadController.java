package com.zipmein.zipmeinapp.controller;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

import com.zipmein.zipmeinapp.service.FileService;
import com.zipmein.zipmeinapp.service.FileService.FileProcessResult;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class FileUploadController {
    private static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;
    private final FileService fileService;

    public FileUploadController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload")
    public ResponseEntity<Resource> uploadFiles(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "password", required = false) String password) throws IOException {
        validateFiles(files);

        FileProcessResult result = fileService.processFiles(files, password);
        Path zipFilePath = result.getZipPath();
        InputStream zipInputStream = Files.newInputStream(zipFilePath);
        Resource resource = new InputStreamResource(new CleanupOnCloseInputStream(
                zipInputStream,
                result.getTempDirectory(),
                fileService));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zipFilePath.getFileName() + "\"")
                .contentType(Objects.requireNonNull(MediaType.APPLICATION_OCTET_STREAM))
                .body(resource);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleValidationException(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }

    private void validateFiles(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("At least one file is required.");
        }

        if (Arrays.stream(files).anyMatch(file -> file == null || file.isEmpty())) {
            throw new IllegalArgumentException("Empty file uploads are not allowed.");
        }

        for (MultipartFile file : files) {
            if (file.getSize() > MAX_FILE_SIZE_BYTES) {
                throw new IllegalArgumentException(
                        "File '" + file.getOriginalFilename() + "' exceeds the 50MB limit.");
            }
        }
    }

    private static class CleanupOnCloseInputStream extends FilterInputStream {
        private final Path tempDirectory;
        private final FileService fileService;
        private boolean closed;

        protected CleanupOnCloseInputStream(InputStream in, Path tempDirectory, FileService fileService) {
            super(in);
            this.tempDirectory = tempDirectory;
            this.fileService = fileService;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }

            closed = true;
            try {
                super.close();
            } finally {
                fileService.deleteTempDirectorySafely(tempDirectory);
            }
        }
    }
}
