package utp.siga.evidence.infrastructure.inventory;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import utp.siga.evidence.application.exception.EvidenceException;
import utp.siga.evidence.application.port.out.OwnerAccessPolicyPort;
import utp.siga.evidence.domain.model.OwnerType;

@Component
public class InventoryOwnerAccessAdapter implements OwnerAccessPolicyPort {
    private final RestClient inventory;

    public InventoryOwnerAccessAdapter(RestClient.Builder builder,
            @Value("${evidence.inventory.base-url}") String baseUrl) {
        this.inventory = builder.baseUrl(baseUrl).build();
    }

    @Override
    public void validateAccess(OwnerType ownerType, UUID ownerId, String bearerToken) {
        if (ownerType != OwnerType.MOVEMENT) {
            throw new EvidenceException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_OWNER_TYPE",
                    "ownerType no está soportado");
        }
        try {
            inventory.get().uri("/api/v1/movements/{id}", ownerId)
                    .header("Authorization", bearerToken).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden e) {
            throw new EvidenceException(HttpStatus.NOT_FOUND, "OWNER_NOT_FOUND",
                    "El movimiento no existe o no es accesible");
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 404 || e.getStatusCode().value() == 403) {
                throw new EvidenceException(HttpStatus.NOT_FOUND, "OWNER_NOT_FOUND",
                        "El movimiento no existe o no es accesible");
            }
            throw unavailable(e);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    private static EvidenceException unavailable(Throwable cause) {
        return new EvidenceException(HttpStatus.SERVICE_UNAVAILABLE, "OWNER_SERVICE_UNAVAILABLE",
                "Inventory no está disponible para validar el movimiento", cause);
    }
}
