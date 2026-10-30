package utp.siga.evidence.infrastructure.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withForbiddenRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import utp.siga.evidence.application.exception.EvidenceException;
import utp.siga.evidence.domain.model.OwnerType;

class InventoryOwnerAccessAdapterTest {
    private MockRestServiceServer server;
    private InventoryOwnerAccessAdapter adapter;
    private UUID ownerId;

    @BeforeEach void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new InventoryOwnerAccessAdapter(builder, "http://inventory.test");
        ownerId = UUID.randomUUID();
    }

    @Test void forwardsOriginalBearerToCanonicalMovementEndpoint() {
        server.expect(requestTo("http://inventory.test/api/v1/movements/" + ownerId))
                .andExpect(header("Authorization", "Bearer original-token")).andRespond(withSuccess());
        adapter.validateAccess(OwnerType.MOVEMENT, ownerId, "Bearer original-token");
        server.verify();
    }

    @Test void missingMovementIsHiddenAs404() {
        server.expect(requestTo("http://inventory.test/api/v1/movements/" + ownerId)).andRespond(withResourceNotFound());
        assertStatus(404);
    }

    @Test void inaccessibleMovementIsHiddenAs404() {
        server.expect(requestTo("http://inventory.test/api/v1/movements/" + ownerId)).andRespond(withForbiddenRequest());
        assertStatus(404);
    }

    @Test void inventoryFailureIs503() {
        server.expect(requestTo("http://inventory.test/api/v1/movements/" + ownerId)).andRespond(withServerError());
        assertStatus(503);
    }

    private void assertStatus(int expected) {
        assertThatThrownBy(() -> adapter.validateAccess(OwnerType.MOVEMENT, ownerId, "Bearer test"))
                .isInstanceOfSatisfying(EvidenceException.class,
                        exception -> assertThat(exception.status().value()).isEqualTo(expected));
        server.verify();
    }
}
