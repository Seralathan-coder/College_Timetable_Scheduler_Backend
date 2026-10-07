package com.erp.timetable.common.file;

import com.erp.timetable.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Local-disk file storage (Decision #3 — approved by user).
 *
 * <p>Files live under {@code app.upload.dir} (default {@code ./uploads}, overridable with
 * {@code UPLOAD_DIR}); the DB stores only the returned URL path ({@code /uploads/photos/...}),
 * never the file. Storage is deliberately simple — an S3/MinIO swap behind this same interface
 * is a one-class change if cloud storage is ever needed.
 *
 * <p>URL contract: the relative path returned ({@code /uploads/photos/<uuid>.<ext>}) is served
 * back by {@code WebConfig} resource handler under the app context-path.
 *
 * <p>SECURITY — the stored format decision is based ONLY on the file's binary signature
 * (magic bytes), never on the client-supplied {@code Content-Type} header or the original
 * filename — both are spoofable, and these files are served publicly from {@code /uploads/**}.
 * A renamed HTML/SVG/JS payload does not carry image magic bytes and is rejected. The stored
 * name is a server-generated UUID + an extension derived from the validated content, so no
 * client-supplied text is ever used in constructing the on-disk path (no path traversal).
 */
@Service
public class FileStorageService {

    private static final String PHOTO_SUB_DIR = "photos";
    private static final long MAX_BYTES = 5L * 1024 * 1024; // 5 MB (matches multipart config)
    private static final int HEADER_BYTES = 12;

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir;

    /**
     * Saves an uploaded profile photo and returns its public URL path.
     * Rejects empty files, files over 5 MB, and files that are not binary JPG/PNG/WebP.
     */
    public String storeProfilePhoto(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Uploaded file is empty");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException("Image exceeds the 5 MB maximum size");
        }

        ImageFormat format = sniffFormat(file);
        if (format == null) {
            throw new BusinessException("Only JPG, PNG and WebP images are allowed");
        }

        // File content is validated; no client-supplied name/content-type text is used here.
        String fileName = UUID.randomUUID() + "." + format.extension;
        Path target = root().resolve(PHOTO_SUB_DIR).resolve(fileName);

        try (InputStream in = file.getInputStream()) {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException("Failed to store the uploaded file");
        }
        return "/uploads/" + PHOTO_SUB_DIR + "/" + fileName;
    }

    /**
     * True when the file behind a stored URL path still exists on disk.
     * Non-local (external) URLs cannot be verified — treated as present so they pass through.
     */
    public boolean exists(String urlPath) {
        Path path = resolve(urlPath);
        return path == null || Files.exists(path);
    }

    /** Best-effort removal of the file behind a stored URL path (used when a photo is replaced). */
    public void delete(String urlPath) {
        Path path = resolve(urlPath);
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            // best-effort cleanup; a stale file is harmless
        }
    }

    /** Maps a public URL path to the on-disk file, or null if it is not a local /uploads path. */
    private Path resolve(String urlPath) {
        if (urlPath == null || urlPath.isBlank() || !urlPath.startsWith("/uploads/")) {
            return null;
        }
        String relative = urlPath.substring("/uploads/".length());
        return root().resolve(relative).normalize();
    }

    private Path root() {
        return Path.of(uploadDir).toAbsolutePath().normalize();
    }

    /**
     * Sniffs the first bytes of the payload and maps the detected binary signature to a
     * validated image format — or null. Content-Type and the original filename are IGNORED.
     */
    private ImageFormat sniffFormat(MultipartFile file) {
        byte[] header;
        try (InputStream in = file.getInputStream()) {
            header = in.readNBytes(HEADER_BYTES);
        } catch (IOException e) {
            throw new BusinessException("Failed to read the uploaded file");
        }

        // JPEG: FF D8 FF
        if (header.length >= 3
            && (header[0] & 0xFF) == 0xFF
            && (header[1] & 0xFF) == 0xD8
            && (header[2] & 0xFF) == 0xFF) {
            return ImageFormat.JPEG;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (header.length >= 8
            && (header[0] & 0xFF) == 0x89
            && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
            && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A) {
            return ImageFormat.PNG;
        }
        // WebP: RIFF....WEBP
        if (header.length >= 12
            && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
            && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return ImageFormat.WEBP;
        }
        return null;
    }

    /** Extension is a property of the validated format, never derived from client input. */
    private enum ImageFormat {
        JPEG("jpg"),
        PNG("png"),
        WEBP("webp");

        private final String extension;

        ImageFormat(String extension) {
            this.extension = extension;
        }
    }
}