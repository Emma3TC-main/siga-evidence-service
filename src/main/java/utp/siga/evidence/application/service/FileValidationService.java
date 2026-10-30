package utp.siga.evidence.application.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import utp.siga.evidence.application.exception.EvidenceException;

@Component
public class FileValidationService {
    private static final Map<String, String> TYPES = Map.of(
            "pdf", "application/pdf", "jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png");
    private final long imageMaxBytes;
    private final long pdfMaxBytes;

    public FileValidationService(
            @Value("${evidence.storage.image-max-bytes}") long imageMaxBytes,
            @Value("${evidence.storage.pdf-max-bytes}") long pdfMaxBytes) {
        this.imageMaxBytes = imageMaxBytes;
        this.pdfMaxBytes = pdfMaxBytes;
    }

    public ValidatedFile validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_FILE", "El archivo es obligatorio");
        }
        String filename = sanitize(file.getOriginalFilename());
        String extension = extension(filename);
        String expectedType = TYPES.get(extension);
        if (expectedType == null) {
            throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Extensión de archivo no permitida");
        }
        String declaredType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!expectedType.equals(declaredType)) {
            throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "La extensión y el tipo MIME no coinciden");
        }
        long max = extension.equals("pdf") ? pdfMaxBytes : imageMaxBytes;
        if (file.getSize() > max) {
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "El archivo supera el límite permitido");
        }
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0) {
                throw error(HttpStatus.BAD_REQUEST, "INVALID_FILE", "El archivo está vacío");
            }
            if (bytes.length > max) {
                throw error(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "El archivo supera el límite permitido");
            }
            if (!matchesMagic(bytes, expectedType)) {
                throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "El contenido no coincide con el tipo declarado");
            }
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new ValidatedFile(bytes, filename, expectedType, extension.equals("jpeg") ? "jpg" : extension, sha);
        } catch (IOException e) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_FILE", "No se pudo leer el archivo", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    static String sanitize(String raw) {
        String leaf = raw == null ? "archivo" : raw.replace('\\', '/');
        leaf = leaf.substring(leaf.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        leaf = leaf.replaceAll("[^\\p{L}\\p{N}._() -]", "_");
        if (leaf.isBlank() || leaf.equals(".") || leaf.equals("..")) leaf = "archivo";
        if (leaf.length() > 255) leaf = leaf.substring(0, 255);
        return leaf;
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean matchesMagic(byte[] b, String type) {
        return switch (type) {
            case "application/pdf" -> starts(b, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            case "image/png" -> starts(b, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            case "image/jpeg" -> b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff;
            default -> false;
        };
    }

    private static boolean starts(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    private static EvidenceException error(HttpStatus status, String code, String message) {
        return new EvidenceException(status, code, message);
    }
    private static EvidenceException error(HttpStatus status, String code, String message, Throwable cause) {
        return new EvidenceException(status, code, message, cause);
    }
}
