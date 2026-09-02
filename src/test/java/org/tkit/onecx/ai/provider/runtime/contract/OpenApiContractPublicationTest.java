package org.tkit.onecx.ai.provider.runtime.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Validates that the runtime OpenAPI contract published as the
 * {@code runtime-contract}
 * classifier artifact (see {@code pom.xml} / build-helper
 * {@code attach-artifact}) exists, is
 * structurally complete, and is wired into the release lifecycle so downstream
 * modules can resolve
 * an immutable, versioned artifact instead of reading a moving branch.
 *
 * <p>
 * This is a plain JUnit test (no Quarkus bootstrap, no containers) so it runs
 * in any environment
 * and guards two invariants: the released contract artifact is a well-formed,
 * self-contained
 * OpenAPI document carrying the typed text-dispatch and provider-health
 * operations consumers depend
 * on, and the Maven build actually attaches it with the exact coordinates
 * consumers resolve by.
 */
class OpenApiContractPublicationTest {

    private static final Path CONTRACT = Paths.get("src/main/openapi/openapi-runtime.yaml");
    private static final Path POM = Paths.get("pom.xml");

    @Test
    void contractFile_existsIsNonEmptyAndIsWellFormedOpenApi() throws IOException {
        assertThat(CONTRACT)
                .as("the runtime contract source file must exist for publication")
                .isRegularFile();

        assertThat(CONTRACT.toFile().length())
                .as("the runtime contract source file must be non-empty for publication")
                .isGreaterThan(0L);

        @SuppressWarnings("unchecked")
        Map<String, Object> spec = loadContract();

        assertThat(spec)
                .as("the contract must parse into a YAML mapping")
                .isNotNull()
                .isNotEmpty();

        Object openapiVersion = spec.get("openapi");
        assertThat(openapiVersion)
                .as("the document must declare an OpenAPI version")
                .isNotNull()
                .isInstanceOf(String.class);
        assertThat((String) openapiVersion)
                .as("the document must target OpenAPI 3.x so it can be served and consumed")
                .startsWith("3.");

        assertThat(info())
                .as("the document must declare an info section for consumer identity")
                .isNotNull();
        assertThat(info().get("title")).as("info.title must be present").isNotNull();
        assertThat(String.valueOf(info().get("title"))).isNotBlank();
    }

    @Test
    void contract_carriesImmutableVersionIdentity() throws IOException {
        Map<String, Object> info = info();

        assertThat(info).as("info section must be present").isNotNull();

        // The immutable identity field: consumers resolve the artifact by Maven version, but the
        // spec itself must carry a non-empty version so the released artifact is self-describing.
        Object version = info.get("version");
        assertThat(version)
                .as("info.version must be present (immutable identity of the contract)")
                .isNotNull();
        assertThat(String.valueOf(version)).isNotBlank();
    }

    @Test
    void pom_declaresContractArtifactAttachment() throws Exception {
        Path pomPath = resolveProjectRoot().resolve(POM);
        assertThat(pomPath).as("pom.xml must be present to inspect the publication wiring").isRegularFile();

        Document pom = parseXml(Files.readString(pomPath, StandardCharsets.UTF_8));

        // Locate the build-helper plugin declaration.
        Element plugin = findBuildHelperPlugin(pom).orElseThrow();
        assertThat(firstText(plugin, "groupId"))
                .as("build-helper-maven-plugin groupId")
                .isEqualTo("org.codehaus.mojo");

        // The execution must bind the attach-artifact goal to the package phase.
        Element execution = firstExecution(plugin).orElseThrow();
        assertThat(execution).as("build-helper-maven-plugin must declare an execution").isNotNull();
        assertThat(childText(execution, "phase"))
                .as("the attach-artifact execution must be bound to the package phase")
                .isEqualTo("package");
        assertThat(goalNames(execution))
                .as("the execution must run the attach-artifact goal")
                .contains("attach-artifact");

        // The attached artifact must carry the exact coordinates consumers resolve by.
        Element artifact = firstChild(execution, "configuration", "artifacts", "artifact").orElseThrow();
        assertThat(childText(artifact, "file"))
                .as("the attached contract file path")
                .isEqualTo("${project.basedir}/src/main/openapi/openapi-runtime.yaml");
        assertThat(childText(artifact, "type"))
                .as("the attached contract type")
                .isEqualTo("yaml");
        assertThat(childText(artifact, "classifier"))
                .as("the attached contract classifier")
                .isEqualTo("runtime-contract");
    }

    @Test
    void contract_declaresTextDispatchOperation() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = paths();

        @SuppressWarnings("unchecked")
        Map<String, Object> chatPath = (Map<String, Object>) paths.get("/internal/runtime/chat");
        assertThat(chatPath).as("the text dispatch path /internal/runtime/chat must be declared").isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> post = (Map<String, Object>) chatPath.get("post");
        assertThat(post).as("the text dispatch path must expose a POST operation with a stable operationId")
                .isNotNull()
                .containsEntry("operationId", "chat");
        assertThat(requestSchemaRef(post)).isEqualTo("#/components/schemas/RuntimeChatRequest");
        assertThat(responseSchemaRef(post, "200")).isEqualTo("#/components/schemas/RuntimeChatResponse");
    }

    @Test
    void contract_declaresProviderHealthOperation() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = paths();

        @SuppressWarnings("unchecked")
        Map<String, Object> healthPath = (Map<String, Object>) paths.get("/internal/runtime/provider-health");
        assertThat(healthPath).as("the provider-health path must be declared").isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> post = (Map<String, Object>) healthPath.get("post");
        assertThat(post).as("the provider-health path must expose a POST operation with a stable operationId")
                .isNotNull()
                .containsEntry("operationId", "getProviderHealthStatus");
        assertThat(requestSchemaRef(post)).isEqualTo("#/components/schemas/ProviderHealthRequest");
        assertThat(responseSchemaRef(post, "200")).isEqualTo("#/components/schemas/ProviderHealthStatus");
    }

    @Test
    void contract_declaresTypedDispatchSchemas() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> schemas = schemas();

        // The schemas consumers' generated clients are built from must all be present.
        assertThat(schemas).containsKeys(
                "RuntimeChatRequest",
                "RuntimeChatResponse",
                "ProviderHealthRequest",
                "ProviderHealthStatus",
                "ChatRequest",
                "ChatMessage",
                "AgentSnapshot",
                "ProviderSnapshot");

        // The typed text-dispatch request must keep its required fields, so existing dispatch
        // requests remain valid when the contract is republished.
        @SuppressWarnings("unchecked")
        Map<String, Object> chatRequest = (Map<String, Object>) schemas.get("RuntimeChatRequest");
        @SuppressWarnings("unchecked")
        List<String> chatRequestRequired = (List<String>) chatRequest.get("required");
        assertThat(chatRequestRequired)
                .as("RuntimeChatRequest required fields define the typed dispatch contract")
                .containsExactlyInAnyOrder("chatRequest", "rootAgent");

        // The provider-health status must keep its required status enum.
        @SuppressWarnings("unchecked")
        Map<String, Object> healthStatus = (Map<String, Object>) schemas.get("ProviderHealthStatus");
        @SuppressWarnings("unchecked")
        Map<String, Object> statusProps = (Map<String, Object>) healthStatus.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> statusField = (Map<String, Object>) statusProps.get("status");
        @SuppressWarnings("unchecked")
        List<String> statusEnum = (List<String>) statusField.get("enum");
        assertThat(statusEnum)
                .as("ProviderHealthStatus.status enum is part of the provider-health contract")
                .containsExactlyInAnyOrder("HEALTHY", "UNHEALTHY");
        @SuppressWarnings("unchecked")
        List<String> healthStatusRequired = (List<String>) healthStatus.get("required");
        assertThat(healthStatusRequired).containsExactly("status");

        // The text-dispatch response must expose a string message field.
        @SuppressWarnings("unchecked")
        Map<String, Object> chatResponse = (Map<String, Object>) schemas.get("RuntimeChatResponse");
        @SuppressWarnings("unchecked")
        Map<String, Object> responseProps = (Map<String, Object>) chatResponse.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> messageField = (Map<String, Object>) responseProps.get("message");
        assertThat(messageField)
                .as("RuntimeChatResponse.message must remain a string")
                .containsEntry("type", "string");
    }

    private Map<String, Object> loadContract() throws IOException {
        // Resolve relative to the project basedir (surefire working directory); fall back to the
        // file itself so the test also passes when run from an IDE with a different working dir.
        Path resolved = resolveContractPath();
        try (InputStream in = Files.newInputStream(resolved)) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(in);
            if (!(loaded instanceof Map)) {
                throw new AssertionError("OpenAPI contract did not parse into a mapping: " + resolved);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = (Map<String, Object>) loaded;
            return spec;
        }
    }

    private static Path resolveContractPath() {
        // surefire runs with the project basedir as the working directory; walk up defensively so
        // the test also passes when launched from an IDE with a different working directory.
        Path candidate = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5; depth++) {
            Path resolved = candidate.resolve(CONTRACT);
            if (Files.isRegularFile(resolved)) {
                return resolved;
            }
            if (candidate.getParent() == null) {
                break;
            }
            candidate = candidate.getParent();
        }
        return Paths.get("").toAbsolutePath().resolve(CONTRACT);
    }

    private static Path resolveProjectRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5; depth++) {
            if (Files.isRegularFile(candidate.resolve(POM)) && Files.isRegularFile(candidate.resolve(CONTRACT))) {
                return candidate;
            }
            if (candidate.getParent() == null) {
                break;
            }
            candidate = candidate.getParent();
        }
        return Paths.get("").toAbsolutePath();
    }

    private static Document parseXml(String content) throws IOException, ParserConfigurationException,
            org.xml.sax.SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new java.io.StringReader(content)));
    }

    /** Finds the {@code <plugin>} element declaring the build-helper plugin. */
    private static Optional<Element> findBuildHelperPlugin(Document pom) {
        NodeList nodes = pom.getElementsByTagName("artifactId");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element && "build-helper-maven-plugin".equals(((Element) n).getTextContent().trim())) {
                return ancestor((Element) n, "plugin");
            }
        }
        return Optional.empty();
    }

    private static Optional<Element> ancestor(Element element, String tag) {
        Node parent = element.getParentNode();
        while (parent != null) {
            if (parent instanceof Element && tag.equals(parent.getNodeName())) {
                return Optional.of((Element) parent);
            }
            parent = parent.getParentNode();
        }
        return Optional.empty();
    }

    private static Optional<Element> firstExecution(Element plugin) {
        NodeList nodes = plugin.getElementsByTagName("execution");
        return nodes.getLength() > 0 ? Optional.of((Element) nodes.item(0)) : Optional.empty();
    }

    private static List<String> goalNames(Element execution) {
        List<String> goals = new ArrayList<>();
        NodeList nodes = execution.getElementsByTagName("goal");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element) {
                goals.add(((Element) nodes.item(i)).getTextContent().trim());
            }
        }
        return goals;
    }

    /** Walks a fixed tag chain from {@code root}, returning the element at the last tag. */
    private static Optional<Element> firstChild(Element root, String... chain) {
        Element current = root;
        for (String tag : chain) {
            NodeList nodes = current.getElementsByTagName(tag);
            Element found = null;
            for (int i = 0; i < nodes.getLength(); i++) {
                if (nodes.item(i) instanceof Element) {
                    found = (Element) nodes.item(i);
                    break;
                }
            }
            if (found == null) {
                return Optional.empty();
            }
            current = found;
        }
        return Optional.of(current);
    }

    private static String childText(Element element, String tag) {
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element && tag.equals(n.getNodeName())) {
                return ((Element) n).getTextContent().trim();
            }
        }
        return null;
    }

    private static String firstText(Element element, String tag) {
        NodeList nodes = element.getElementsByTagName(tag);
        if (nodes.getLength() > 0 && nodes.item(0) instanceof Element) {
            return ((Element) nodes.item(0)).getTextContent().trim();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> info() throws IOException {
        Map<String, Object> spec = loadContract();
        return (Map<String, Object>) spec.get("info");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paths() throws IOException {
        Map<String, Object> spec = loadContract();
        return (Map<String, Object>) spec.get("paths");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schemas() throws IOException {
        Map<String, Object> spec = loadContract();
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        assertThat(components).as("components section must be present").isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        assertThat(schemas).as("components.schemas must be present").isNotNull();
        return schemas;
    }

    @SuppressWarnings("unchecked")
    private static String requestSchemaRef(Map<String, Object> operation) {
        Map<String, Object> requestBody = (Map<String, Object>) operation.get("requestBody");
        assertThat(requestBody)
                .as("operation must declare a required request body")
                .isNotNull()
                .containsEntry("required", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) requestBody.get("content");
        assertThat(content)
                .as("the request body must declare a content section before its media types")
                .isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) content.get("application/json");
        assertThat(json)
                .as("the request body must declare an application/json media type")
                .isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) json.get("schema");
        assertThat(schema)
                .as("the request body application/json media type must declare a schema")
                .isNotNull();
        String ref = (String) schema.get("$ref");
        assertThat(ref)
                .as("the request body schema must be resolved via a $ref to a named component")
                .isNotNull();
        return ref;
    }

    @SuppressWarnings("unchecked")
    private static String responseSchemaRef(Map<String, Object> operation, String statusCode) {
        Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
        assertThat(responses)
                .as("the operation must declare a responses section before " + statusCode)
                .isNotNull();
        Map<String, Object> response = (Map<String, Object>) responses.get(statusCode);
        assertThat(response).as("operation must declare a " + statusCode + " response").isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) response.get("content");
        assertThat(content)
                .as("the " + statusCode + " response must declare a content section before its media types")
                .isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) content.get("application/json");
        assertThat(json)
                .as("the " + statusCode + " response must declare an application/json media type")
                .isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) json.get("schema");
        assertThat(schema)
                .as("the " + statusCode + " response application/json media type must declare a schema")
                .isNotNull();
        String ref = (String) schema.get("$ref");
        assertThat(ref)
                .as("the " + statusCode + " response schema must be resolved via a $ref to a named component")
                .isNotNull();
        return ref;
    }
}
