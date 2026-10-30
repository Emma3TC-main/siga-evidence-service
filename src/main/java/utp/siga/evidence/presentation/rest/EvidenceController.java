package utp.siga.evidence.presentation.rest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.UriUtils;
import utp.siga.evidence.application.dto.EvidenceContent;
import utp.siga.evidence.application.dto.EvidencePage;
import utp.siga.evidence.application.dto.EvidenceResponse;
import utp.siga.evidence.application.dto.UploadEvidenceCommand;
import utp.siga.evidence.application.port.in.EvidenceUseCase;
import utp.siga.evidence.domain.model.OwnerType;

@RestController
@RequestMapping("/api/v1/evidences")
public class EvidenceController {
    private final EvidenceUseCase useCase;

    public EvidenceController(EvidenceUseCase useCase) { this.useCase = useCase; }

    @PostMapping(consumes = "multipart/form-data")
    ResponseEntity<EvidenceResponse> upload(
            @RequestParam OwnerType ownerType,
            @RequestParam UUID ownerId,
            @RequestParam MultipartFile file,
            @RequestParam(required = false) String metadata,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationHeader,
            JwtAuthenticationToken authentication) {
        UUID correlationId = validOrNew(correlationHeader);
        UUID actorId = UUID.fromString(authentication.getToken().getSubject());
        String bearerToken = "Bearer " + authentication.getToken().getTokenValue();
        EvidenceResponse response = useCase.upload(new UploadEvidenceCommand(ownerType, ownerId, file, metadata,
                idempotencyKey, actorId, correlationId, bearerToken));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    EvidencePage list(@RequestParam OwnerType ownerType, @RequestParam UUID ownerId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            JwtAuthenticationToken authentication) {
        return useCase.list(ownerType, ownerId, page, size, bearer(authentication));
    }

    @GetMapping("/{id}")
    EvidenceResponse get(@PathVariable UUID id, JwtAuthenticationToken authentication) {
        return useCase.get(id, bearer(authentication));
    }

    @GetMapping("/{id}/content")
    ResponseEntity<StreamingResponseBody> content(@PathVariable UUID id, JwtAuthenticationToken authentication) {
        EvidenceContent content = useCase.content(id, bearer(authentication));
        StreamingResponseBody body = output -> {
            try (InputStream input = content.stream()) { input.transferTo(output); }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.parseMediaType(content.metadata().contentType()));
        headers.setContentLength(content.metadata().sizeBytes());
        headers.set(HttpHeaders.CONTENT_DISPOSITION, attachmentFilename(content.metadata().originalFilename()));
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    private static UUID validOrNew(String value) {
        try { return value == null ? UUID.randomUUID() : UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return UUID.randomUUID(); }
    }

    private static String bearer(JwtAuthenticationToken authentication) {
        return "Bearer " + authentication.getToken().getTokenValue();
    }

    private static String attachmentFilename(String filename) {
        String safe = filename.replaceAll("[\\r\\n\\\\/\\p{Cntrl}]", "_");
        String fallback = safe.replaceAll("[^\\x20-\\x7e]", "_").replace('"', '_');
        String attachment = ContentDisposition.attachment().filename(fallback).build().toString();
        return attachment + "; filename*=UTF-8''" + UriUtils.encode(safe, StandardCharsets.UTF_8);
    }
}
