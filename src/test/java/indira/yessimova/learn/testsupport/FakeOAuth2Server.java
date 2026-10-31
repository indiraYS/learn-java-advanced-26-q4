package indira.yessimova.learn.testsupport;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * A fake OAuth2 / OIDC authorization server backed by WireMock.
 *
 * <p>This stands in for the real {@code mock-oauth2-server} container that the
 * application normally talks to on {@code http://localhost:8081/issuer-a}. It
 * exists because {@code SecurityConfig#jwtDecoder()} calls
 * {@link org.springframework.security.oauth2.jwt.JwtDecoders#fromIssuerLocation}
 * which performs OIDC <em>discovery over HTTP while the Spring context is
 * starting</em>. An integration test therefore cannot simply stub a bean - the
 * issuer has to be genuinely reachable before the context refreshes, and has to
 * keep serving its JWKS afterwards so every incoming token can be verified.
 *
 * <p>Two endpoints are served:
 * <ul>
 *   <li>{@code /{issuerPath}/.well-known/openid-configuration} - the discovery
 *       document. Spring Security asserts that the {@code issuer} field matches
 *       the location it fetched from, so the value is derived from the real
 *       randomly-assigned port rather than hardcoded.</li>
 *   <li>{@code /{issuerPath}/jwks} - the public half of a freshly generated
 *       RSA-2048 key pair.</li>
 * </ul>
 *
 * <p>The private half never leaves this class; {@link #mintToken} uses it to
 * sign tokens that the resource server then validates for real - signature,
 * issuer, expiry and all. Nothing about the security chain is bypassed, which
 * is the whole point: the tests exercise the same code path production does.
 *
 * <p>Tokens carry the {@code role} claim as a <strong>string</strong>, matching
 * the shape of the real issuer's tokens and the
 * {@code setAuthoritiesClaimName("role")} configuration in {@code SecurityConfig}.
 *
 * <p>Not thread-safe; intended to be driven from a single test lifecycle.
 */
public class FakeOAuth2Server {

    /** Mirrors the path segment used by the real issuer, so URLs look familiar. */
    private static final String ISSUER_PATH = "issuer-a";

    private static final String KEY_ID = "fake-oauth2-key";

    private final WireMockServer wireMock;
    private final RSAKey signingKey;

    private FakeOAuth2Server(WireMockServer wireMock, RSAKey signingKey) {
        this.wireMock = wireMock;
        this.signingKey = signingKey;
    }

    /**
     * Generates a key pair, starts WireMock on a random free port and installs
     * the discovery + JWKS stubs. The server is ready to serve on return.
     */
    public static FakeOAuth2Server startOnRandomPort() {
        RSAKey key;
        try {
            key = new RSAKeyGenerator(2048)
                    .keyID(KEY_ID)
                    .generate();
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate the fake issuer's signing key", e);
        }

        WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();

        FakeOAuth2Server fake = new FakeOAuth2Server(server, key);
        fake.installStubs();
        return fake;
    }

    private void installStubs() {
        String issuer = issuerUri();

        // Discovery document. Spring Security's JwtDecoders validates that the
        // "issuer" value equals the location it was fetched from, so it must be
        // built from the port WireMock actually bound to.
        String discovery = """
                {
                  "issuer": "%s",
                  "jwks_uri": "%s/jwks",
                  "token_endpoint": "%s/token",
                  "response_types_supported": ["token"],
                  "subject_types_supported": ["public"],
                  "id_token_signing_alg_values_supported": ["RS256"]
                }
                """.formatted(issuer, issuer, issuer);

        wireMock.stubFor(get("/" + ISSUER_PATH + "/.well-known/openid-configuration")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(discovery)));

        // Public keys only - the private exponent stays in this process.
        JWKSet publicKeys = new JWKSet(signingKey.toPublicJWK());
        wireMock.stubFor(get("/" + ISSUER_PATH + "/jwks")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(publicKeys.toString())));
    }

    /**
     * The issuer URI to feed into
     * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}.
     */
    public String issuerUri() {
        return "http://localhost:" + wireMock.port() + "/" + ISSUER_PATH;
    }

    /**
     * Mints a signed, currently-valid access token carrying the given role.
     *
     * @param role value of the {@code role} claim, e.g. {@code "ADMIN"} or
     *             {@code "USER"} - deliberately without the {@code ROLE_}
     *             prefix, which {@code SecurityConfig} adds itself
     */
    public String mintToken(String role) {
        return mint(claims -> claims.claim("role", role));
    }

    /** Mints a valid token with no {@code role} claim - authenticated but unauthorized. */
    public String mintTokenWithoutRole() {
        return mint(claims -> claims);
    }

    /** Mints a structurally valid token that expired an hour ago. */
    public String mintExpiredToken(String role) {
        Instant now = Instant.now();
        return sign(baseClaims(now)
                .claim("role", role)
                .issueTime(Date.from(now.minus(2, ChronoUnit.HOURS)))
                .expirationTime(Date.from(now.minus(1, ChronoUnit.HOURS)))
                .build());
    }

    /**
     * Mints a token signed by a <em>different</em> key than the one published in
     * the JWKS, so the resource server must reject the signature.
     */
    public String mintTokenSignedByUnknownKey(String role) {
        try {
            RSAKey rogue = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
            SignedJWT jwt = new SignedJWT(
                    header(),
                    baseClaims(Instant.now()).claim("role", role).build());
            jwt.sign(new RSASSASigner(rogue.toRSAPrivateKey()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Could not mint a rogue-key token", e);
        }
    }

    private String mint(java.util.function.UnaryOperator<JWTClaimsSet.Builder> customiser) {
        return sign(customiser.apply(baseClaims(Instant.now())).build());
    }

    /**
     * The claim set shared by every minted token. Mirrors what the real issuer
     * produces, including the short 120-second lifetime.
     */
    private JWTClaimsSet.Builder baseClaims(Instant now) {
        return new JWTClaimsSet.Builder()
                .issuer(issuerUri())
                .subject("learn-client")
                .audience(List.of("learn-client"))
                .claim("scope", "openid")
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(120)));
    }

    private String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(header(), claims);
            jwt.sign(new RSASSASigner(signingKey.toRSAPrivateKey()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign the fake access token", e);
        }
    }

    private static JWSHeader header() {
        return new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(KEY_ID)
                .type(JOSEObjectType.JWT)
                .build();
    }

    /** Stops WireMock and releases the port. */
    public void stop() {
        wireMock.stop();
    }
}
