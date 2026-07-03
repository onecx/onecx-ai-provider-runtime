package org.tkit.onecx.ai.provider.runtime.services.external;

import java.net.URI;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class ExternalAgentDiscoveryService {

    @Inject
    ObjectMapper objectMapper;

    public AgentCard fetchAgentCard(String discoveryUrl) {
        if (discoveryUrl == null || discoveryUrl.isBlank()) {
            return null;
        }
        try (Client client = ClientBuilder.newClient()) {
            String payload = client.target(URI.create(discoveryUrl))
                    .request(MediaType.APPLICATION_JSON_TYPE)
                    .get(String.class);
            JsonNode root = objectMapper.readTree(payload);
            return new AgentCard(text(root, "name"), text(root, "description"), invokeUrl(root, discoveryUrl));
        } catch (Exception ex) {
            log.warn("Unable to discover external agent at {}: {}: {}", discoveryUrl, ex.getClass().getSimpleName(),
                    ex.getMessage());
            log.debug("External agent discovery failure details for {}", discoveryUrl, ex);
            return null;
        }
    }

    private String invokeUrl(JsonNode root, String fallback) {
        String direct = text(root, "url");
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        String endpoint = text(root, "endpoint");
        if (endpoint != null && !endpoint.isBlank()) {
            return endpoint;
        }
        return fallback;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
