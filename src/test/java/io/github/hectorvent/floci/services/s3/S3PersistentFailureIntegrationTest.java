package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.signer.AwsS3V4Signer;
import software.amazon.awssdk.auth.signer.params.AwsS3V4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(S3PersistentFailureIntegrationTest.PersistentProfile.class)
class S3PersistentFailureIntegrationTest {
    @jakarta.inject.Inject io.github.hectorvent.floci.config.EmulatorConfig config;
    public static class PersistentProfile implements QuarkusTestProfile {
        static final Path ROOT = Path.of(System.getProperty("java.io.tmpdir"), "floci-persistence-" + UUID.randomUUID());
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("floci.storage.services.s3.mode", "persistent",
                    "floci.storage.persistent-path", ROOT.toString(),
                    "floci.services.s3.validate-header-signatures", "true");
        }
    }

    @Test
    void failedPersistenceReturnsS3ErrorsAndNeverExposesUncommittedBytes() throws Exception {
        request("PUT", "/persistent-bucket", "").statusCode(200);
        request("PUT", "/persistent-bucket/key", "old").statusCode(200);
        Path obstacle = Path.of(config.storage().persistentPath()).resolve("s3-objects.json.tmp");
        Files.createDirectory(obstacle);
        try {
            request("PUT", "/persistent-bucket/key", "new").statusCode(500)
                    .body("Error.Code", equalTo("InternalError"));
            request("GET", "/persistent-bucket/key", "").statusCode(500)
                    .body("Error.Code", equalTo("InternalError"));
        } finally {
            Files.delete(obstacle);
        }
        request("GET", "/persistent-bucket/key", "").statusCode(500);
    }

    private io.restassured.response.ValidatableResponse request(String method, String path, String body) {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var unsigned = SdkHttpFullRequest.builder()
                .uri(URI.create("http://localhost:" + RestAssured.port + path))
                .method(SdkHttpMethod.fromValue(method))
                .contentStreamProvider(() -> new ByteArrayInputStream(bytes)).build();
        var signed = AwsS3V4Signer.create().sign(unsigned, AwsS3V4SignerParams.builder()
                .awsCredentials(AwsBasicCredentials.create("test", "test"))
                .signingName("s3").signingRegion(Region.US_EAST_1)
                .doubleUrlEncode(false).normalizePath(false)
                .enablePayloadSigning(true).enableChunkedEncoding(false).build());
        var spec = given().body(bytes);
        signed.headers().forEach((name, values) -> values.forEach(value -> spec.header(name, value)));
        return spec.request(method, signed.getUri()).then();
    }
}
