package indira.yessimova.learn.integration;

import indira.yessimova.learn.model.Customer;
import indira.yessimova.learn.model.CustomerUpsert;
import indira.yessimova.learn.service.ICustomerService;
import indira.yessimova.learn.testsupport.FakeOAuth2Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security integration test: the <em>real</em> filter chain, the <em>real</em>
 * {@code JwtDecoder}, and real signature / issuer / expiry validation.
 *
 * <h2>Why a fake issuer and not a mocked decoder</h2>
 * {@code SecurityConfig#jwtDecoder()} calls {@code JwtDecoders.fromIssuerLocation},
 * which performs OIDC discovery <em>over HTTP while the context refreshes</em>.
 * Stubbing the decoder bean - as the database tests do - keeps a context off the
 * network but also removes the very thing this class exists to test. So
 * {@link FakeOAuth2Server} is started first, on a random port, and
 * {@link #pointAtTheFakeIssuer(DynamicPropertyRegistry)} hands its URI to Spring
 * before the context boots. Every token below is signed for real and verified for
 * real; nothing in the chain is bypassed.
 *
 * <h2>Why the service is a mock</h2>
 * The subject here is the filter chain, not persistence. {@link ICustomerService}
 * is replaced by a Mockito mock, which also makes the central assertion possible:
 * a rejected request must never reach the service at all. (The {@code local}
 * profile still boots H2 so the rest of the context is the real one, but nothing
 * below touches a table.)
 *
 * <h2>Why two parameterized tests and no per-endpoint cases</h2>
 * 401 and 403 are properties of the chain, not of any one endpoint, so asserting
 * them once per method would be the same test written five times. The two
 * matrices below cover every (endpoint, credential) combination that matters:
 * one for what the chain must refuse, one for what it must let through.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@DisplayName("security: real filter chain against a fake issuer")
class SecurityIntegrationTest {

    /**
     * Started in a static initialiser rather than {@code @BeforeAll}: the issuer
     * has to be serving its discovery document before Spring begins refreshing
     * the context, and {@link DynamicPropertySource} is evaluated earlier still.
     */
    private static final FakeOAuth2Server ISSUER = FakeOAuth2Server.startOnRandomPort();

    /**
     * Overrides the {@code issuer-uri} hardcoded by {@code application-local.yml}.
     * {@code @DynamicPropertySource} contributes a property source ahead of the
     * profile YAML, so the real decoder is built against the fake issuer's
     * randomly-assigned port.
     */
    @DynamicPropertySource
    static void pointAtTheFakeIssuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER::issuerUri);
    }

    @AfterAll
    static void stopIssuer() {
        ISSUER.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    /** Keeps every assertion below about the chain, never about a database. */
    @MockitoBean
    private ICustomerService customerService;

    /**
     * The kind of credential a request carries. Resolved to an actual token
     * inside the test, so the matrices stay readable.
     */
    private enum Credential {

        /** No {@code Authorization} header at all. */
        NONE,

        /** Not a JWT in the first place. */
        GARBAGE,

        /** Correctly signed, but expired an hour ago. */
        EXPIRED,

        /** Well-formed and current, signed by a key the issuer never published. */
        UNKNOWN_KEY,

        /** Valid, but carries no {@code role} claim: authenticated, unauthorized. */
        NO_ROLE,

        /** Valid, {@code role=USER}. */
        USER,

        /** Valid, {@code role=ADMIN}. */
        ADMIN;

        /** {@code null} means "send no Authorization header". */
        String token() {
            return switch (this) {
                case NONE -> null;
                case GARBAGE -> "not-even-a-jwt";
                case EXPIRED -> ISSUER.mintExpiredToken("ADMIN");
                case UNKNOWN_KEY -> ISSUER.mintTokenSignedByUnknownKey("ADMIN");
                case NO_ROLE -> ISSUER.mintTokenWithoutRole();
                case USER -> ISSUER.mintToken("USER");
                case ADMIN -> ISSUER.mintToken("ADMIN");
            };
        }
    }

    /**
     * Stubs every service method once, so a single parameterized method can
     * exercise any endpoint without caring which. Mockito does not police
     * unused stubs on {@code @MockitoBean}, so the rows that never reach the
     * service cost nothing.
     */
    @BeforeEach
    void stubTheService() {
        Customer customer = new Customer(1L, "Ivan", "ivan@email.com");
        when(customerService.findAll(any())).thenReturn(Page.empty());
        when(customerService.findById(anyLong())).thenReturn(customer);
        when(customerService.create(any(CustomerUpsert.class))).thenReturn(customer);
        when(customerService.update(anyLong(), any(CustomerUpsert.class))).thenReturn(customer);
        doNothing().when(customerService).delete(anyLong());
    }

    // ------------------------------------------------------------------
    // what the chain must refuse
    // ------------------------------------------------------------------

    /**
     * Every way a request can be turned away.
     *
     * <p>The first block is authentication: no token, a token that is not a JWT,
     * an expired one, and one signed by a key absent from the JWKS - all 401,
     * and all proof that the decoder really validates rather than merely parses.
     * The second block is authorization: {@code POST} is the one ADMIN-only
     * route, so a USER token and a token with no {@code role} claim are both
     * authenticated yet forbidden, 403.
     */
    static Stream<Arguments> refusedRequests() {
        return Stream.of(
                // --- authentication: 401 ---------------------------------
                Arguments.of(HttpMethod.GET, "/api/v1/customers", Credential.NONE, 401),
                Arguments.of(HttpMethod.GET, "/api/v1/customers/1", Credential.NONE, 401),
                Arguments.of(HttpMethod.POST, "/api/v1/customers", Credential.NONE, 401),
                Arguments.of(HttpMethod.PUT, "/api/v1/customers/1", Credential.NONE, 401),
                Arguments.of(HttpMethod.DELETE, "/api/v1/customers/1", Credential.NONE, 401),
                Arguments.of(HttpMethod.GET, "/api/v1/customers", Credential.GARBAGE, 401),
                Arguments.of(HttpMethod.GET, "/api/v1/customers", Credential.EXPIRED, 401),
                Arguments.of(HttpMethod.GET, "/api/v1/customers", Credential.UNKNOWN_KEY, 401),
                Arguments.of(HttpMethod.POST, "/api/v1/customers", Credential.EXPIRED, 401),
                Arguments.of(HttpMethod.POST, "/api/v1/customers", Credential.UNKNOWN_KEY, 401),

                // --- authorization: 403 ----------------------------------
                Arguments.of(HttpMethod.POST, "/api/v1/customers", Credential.USER, 403),
                Arguments.of(HttpMethod.POST, "/api/v1/customers", Credential.NO_ROLE, 403)
        );
    }

    @ParameterizedTest(name = "{0} {1} with {2} -> {3}")
    @MethodSource("refusedRequests")
    @DisplayName("refused requests never reach the service")
    void refusedRequestsNeverReachTheService(HttpMethod method,
                                             String path,
                                             Credential credential,
                                             int expectedStatus) throws Exception {
        mockMvc.perform(authorized(request(method, path), credential)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().is(expectedStatus));

        // A valid body is attached to every row, GET and DELETE included, so a
        // rejection can never be mistaken for validation or argument resolution
        // failing: the chain stopped the request before the controller existed.
        verifyNoInteractions(customerService);
    }

    // ------------------------------------------------------------------
    // what the chain must let through
    // ------------------------------------------------------------------

    /**
     * The mirror image: the same endpoints with credentials that do satisfy the
     * rules. {@code NO_ROLE} appears on a read because {@code anyRequest()
     * .authenticated()} asks for a valid token and nothing more - only
     * {@code POST} demands a role. The actuator row covers the single
     * {@code permitAll()} matcher and is the one request that needs no token.
     */
    static Stream<Arguments> permittedRequests() {
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/api/v1/customers", Credential.USER, 200),
                Arguments.of(HttpMethod.GET, "/api/v1/customers", Credential.NO_ROLE, 200),
                Arguments.of(HttpMethod.GET, "/api/v1/customers/1", Credential.USER, 200),
                Arguments.of(HttpMethod.GET, "/api/v1/customers", Credential.ADMIN, 200),
                Arguments.of(HttpMethod.PUT, "/api/v1/customers/1", Credential.USER, 200),
                Arguments.of(HttpMethod.DELETE, "/api/v1/customers/1", Credential.USER, 204),
                Arguments.of(HttpMethod.POST, "/api/v1/customers", Credential.ADMIN, 201)
        );
    }

    @ParameterizedTest(name = "{0} {1} with {2} -> {3}")
    @MethodSource("permittedRequests")
    @DisplayName("permitted requests reach the controller")
    void permittedRequestsReachTheController(HttpMethod method,
                                             String path,
                                             Credential credential,
                                             int expectedStatus) throws Exception {
        mockMvc.perform(authorized(request(method, path), credential)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().is(expectedStatus));
    }

    @ParameterizedTest(name = "anonymous GET {0} -> 200")
    @MethodSource
    @DisplayName("actuator stays open to anonymous callers")
    void actuatorIsPermitted(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }

    /** The endpoints {@code application.yml} exposes and {@code SecurityConfig} permits. */
    static Stream<String> actuatorIsPermitted() {
        return Stream.of("/actuator/health", "/actuator/info");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Valid against {@code CustomerUpsert}'s constraints, so only security can reject it. */
    private static final String VALID_BODY = """
            {"name":"Ivan","email":"ivan@email.com"}
            """;

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder,
                                                            Credential credential) {
        String token = credential.token();
        return token == null
                ? builder
                : builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
