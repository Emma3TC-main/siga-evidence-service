package utp.siga.evidence.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import utp.siga.evidence.application.exception.EvidenceException;

@Component
public class MetadataValidationService {
    private static final int MAX_BYTES = 16 * 1024;
    private static final Set<String> SECRET_KEYS = Set.of("password", "passwd", "secret", "token", "authorization",
            "credential", "api_key", "apikey", "private_key", "access_key", "refresh_token");
    private static final Pattern BASE64 = Pattern.compile("^[A-Za-z0-9+/]{256,}={0,2}$");
    private final ObjectMapper mapper;

    public MetadataValidationService(ObjectMapper mapper) { this.mapper = mapper; }

    public ObjectNode validate(String raw) {
        if (raw == null || raw.isBlank()) return mapper.createObjectNode();
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid("La metadata supera 16 KiB");
        try {
            JsonNode root = mapper.readTree(raw);
            if (!(root instanceof ObjectNode object)) throw invalid("La metadata debe ser un objeto JSON");
            validateNode(object, 1);
            if (mapper.writeValueAsBytes(object).length > MAX_BYTES) throw invalid("La metadata supera 16 KiB");
            return object;
        } catch (JsonProcessingException e) {
            throw invalid("La metadata no es JSON válido");
        }
    }

    private void validateNode(JsonNode node, int depth) {
        if (depth > 5) throw invalid("La metadata supera la profundidad máxima");
        if (node.isObject()) {
            if (node.size() > 50) throw invalid("La metadata supera 50 propiedades por objeto");
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getKey().length() > 64) throw invalid("Una clave de metadata supera 64 caracteres");
                String key = field.getKey().toLowerCase().replace('-', '_');
                if (SECRET_KEYS.contains(key)) throw invalid("La metadata contiene un campo sensible no permitido");
                validateNode(field.getValue(), depth + 1);
            }
        } else if (node.isArray()) {
            if (node.size() > 50) throw invalid("La metadata supera 50 elementos por arreglo");
            for (JsonNode item : node) validateNode(item, depth + 1);
        } else if (node.isTextual()) {
            String value = node.textValue();
            if (value.length() > 1024) throw invalid("Un valor de metadata supera 1024 caracteres");
            if (value.startsWith("data:") || BASE64.matcher(value).matches()) {
                throw invalid("La metadata no admite contenido binario o base64");
            }
        }
    }

    private static EvidenceException invalid(String message) {
        return new EvidenceException(HttpStatus.BAD_REQUEST, "INVALID_METADATA", message);
    }
}
