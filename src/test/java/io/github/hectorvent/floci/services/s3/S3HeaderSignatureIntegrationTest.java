package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.signer.AwsS3V4Signer;
import software.amazon.awssdk.auth.signer.params.AwsS3V4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(S3HeaderSignatureIntegrationTest.SignaturesEnabled.class)
class S3HeaderSignatureIntegrationTest {
    public static class SignaturesEnabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.s3.validate-header-signatures", "true");
        }
    }

    @Test
    void rejectsAnInvalidSignatureBeforeCreatingABucket() {
        String path = "/sig-" + UUID.randomUUID();
        SdkHttpFullRequest signed = sign("PUT", path, "", Map.of(), "test");
        String authorization = signed.firstMatchingHeader("Authorization").orElseThrow();
        SdkHttpFullRequest tampered = signed.toBuilder().putHeader("Authorization",
                authorization.replaceFirst("Signature=[0-9a-f]+", "Signature=" + "0".repeat(64))).build();
        send(tampered, "").statusCode(403).body("Error.Code", equalTo("SignatureDoesNotMatch"));
        send(sign("GET", path, "", Map.of(), "test"), "").statusCode(404);
    }

    @Test
    void acceptsSdkSignedBinaryWritesAndReads() {
        String bucket = bucket();
        byte[] bytes = new byte[]{0, -1, -128, 42};
        SdkHttpFullRequest write = signBytes("PUT", bucket + "/binary", bytes, Map.of(), "test");
        sendBytes(write, bytes).statusCode(200);
        byte[] received = send(sign("GET", bucket + "/binary", "", Map.of(), "test"), "")
                .statusCode(200).extract().asByteArray();
        org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, received);
    }

    @Test
    void rejectsChangesToSignedConditionalHeaders() {
        String path = bucket() + "/conditional";
        SdkHttpFullRequest signed = sign("PUT", path, "body", Map.of("If-None-Match", "*"), "test");
        SdkHttpFullRequest tampered = signed.toBuilder().putHeader("If-None-Match", "different").build();
        send(tampered, "body").statusCode(403).body("Error.Code", equalTo("SignatureDoesNotMatch"));
        send(signed, "body").statusCode(200);
        send(signed, "body").statusCode(412);
    }

    @Test
    void rejectsBodyChangesWithoutChangingObjectState() {
        String path = bucket() + "/body";
        send(sign("PUT", path, "first", Map.of(), "test"), "first").statusCode(200);
        send(sign("PUT", path, "other", Map.of(), "test"), "wrong")
                .statusCode(400).body("Error.Code", equalTo("XAmzContentSHA256Mismatch"));
        send(sign("GET", path, "", Map.of(), "test"), "").statusCode(200).body(equalTo("first"));
    }

    @Test
    void rejectsUnknownKeysAndUnsignedRequests() {
        String path = "/sig-" + UUID.randomUUID();
        send(sign("PUT", path, "", Map.of(), "unknown-key"), "")
                .statusCode(403).body("Error.Code", equalTo("InvalidAccessKeyId"));
        given().put(path).then().statusCode(403);
    }

    @Test
    void preservesEncodedObjectPathsAndSignsTheQueryString() {
        String bucket = bucket();
        String path = bucket + "/folder%20one/%C3%A9%2Bfile";
        send(sign("PUT", path, "encoded", Map.of(), "test"), "encoded").statusCode(200);
        send(sign("GET", path, "", Map.of(), "test"), "").statusCode(200).body(equalTo("encoded"));
        SdkHttpFullRequest listed = sign("GET", bucket + "?list-type=2&prefix=folder%20one%2F&a=1&a-=2", "", Map.of(), "test");
        send(listed, "").statusCode(200).body("ListBucketResult.KeyCount", equalTo("1"));
        SdkHttpFullRequest tampered = listed.toBuilder().putRawQueryParameter("prefix", "different").build();
        send(tampered, "").statusCode(403).body("Error.Code", equalTo("SignatureDoesNotMatch"));
    }

    private static String bucket() {
        String path = "/sig-" + UUID.randomUUID();
        send(sign("PUT", path, "", Map.of(), "test"), "").statusCode(200);
        return path;
    }

    @Test
    void verifiesBodiesWithoutAContentTypeHeader() throws Exception {
        String path = bucket() + "/no-content-type";
        SdkHttpFullRequest signed = sign("PUT", path, "first", Map.of(), "test");
        var builder = HttpRequest.newBuilder(signed.getUri()).PUT(HttpRequest.BodyPublishers.ofString("first"));
        signed.headers().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("content-length")) {
                values.forEach(value -> builder.header(name, value));
            }
        });
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            org.junit.jupiter.api.Assertions.assertEquals(200, response.statusCode(), response.body());
        }
        send(sign("GET", path, "", Map.of(), "test"), "").statusCode(200).body(equalTo("first"));
    }

    @Test
    void canonicalizesReservedCharactersSentLiterallyOnTheWire() {
        String path = bucket() + "/owner%40host";
        SdkHttpFullRequest signed = sign("PUT", path, "owner", Map.of(), "test");
        send(signed.toBuilder().encodedPath(path.replace("%40", "@")).build(), "owner").statusCode(200);
        send(sign("GET", path, "", Map.of(), "test"), "").statusCode(200).body(equalTo("owner"));
    }

    @Test
    void verifiesTheOriginalVirtualHostedUri() {
        String bucket = bucket();
        send(sign("PUT", bucket + "/virtual", "original", Map.of(), "test"), "original").statusCode(200);
        String host = bucket.substring(1) + ".localhost";
        SdkHttpFullRequest raw = SdkHttpFullRequest.builder()
                .uri(URI.create("http://" + host + ":" + RestAssured.port + "/virtual"))
                .method(SdkHttpMethod.GET).build();
        SdkHttpFullRequest signed = AwsS3V4Signer.create().sign(raw, AwsS3V4SignerParams.builder()
                .awsCredentials(AwsBasicCredentials.create("test", "test"))
                .signingName("s3").signingRegion(Region.US_EAST_1)
                .doubleUrlEncode(false).normalizePath(false)
                .enablePayloadSigning(true).enableChunkedEncoding(false).build());
        // Connect to loopback while retaining the signed Host header on the wire.
        send(signed.toBuilder().host("localhost").build(), "")
                .statusCode(200).body(equalTo("original"));
    }

    @Test
    void rejectsMalformedScopesAndMissingSignedHeaders() {
        String path = "/sig-" + UUID.randomUUID();
        SdkHttpFullRequest signed = sign("PUT", path, "", Map.of(), "test");
        String authorization = signed.firstMatchingHeader("Authorization").orElseThrow();
        send(signed.toBuilder().putHeader("Authorization", authorization.replace("/aws4_request", "/wrong_terminator")).build(), "")
                .statusCode(400).body("Error.Code", equalTo("AuthorizationHeaderMalformed"));
        send(signed.toBuilder().removeHeader("x-amz-date").build(), "")
                .statusCode(400).body("Error.Code", equalTo("AuthorizationHeaderMalformed"));
    }

    private static SdkHttpFullRequest sign(String method, String path, String body,
                                          Map<String, String> headers, String key) {
        return signBytes(method, path, body.getBytes(StandardCharsets.UTF_8), headers, key);
    }

    private static SdkHttpFullRequest signBytes(String method, String path, byte[] body,
                                               Map<String, String> headers, String key) {
        var builder = SdkHttpFullRequest.builder()
                .uri(URI.create("http://localhost:" + RestAssured.port + path))
                .method(SdkHttpMethod.fromValue(method))
                .contentStreamProvider(() -> new ByteArrayInputStream(body));
        headers.forEach(builder::putHeader);
        return AwsS3V4Signer.create().sign(builder.build(), AwsS3V4SignerParams.builder()
                .awsCredentials(AwsBasicCredentials.create(key, "test"))
                .signingName("s3").signingRegion(Region.US_EAST_1)
                .doubleUrlEncode(false).normalizePath(false)
                .enablePayloadSigning(true).enableChunkedEncoding(false).build());
    }

    private static ValidatableResponse send(SdkHttpFullRequest request, String body) {
        return sendBytes(request, body.getBytes(StandardCharsets.UTF_8));
    }

    private static ValidatableResponse sendBytes(SdkHttpFullRequest request, byte[] body) {
        RequestSpecification spec = given().urlEncodingEnabled(false).body(body);
        request.headers().forEach((name, values) -> values.forEach(value -> spec.header(name, value)));
        return spec.request(request.method().name(), request.getUri()).then();
    }
}
