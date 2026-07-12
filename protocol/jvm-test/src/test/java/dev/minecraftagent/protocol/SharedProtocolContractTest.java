package dev.minecraftagent.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.resource.AllowSchemaLoader;
import com.networknt.schema.resource.UriSchemaLoader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPInputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

final class SharedProtocolContractTest {
    private static final int MAX_PREVIEW_COMPRESSED_BYTES = 16_777_216;
    private static final int MAX_PREVIEW_UNCOMPRESSED_BYTES = 67_108_864;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z][a-zA-Z0-9_]{0,63})}");
    private static final SchemaValidatorsConfig SCHEMA_CONFIG = schemaConfig();
    private static final String SCHEMA_ID_PREFIX = "https://minecraft-agent.dev/schemas/1.0/";

    private final Path protocolRoot =
            Path.of(System.getProperty("minecraftAgent.protocolDir")).toAbsolutePath().normalize();
    private final JsonSchemaFactory schemaFactory = createSchemaFactory();
    private final Map<String, JsonSchema> schemas = new HashMap<>();

    @TestFactory
    Stream<DynamicTest> allSharedFixturesMatchTheirExpectedResults() throws IOException {
        var manifest = readJson(protocolRoot.resolve("fixtures/manifest.json"));
        assertEquals("1.0", manifest.path("manifestVersion").asText());
        assertEquals("1.0", manifest.path("protocolVersion").asText());
        assertTrue(manifest.path("cases").isArray());

        return StreamSupport.stream(manifest.path("cases").spliterator(), false)
                .map(testCase ->
                        DynamicTest.dynamicTest(testCase.path("id").asText(), () -> verifyCase(testCase)));
    }

    private void verifyCase(JsonNode testCase) throws IOException {
        var fixturePath = resolveInsideProtocol("fixtures/" + testCase.path("file").asText());
        var fixture = readJson(fixturePath);

        for (var validation : testCase.path("validations")) {
            var document = fixture.at(validation.path("documentPointer").asText());
            assertFalse(document.isMissingNode(), "documentPointer must resolve in " + fixturePath);

            var schemaName = validation.path("schema").asText();
            var errors = loadSchema(schemaName).validate(document);
            var actualValid = errors.isEmpty();
            var expectedValid = validation.path("expectedValid").asBoolean();
            assertEquals(
                    expectedValid,
                    actualValid,
                    () -> "Schema " + schemaName + " errors for " + fixturePath + ": " + errors);
        }

        if (testCase.has("semanticValidation")) {
            verifySemantics(testCase.path("semanticValidation"), fixture, fixturePath);
        }
    }

    private void verifySemantics(JsonNode expectation, JsonNode fixture, Path fixturePath) {
        var validator = expectation.path("validator").asText();
        var issues = switch (validator) {
            case "handshake-proof-v1" -> validateHandshakeProof(fixture);
            case "build-preview-transfer-v1" -> validateBuildPreview(fixture);
            case "recipe-view-v1" -> validateRecipeView(fixture);
            case "capability-manifest-v1" -> validateCapability(fixture);
            case "view-negotiation-v1" -> validateViewNegotiation(fixture);
            default -> List.of("SEMANTIC_VALIDATOR_UNKNOWN");
        };

        var expectedValid = expectation.path("expectedValid").asBoolean();
        assertEquals(
                expectedValid,
                issues.isEmpty(),
                () -> "Semantic issues for " + fixturePath + ": " + issues);
        if (expectation.has("errorCode")) {
            assertTrue(
                    issues.contains(expectation.path("errorCode").asText()),
                    () -> "Expected semantic error "
                            + expectation.path("errorCode").asText()
                            + " for "
                            + fixturePath
                            + ", received "
                            + issues);
        }
    }

    private List<String> validateHandshakeProof(JsonNode exchange) {
        if (!exchange.path("publicTestToken").isTextual()) {
            return List.of("HANDSHAKE_GOLDEN_STRUCTURE_INVALID");
        }
        var paper = exchange.path("paper");
        var runtime = exchange.path("runtime");
        if (!hasHandshakeGoldenFields(paper) || !hasHandshakeGoldenFields(runtime)) {
            return List.of("HANDSHAKE_GOLDEN_STRUCTURE_INVALID");
        }
        var paperPayload = paper.path("payload");
        var runtimePayload = runtime.path("payload");
        var paperAuthentication = paperPayload.path("authentication");
        var runtimeAuthentication = runtimePayload.path("authentication");

        if (!"1.0".equals(paper.path("protocolVersion").asText())
                || !"1.0".equals(runtime.path("protocolVersion").asText())
                || !"paper.hello".equals(paper.path("type").asText())
                || !"runtime.hello".equals(runtime.path("type").asText())
                || !"paper".equals(paperPayload.path("component").asText())
                || !"runtime".equals(runtimePayload.path("component").asText())
                || !"hmac-sha256".equals(paperAuthentication.path("scheme").asText())
                || !"hmac-sha256".equals(runtimeAuthentication.path("scheme").asText())) {
            return List.of("HANDSHAKE_IDENTITY_INVALID");
        }
        if (!paper.path("messageId").asText().equals(paper.path("requestId").asText())
                || !runtime.path("requestId").asText().equals(paper.path("requestId").asText())
                || runtime.path("messageId").asText().equals(paper.path("messageId").asText())
                || !runtime.path("serverId").asText().equals(paper.path("serverId").asText())
                || !paperAuthentication
                        .path("keyId")
                        .asText()
                        .equals(paper.path("serverId").asText())
                || !runtimeAuthentication
                        .path("keyId")
                        .asText()
                        .equals(paper.path("serverId").asText())) {
            return List.of("HANDSHAKE_CORRELATION_INVALID");
        }
        if (!supportsOnlyProtocolOne(paperPayload)
                || !supportsOnlyProtocolOne(runtimePayload)
                || !paperPayload.path("selectedProtocolVersion").isNull()
                || !"1.0".equals(runtimePayload.path("selectedProtocolVersion").asText())) {
            return List.of("HANDSHAKE_NEGOTIATION_INVALID");
        }
        if (!runtimeAuthentication
                .path("challenge")
                .asText()
                .equals(paperAuthentication.path("challenge").asText())) {
            return List.of("HANDSHAKE_CHALLENGE_MISMATCH");
        }
        if (paper.path("nonce").asText().equals(runtime.path("nonce").asText())
                || decodeCanonicalBase64Url(paper.path("nonce").asText(), 16, -1) == null
                || decodeCanonicalBase64Url(runtime.path("nonce").asText(), 16, -1) == null
                || decodeCanonicalBase64Url(
                                paperAuthentication.path("challenge").asText(), 16, -1)
                        == null
                || decodeCanonicalBase64Url(paperAuthentication.path("proof").asText(), 0, 32)
                        == null
                || decodeCanonicalBase64Url(runtimeAuthentication.path("proof").asText(), 0, 32)
                        == null) {
            return List.of("HANDSHAKE_BASE64URL_INVALID");
        }

        var token = exchange.path("publicTestToken").asText();
        if (!handshakeProofMatches(token, paper) || !handshakeProofMatches(token, runtime)) {
            return List.of("HANDSHAKE_PROOF_INVALID");
        }
        return List.of();
    }

    private static boolean hasHandshakeGoldenFields(JsonNode envelope) {
        var payload = envelope.path("payload");
        var authentication = payload.path("authentication");
        return envelope.isObject()
                && payload.isObject()
                && authentication.isObject()
                && Stream.of(
                                envelope.path("protocolVersion"),
                                envelope.path("messageId"),
                                envelope.path("requestId"),
                                envelope.path("serverId"),
                                envelope.path("type"),
                                envelope.path("timestamp"),
                                envelope.path("nonce"),
                                payload.path("component"),
                                payload.path("componentVersion"),
                                authentication.path("scheme"),
                                authentication.path("keyId"),
                                authentication.path("challenge"),
                                authentication.path("proof"))
                        .allMatch(JsonNode::isTextual)
                && payload.path("supportedProtocolVersions").isArray()
                && (payload.path("selectedProtocolVersion").isNull()
                        || payload.path("selectedProtocolVersion").isTextual());
    }

    private static boolean supportsOnlyProtocolOne(JsonNode payload) {
        var supported = payload.path("supportedProtocolVersions");
        return supported.size() == 1 && "1.0".equals(supported.path(0).asText());
    }

    private static byte[] decodeCanonicalBase64Url(
            String value, int minimumBytes, int expectedBytes) {
        if (!value.matches("^[A-Za-z0-9_-]+$")) {
            return null;
        }
        try {
            var decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length < minimumBytes
                    || (expectedBytes >= 0 && decoded.length != expectedBytes)
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
                return null;
            }
            return decoded;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static boolean handshakeProofMatches(String token, JsonNode envelope) {
        var authentication = envelope.path("payload").path("authentication");
        var suppliedProof = decodeCanonicalBase64Url(authentication.path("proof").asText(), 0, 32);
        var expectedProof = hmacSha256(token, handshakeTranscript(envelope));
        return suppliedProof != null && MessageDigest.isEqual(suppliedProof, expectedProof);
    }

    private static String handshakeTranscript(JsonNode envelope) {
        var payload = envelope.path("payload");
        return String.join(
                "\n",
                "minecraft-agent-handshake-v1",
                envelope.path("serverId").asText(),
                envelope.path("type").asText(),
                envelope.path("timestamp").asText(),
                envelope.path("nonce").asText(),
                payload.path("component").asText(),
                payload.path("componentVersion").asText(),
                payload.path("authentication").path("challenge").asText());
    }

    private static byte[] hmacSha256(String token, String transcript) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(transcript.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("HmacSHA256 is required by the Java runtime", error);
        }
    }

    private List<String> validateBuildPreview(JsonNode preview) {
        var chunks = preview.path("chunks");
        var expectedCount = preview.path("chunkCount").asInt(-1);
        var orderedChunks = new ArrayList<JsonNode>();
        chunks.forEach(orderedChunks::add);
        var seenIndexes = new HashSet<Integer>();
        for (var chunk : orderedChunks) {
            var index = chunk.path("index").asInt(-1);
            if (!seenIndexes.add(index)) {
                return List.of("CHUNK_INDEX_DUPLICATE");
            }
        }
        if (expectedCount != chunks.size()
                || seenIndexes.size() != expectedCount
                || !seenIndexes.containsAll(java.util.stream.IntStream.range(0, expectedCount).boxed().toList())) {
            return List.of("CHUNK_SET_INCOMPLETE");
        }
        orderedChunks.sort(Comparator.comparingInt(chunk -> chunk.path("index").asInt()));

        var declaredCompressedBytes = preview.path("compressedBytes").asInt(-1);
        if (declaredCompressedBytes < 1 || declaredCompressedBytes > MAX_PREVIEW_COMPRESSED_BYTES) {
            return List.of("CONTENT_COMPRESSED_LENGTH_MISMATCH");
        }
        var decodedChunks = new ArrayList<byte[]>();
        var decodedByteCount = 0;
        for (var chunk : orderedChunks) {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(chunk.path("data").asText());
            } catch (IllegalArgumentException error) {
                return List.of("CHUNK_BASE64_INVALID");
            }
            if (!Base64.getEncoder().encodeToString(decoded).equals(chunk.path("data").asText())) {
                return List.of("CHUNK_BASE64_INVALID");
            }
            decodedByteCount += decoded.length;
            if (decodedByteCount > declaredCompressedBytes
                    || decodedByteCount > MAX_PREVIEW_COMPRESSED_BYTES) {
                return List.of("CONTENT_COMPRESSED_LENGTH_MISMATCH");
            }
            decodedChunks.add(decoded);
        }
        for (int index = 0; index < orderedChunks.size(); index++) {
            if (orderedChunks.get(index).path("byteLength").asInt(-1)
                    != decodedChunks.get(index).length) {
                return List.of("CHUNK_LENGTH_MISMATCH");
            }
        }
        for (int index = 0; index < orderedChunks.size(); index++) {
            if (!sha256(decodedChunks.get(index))
                    .equals(orderedChunks.get(index).path("sha256").asText())) {
                return List.of("CHUNK_HASH_MISMATCH");
            }
        }

        var compressed = concatenate(decodedChunks);
        if (declaredCompressedBytes != compressed.length) {
            return List.of("CONTENT_COMPRESSED_LENGTH_MISMATCH");
        }

        var declaredUncompressedBytes = preview.path("uncompressedBytes").asInt(-1);
        byte[] content;
        try {
            content = switch (preview.path("encoding").asText()) {
                case "identity+base64" -> compressed;
                case "gzip+base64" ->
                    gunzip(
                            compressed,
                            Math.min(declaredUncompressedBytes, MAX_PREVIEW_UNCOMPRESSED_BYTES));
                default -> throw new IOException("unsupported encoding");
            };
        } catch (IOException error) {
            return List.of("CONTENT_DECOMPRESSION_FAILED");
        }

        if (declaredUncompressedBytes != content.length) {
            return List.of("CONTENT_UNCOMPRESSED_LENGTH_MISMATCH");
        }
        if (!sha256(content).equals(preview.path("contentHash").asText())) {
            return List.of("CONTENT_HASH_MISMATCH");
        }
        return List.of();
    }

    private List<String> validateRecipeView(JsonNode view) {
        var issues = new ArrayList<String>();
        var recipes = view.path("recipes");
        var selectedRecipe = view.path("selectedRecipe").asInt(-1);
        if (selectedRecipe < 0 || selectedRecipe >= recipes.size()) {
            issues.add("RECIPE_SELECTED_INDEX_OUT_OF_RANGE");
        }

        var recipeIds = new HashSet<String>();
        for (var recipe : recipes) {
            if (!recipeIds.add(recipe.path("recipeId").asText())) {
                issues.add("RECIPE_ID_DUPLICATE");
            }
            var layout = recipe.path("layout");
            var width = layout.path("width").asInt();
            var height = layout.path("height").asInt();
            var occupiedCoordinates = new HashSet<String>();
            var occupiedSlots = new HashSet<Integer>();
            for (var ingredient : layout.path("ingredients")) {
                var x = ingredient.path("x").asInt();
                var y = ingredient.path("y").asInt();
                var slot = ingredient.path("slot").asInt();
                if (x >= width || y >= height) {
                    issues.add("RECIPE_INGREDIENT_OUT_OF_BOUNDS");
                }
                if (!occupiedCoordinates.add(x + ":" + y) || !occupiedSlots.add(slot)) {
                    issues.add("RECIPE_INGREDIENT_DUPLICATE");
                }
                if (slot != y * width + x) {
                    issues.add("RECIPE_SLOT_COORDINATE_MISMATCH");
                }
            }
        }
        return distinct(issues);
    }

    private List<String> validateCapability(JsonNode manifest) {
        var execution = manifest.path("execution");
        var template = execution.path("template").asText();
        var arguments = manifest.path("arguments");
        var argumentUses = new HashMap<String, Integer>();
        var matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            var argument = matcher.group(1);
            argumentUses.merge(argument, 1, Integer::sum);
        }
        if (PLACEHOLDER.matcher(template).replaceAll("").matches(".*[{}].*")) {
            return List.of("CAPABILITY_TEMPLATE_PLACEHOLDER_MALFORMED");
        }
        if (argumentUses.keySet().stream().anyMatch(argument -> !arguments.has(argument))) {
            return List.of("CAPABILITY_TEMPLATE_ARGUMENT_UNDECLARED");
        }
        if (argumentUses.values().stream().anyMatch(count -> count > 1)) {
            return List.of("CAPABILITY_TEMPLATE_ARGUMENT_DUPLICATE");
        }
        for (var entry : arguments.properties()) {
            if (entry.getValue().path("required").asBoolean()
                    && !argumentUses.containsKey(entry.getKey())) {
                return List.of("CAPABILITY_REQUIRED_ARGUMENT_UNUSED");
            }
        }
        for (var entry : arguments.properties()) {
            if (!entry.getValue().path("required").asBoolean()) {
                return List.of("CAPABILITY_OPTIONAL_ARGUMENT_UNSUPPORTED");
            }
        }

        var command = template.substring(1).split("\\s+", 2)[0];
        if (!command.equals(execution.path("commandRoot").asText())) {
            return List.of("CAPABILITY_COMMAND_ROOT_MISMATCH");
        }
        for (var entry : arguments.properties()) {
            var minimum = entry.getValue().has("minimum")
                    ? entry.getValue().path("minimum").asDouble()
                    : entry.getValue().path("minLength").asDouble(Double.NaN);
            var maximum = entry.getValue().has("maximum")
                    ? entry.getValue().path("maximum").asDouble()
                    : entry.getValue().path("maxLength").asDouble(Double.NaN);
            if (!Double.isNaN(minimum) && !Double.isNaN(maximum) && minimum > maximum) {
                return List.of("CAPABILITY_ARGUMENT_RANGE_INVALID");
            }
        }
        return List.of();
    }

    private List<String> validateViewNegotiation(JsonNode negotiation) {
        var client = negotiation.path("client");
        var view = negotiation.path("view");
        if (!"1.0".equals(client.path("clientProtocolVersion").asText())) {
            return List.of("VIEW_PROTOCOL_UNSUPPORTED");
        }

        var requiredCapabilities = switch (view.path("viewType").asText()) {
            case "text", "selection_list", "proposal" -> List.of("overlay");
            case "item_stack", "item_list" -> List.of("overlay", "itemIcons");
            case "recipe" -> List.of("overlay", "itemIcons", "recipeView");
            case "build_preview" -> List.of("overlay", "litematicaPreview");
            default -> List.<String>of();
        };
        var capabilities = client.path("capabilities");
        for (var capability : requiredCapabilities) {
            if (capabilities.path(capability).asInt(0) < 1) {
                return List.of("VIEW_CAPABILITY_UNDECLARED");
            }
        }
        return List.of();
    }

    private JsonSchema loadSchema(String name) throws IOException {
        var cached = schemas.get(name);
        if (cached != null) {
            return cached;
        }
        var schemaNode = readJson(resolveInsideProtocol("schemas/" + name));
        var schema = schemaFactory.getSchema(schemaNode, SCHEMA_CONFIG);
        schemas.put(name, schema);
        return schema;
    }

    private Path resolveInsideProtocol(String relativePath) {
        var resolved = protocolRoot.resolve(relativePath).normalize();
        assertTrue(resolved.startsWith(protocolRoot), "protocol path must remain inside protocol root");
        return resolved;
    }

    private static SchemaValidatorsConfig schemaConfig() {
        return SchemaValidatorsConfig.builder()
                .formatAssertionsEnabled(true)
                .failFast(false)
                .build();
    }

    private JsonSchemaFactory createSchemaFactory() {
        var localSchemaPrefix = protocolRoot.resolve("schemas").toUri().toString();
        return JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V202012,
                builder -> {
                    builder.schemaMappers(
                            mappers -> mappers.mapPrefix(SCHEMA_ID_PREFIX, localSchemaPrefix));
                    builder.schemaLoaders(loaders -> loaders.values(values -> {
                        var uriLoaderIndex = 0;
                        while (uriLoaderIndex < values.size()
                                && !(values.get(uriLoaderIndex) instanceof UriSchemaLoader)) {
                            uriLoaderIndex++;
                        }
                        values.add(
                                uriLoaderIndex,
                                new AllowSchemaLoader(iri -> iri.toString().startsWith("file:")));
                    }));
                });
    }

    private static JsonNode readJson(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return JSON.readTree(input);
        }
    }

    private static byte[] concatenate(List<byte[]> chunks) {
        var length = chunks.stream().mapToInt(chunk -> chunk.length).sum();
        var result = new byte[length];
        var offset = 0;
        for (var chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }

    private static byte[] gunzip(byte[] compressed, int maximumOutputBytes) throws IOException {
        if (maximumOutputBytes < 0) {
            throw new IOException("invalid uncompressed byte limit");
        }
        try (var input = new GZIPInputStream(new ByteArrayInputStream(compressed));
                var output = new ByteArrayOutputStream(Math.min(maximumOutputBytes, 8192))) {
            var buffer = new byte[8192];
            var total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maximumOutputBytes) {
                    throw new IOException("gzip content exceeds declared uncompressed byte limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", error);
        }
    }

    private static List<String> distinct(List<String> issues) {
        return issues.stream().distinct().toList();
    }
}
