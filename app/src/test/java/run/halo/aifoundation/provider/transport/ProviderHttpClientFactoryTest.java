package run.halo.aifoundation.provider.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.reactive.function.client.WebClient;
import run.halo.aifoundation.extension.AiProvider;
import run.halo.app.extension.Metadata;

class ProviderHttpClientFactoryTest {

    @Test
    void defaultsToSpringDefaultLimit() {
        assertThat(ProviderHttpClientFactory.providerMaxInMemorySize(provider(null)))
            .isEqualTo(ProviderHttpClientFactory.DEFAULT_MAX_IN_MEMORY_SIZE)
            .isEqualTo(256 * 1024);
        assertThat(ProviderHttpClientFactory.providerMaxInMemorySize(null))
            .isEqualTo(ProviderHttpClientFactory.DEFAULT_MAX_IN_MEMORY_SIZE);
    }

    @Test
    void honoursConfiguredLimit() {
        assertThat(ProviderHttpClientFactory.providerMaxInMemorySize(provider(8 * 1024 * 1024)))
            .isEqualTo(8 * 1024 * 1024);
    }

    @Test
    void fallsBackToDefaultForNonPositiveLimit() {
        assertThat(ProviderHttpClientFactory.providerMaxInMemorySize(provider(0)))
            .isEqualTo(ProviderHttpClientFactory.DEFAULT_MAX_IN_MEMORY_SIZE);
        assertThat(ProviderHttpClientFactory.providerMaxInMemorySize(provider(-1)))
            .isEqualTo(ProviderHttpClientFactory.DEFAULT_MAX_IN_MEMORY_SIZE);
    }

    @Test
    void readsImageSizedResponseBodyWithConfiguredLimit() throws Exception {
        // 1 MB base64 body: above the 256 KB default, readable once the provider raises its limit.
        var provider = provider(8 * 1024 * 1024);
        var payload = payloadOfLength(1_000_000);
        var server = startPayloadServer(payload);
        try {
            assertThat(readBody(provider, server)).isEqualTo(payload);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsBodyJustUnderConfiguredLimit() throws Exception {
        var limit = 8 * 1024 * 1024;
        var provider = provider(limit);
        var payload = payloadOfLength(limit - 1024);
        var server = startPayloadServer(payload);
        try {
            assertThat(readBody(provider, server)).hasSize(payload.length());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsBodyJustAboveConfiguredLimit() throws Exception {
        var limit = 8 * 1024 * 1024;
        var provider = provider(limit);
        var server = startPayloadServer(payloadOfLength(limit + 1024));
        try {
            assertThatThrownBy(() -> readBody(provider, server))
                .hasRootCauseInstanceOf(DataBufferLimitException.class);
        } finally {
            server.stop(0);
        }
    }

    private static String readBody(AiProvider provider, HttpServer server) {
        return clientFor(provider, server)
            .post()
            .uri("/images/generations")
            .retrieve()
            .bodyToMono(String.class)
            .block();
    }

    private static WebClient clientFor(AiProvider provider, HttpServer server) {
        return ProviderHttpClientFactory.webClientBuilder(provider)
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
            .build();
    }

    private static AiProvider provider(Integer maxInMemorySize) {
        var provider = new AiProvider();
        var metadata = new Metadata();
        metadata.setName("provider-http-client-factory-test");
        provider.setMetadata(metadata);
        var spec = new AiProvider.AiProviderSpec();
        spec.setProviderType("openai");
        spec.setMaxInMemorySize(maxInMemorySize);
        provider.setSpec(spec);
        return provider;
    }

    private static String payloadOfLength(int length) {
        var overhead = "{\"data\":[{\"b64_json\":\"\"}]}".length();
        return "{\"data\":[{\"b64_json\":\"" + "a".repeat(length - overhead) + "\"}]}";
    }

    private static HttpServer startPayloadServer(String payload) throws IOException {
        var server = HttpServer.create(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/images/generations", exchange -> {
            try (exchange) {
                var bytes = payload.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        return server;
    }
}
