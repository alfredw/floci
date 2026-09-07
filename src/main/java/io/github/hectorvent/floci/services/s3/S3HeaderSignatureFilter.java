package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Opt-in SigV4 verification of S3 Authorization headers before a controller mutates state. */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION)
public class S3HeaderSignatureFilter implements ContainerRequestFilter {
    private static final Logger LOG = Logger.getLogger(S3HeaderSignatureFilter.class);
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuuMMdd'T'HHmmss'Z'").withResolverStyle(ResolverStyle.STRICT).withZone(ZoneOffset.UTC);

    private final boolean enabled;
    private final IamService iamService;

    @Context
    ResourceInfo resourceInfo;

    @Inject
    public S3HeaderSignatureFilter(EmulatorConfig config, IamService iamService) {
        enabled = config.services().s3().validateHeaderSignatures();
        this.iamService = iamService;
    }

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        if (!enabled || resourceInfo.getResourceClass() != S3Controller.class) {
            return;
        }
        String authorization = ctx.getHeaderString("Authorization");
        if (authorization == null && ctx.getUriInfo().getQueryParameters().containsKey("X-Amz-Algorithm")) {
            // Query authentication retains PreSignedUrlFilter's independently configured policy.
            return;
        }
        try {
            verify(ctx, authorization);
        } catch (AwsException e) {
            String xml = new XmlBuilder().start("Error").elem("Code", e.getErrorCode())
                    .elem("Message", e.getMessage()).end("Error").build();
            ctx.abortWith(Response.status(e.getHttpStatus()).type(MediaType.APPLICATION_XML).entity(xml).build());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            LOG.debugv(e, "S3 header signature verification failed");
            String xml = new XmlBuilder().start("Error").elem("Code", "SignatureDoesNotMatch")
                    .elem("Message", "The request signature could not be verified.").end("Error").build();
            ctx.abortWith(Response.status(403).type(MediaType.APPLICATION_XML).entity(xml).build());
        }
    }

    private void verify(ContainerRequestContext ctx, String authorization) throws Exception {
        SignedRequest signed = SignedRequest.parse(authorization);
        String timestamp = ctx.getHeaderString("x-amz-date");
        if (timestamp == null || !timestamp.startsWith(signed.date())) {
            throw malformed();
        }
        Instant time;
        try {
            time = Instant.from(TIMESTAMP.parse(timestamp));
        } catch (java.time.format.DateTimeParseException e) {
            throw malformed();
        }
        if (Duration.between(time, Instant.now()).abs().compareTo(Duration.ofMinutes(15)) > 0) {
            throw new AwsException("RequestTimeTooSkewed", "The request time differs too much from the server time.", 403);
        }
        String token = ctx.getHeaderString("x-amz-security-token");
        if (token != null && !Arrays.asList(signed.headers().split(";")).contains("x-amz-security-token")) {
            throw malformed();
        }
        String secret = "test".equals(signed.key()) && token == null ? "test"
                : iamService.findSecretKey(signed.key(), token).orElseThrow(() ->
                    new AwsException("InvalidAccessKeyId", "The AWS Access Key Id does not exist in our records.", 403));

        URI uri = ctx.getProperty(S3VirtualHostFilter.ORIGINAL_REQUEST_URI_PROPERTY) instanceof URI original
                ? original : ctx.getUriInfo().getRequestUri();
        StringBuilder headers = new StringBuilder();
        for (String name : signed.headers().split(";")) {
            String value = "host".equals(name)
                    ? S3VirtualHostFilter.resolveHost(ctx.getHeaderString("Host"), uri)
                    : ctx.getHeaderString(name);
            if (value == null) {
                throw malformed();
            }
            headers.append(name).append(':').append(value.trim().replaceAll("[\\t ]+", " ")).append('\n');
        }
        String payloadHash = ctx.getHeaderString("x-amz-content-sha256");
        if (payloadHash == null) {
            throw new AwsException("InvalidRequest", "Missing x-amz-content-sha256 header.", 400);
        }
        if (!"UNSIGNED-PAYLOAD".equals(payloadHash)) {
            if (!payloadHash.matches("[0-9a-f]{64}")) {
                throw new AwsException("NotImplemented", "Streaming payload signatures are not implemented.", 501);
            }
            byte[] body = ctx.getEntityStream().readAllBytes();
            ctx.setEntityStream(new ByteArrayInputStream(body));
            String actual = PreSignedUrlFilter.hexEncode(MessageDigest.getInstance("SHA-256").digest(body));
            if (!actual.equals(payloadHash)) {
                throw new AwsException("XAmzContentSHA256Mismatch", "The provided payload hash does not match the body.", 400);
            }
        }
        String path = uri.getRawPath().isEmpty() ? "/" : Arrays.stream(uri.getRawPath().split("/", -1))
                .map(S3HeaderSignatureFilter::encodeQueryPart).collect(Collectors.joining("/"));
        String canonical = ctx.getMethod() + "\n" + path + "\n" + canonicalQuery(uri.getRawQuery())
                + "\n" + headers + "\n" + signed.headers() + "\n" + payloadHash;
        String scope = signed.date() + "/" + signed.region() + "/s3/aws4_request";
        String toSign = ALGORITHM + "\n" + timestamp + "\n" + scope + "\n" + PreSignedUrlFilter.sha256Hex(canonical);
        byte[] signingKey = PreSignedUrlFilter.deriveSigningKey(secret, signed.date(), signed.region(), "s3");
        String expected = PreSignedUrlFilter.hexEncode(PreSignedUrlFilter.hmacSha256(signingKey, toSign));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signed.signature().getBytes(StandardCharsets.UTF_8))) {
            throw new AwsException("SignatureDoesNotMatch", "The request signature does not match the calculated signature.", 403);
        }
    }

    private static String canonicalQuery(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return Arrays.stream(raw.split("&", -1)).map(pair -> {
            String[] parts = pair.split("=", 2);
            return new String[]{encodeQueryPart(parts[0]), encodeQueryPart(parts.length == 2 ? parts[1] : "")};
        }).sorted(Comparator.comparing((String[] pair) -> pair[0]).thenComparing(pair -> pair[1]))
                .map(pair -> pair[0] + "=" + pair[1]).collect(Collectors.joining("&"));
    }

    private static String encodeQueryPart(String value) {
        return PreSignedUrlFilter.awsUriEncode(URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8));
    }

    private static AwsException malformed() {
        return new AwsException("AuthorizationHeaderMalformed", "The authorization header or signing date is malformed.", 400);
    }

    private record SignedRequest(String key, String date, String region, String headers, String signature) {
        static SignedRequest parse(String authorization) {
            if (authorization == null) {
                throw new AwsException("AccessDenied", "A signed request is required.", 403);
            }
            if (!authorization.startsWith(ALGORITHM + " ")) {
                throw malformed();
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (String part : authorization.substring(ALGORITHM.length()).split(",")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length != 2 || fields.putIfAbsent(pair[0], pair[1]) != null) {
                    throw malformed();
                }
            }
            String[] credential = fields.getOrDefault("Credential", "").split("/", -1);
            String names = fields.getOrDefault("SignedHeaders", "");
            String signature = fields.getOrDefault("Signature", "");
            if (credential.length != 5 || credential[0].isBlank() || !credential[1].matches("[0-9]{8}")
                    || credential[2].isBlank() || !"s3".equals(credential[3]) || !"aws4_request".equals(credential[4])
                    || !signature.matches("[0-9a-f]{64}") || !names.matches("[a-z0-9-]+(;[a-z0-9-]+)*")) {
                throw malformed();
            }
            var list = Arrays.asList(names.split(";"));
            if (!list.contains("host") || !list.contains("x-amz-date") || list.contains("authorization")
                    || !names.equals(list.stream().distinct().sorted().collect(Collectors.joining(";")))) {
                throw malformed();
            }
            return new SignedRequest(credential[0], credential[1], credential[2], names, signature);
        }
    }
}
