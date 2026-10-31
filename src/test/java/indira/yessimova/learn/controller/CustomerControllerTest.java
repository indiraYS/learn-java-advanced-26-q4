package indira.yessimova.learn.controller;

import indira.yessimova.learn.exception.CustomerNotFoundException;
import indira.yessimova.learn.exception.EmailAlreadyUsedException;
import indira.yessimova.learn.model.Customer;
import indira.yessimova.learn.model.CustomerUpsert;
import indira.yessimova.learn.security.SecurityConfig;
import indira.yessimova.learn.service.ICustomerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web layer (controller) slice tests.
 *
 * <p>Only the MVC infrastructure is started - no database, no JPA, no Flyway.
 * {@link ICustomerService} is replaced by a Mockito mock that stubs the
 * records a database would otherwise return, so the assertions cover purely
 * web concerns: routing, JSON (de)serialisation, status codes, the Location
 * header, bean validation, exception-to-status translation and the
 * authorization rules.
 *
 * <p>{@link SecurityConfig} is imported explicitly because {@code @WebMvcTest}
 * does not component-scan plain {@code @Configuration} classes; without the
 * import the slice would fall back to Boot's default "authenticate
 * everything" chain and the ADMIN-only rule on POST would go untested.
 * The {@link JwtDecoder} is mocked so the context never tries to fetch the
 * real issuer's JWKS over the network.
 */
@WebMvcTest(CustomerController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8081/issuer-a"
})
class CustomerControllerTest {

    private static final GrantedAuthority ROLE_ADMIN = new SimpleGrantedAuthority("ROLE_ADMIN");
    private static final GrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");

    @Autowired
    private MockMvc mockMvc;

    /** Boot 4 ships Jackson 3, so the injected mapper is a {@code JsonMapper}. */
    @Autowired
    private JsonMapper jsonMapper;

    /** Stubs the database-backed service; nothing below this line touches a DB. */
    @MockitoBean
    private ICustomerService customerService;

    /** Prevents SecurityConfig#jwtDecoder from calling the real issuer. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    // ------------------------------------------------------------------
    // Access control, for every endpoint at once
    // ------------------------------------------------------------------

    /**
     * The whole access-control matrix of the controller.
     *
     * <p>{@code null} authority means the request carries no token at all, so
     * the filter chain must answer 401. The last row is the only authorization
     * rule that differs per endpoint: {@code POST} is ADMIN-only, so a USER
     * token is authenticated but not permitted and must get 403.
     */
    static Stream<Arguments> deniedRequests() {
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/api/v1/customers", null, 401),
                Arguments.of(HttpMethod.GET, "/api/v1/customers/1", null, 401),
                Arguments.of(HttpMethod.POST, "/api/v1/customers", null, 401),
                Arguments.of(HttpMethod.PUT, "/api/v1/customers/1", null, 401),
                Arguments.of(HttpMethod.DELETE, "/api/v1/customers/1", null, 401),
                Arguments.of(HttpMethod.POST, "/api/v1/customers", ROLE_USER, 403)
        );
    }

    @ParameterizedTest(name = "{0} {1} as {2} -> {3}")
    @MethodSource("deniedRequests")
    @DisplayName("denied requests never reach the service")
    void deniedRequestsNeverReachTheService(HttpMethod method,
                                            String path,
                                            GrantedAuthority authority,
                                            int expectedStatus) throws Exception {
        // A body is attached unconditionally: harmless for GET/DELETE, and it
        // proves the rejection happens in the filter chain, before any
        // argument resolution or validation could be blamed for it.
        MockHttpServletRequestBuilder builder = request(method, path)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ivan\",\"email\":\"ivan@email.com\"}");

        if (authority != null) {
            builder = builder.with(jwt().authorities(authority));
        }

        mockMvc.perform(builder).andExpect(status().is(expectedStatus));

        verifyNoInteractions(customerService);
    }

    // ------------------------------------------------------------------
    // GET /api/v1/customers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET collection returns 200 and the page of stubbed customers")
    void findAllReturnsPageOfCustomers() throws Exception {
        Pageable pageable = PageRequest.of(0, 20);
        when(customerService.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(
                List.of(
                        new Customer(1L, "Ivan", "ivan@email.com"),
                        new Customer(2L, "Olga", "olga@email.com")
                ),
                pageable,
                2
        ));

        mockMvc.perform(get("/api/v1/customers").with(jwt().authorities(ROLE_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Ivan"))
                .andExpect(jsonPath("$.content[0].email").value("ivan@email.com"))
                .andExpect(jsonPath("$.content[1].name").value("Olga"))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("GET collection passes the page/size request parameters through to the service")
    void findAllHonoursPaginationParameters() throws Exception {
        when(customerService.findAll(any(Pageable.class))).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/customers")
                        .param("page", "2")
                        .param("size", "5")
                        .with(jwt().authorities(ROLE_USER)))
                .andExpect(status().isOk());

        verify(customerService).findAll(PageRequest.of(2, 5));
    }

    // ------------------------------------------------------------------
    // GET /api/v1/customers/{id}
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET by id returns 200 and the stubbed customer")
    void findByIdReturnsCustomer() throws Exception {
        when(customerService.findById(1L)).thenReturn(new Customer(1L, "Ivan", "ivan@email.com"));

        mockMvc.perform(get("/api/v1/customers/{id}", 1L).with(jwt().authorities(ROLE_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Ivan"))
                .andExpect(jsonPath("$.email").value("ivan@email.com"));
    }

    @Test
    @DisplayName("GET by id returns 404 ProblemDetail when the service reports the customer is missing")
    void findByIdReturnsNotFound() throws Exception {
        when(customerService.findById(99L)).thenThrow(new CustomerNotFoundException(99L));

        mockMvc.perform(get("/api/v1/customers/{id}", 99L).with(jwt().authorities(ROLE_USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Customer not found: 99"));
    }

    // ------------------------------------------------------------------
    // POST /api/v1/customers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST returns 201 with the created body and a Location header for ROLE_ADMIN")
    void createReturnsCreatedForAdmin() throws Exception {
        CustomerUpsert request = new CustomerUpsert("Ivan", "ivan@email.com");
        when(customerService.create(request)).thenReturn(new Customer(1L, "Ivan", "ivan@email.com"));

        mockMvc.perform(post("/api/v1/customers")
                        .with(jwt().authorities(ROLE_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/customers/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Ivan"))
                .andExpect(jsonPath("$.email").value("ivan@email.com"));

        verify(customerService).create(request);
    }

    @Test
    @DisplayName("POST returns 409 ProblemDetail when the email is already used")
    void createReturnsConflictOnDuplicateEmail() throws Exception {
        CustomerUpsert request = new CustomerUpsert("Ivan", "taken@email.com");
        when(customerService.create(request)).thenThrow(new EmailAlreadyUsedException("taken@email.com"));

        mockMvc.perform(post("/api/v1/customers")
                        .with(jwt().authorities(ROLE_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Email already used: taken@email.com"));
    }

    @Test
    @DisplayName("POST returns 400 with field errors when the body is invalid")
    void createRejectsInvalidBody() throws Exception {
        // blank name violates @NotBlank, "not-an-email" violates @Email
        String body = """
                {"name":"  ","email":"not-an-email"}
                """;

        mockMvc.perform(post("/api/v1/customers")
                        .with(jwt().authorities(ROLE_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Validation failed"))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.email").exists());

        verify(customerService, never()).create(any());
    }

    // ------------------------------------------------------------------
    // PUT /api/v1/customers/{id}
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PUT returns 200 and the updated customer")
    void updateReturnsUpdatedCustomer() throws Exception {
        CustomerUpsert request = new CustomerUpsert("Ivan Renamed", "ivan@email.com");
        when(customerService.update(eq(1L), eq(request)))
                .thenReturn(new Customer(1L, "Ivan Renamed", "ivan@email.com"));

        mockMvc.perform(put("/api/v1/customers/{id}", 1L)
                        .with(jwt().authorities(ROLE_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Ivan Renamed"));

        verify(customerService).update(1L, request);
    }

    @Test
    @DisplayName("PUT returns 404 ProblemDetail when the customer is missing")
    void updateReturnsNotFound() throws Exception {
        CustomerUpsert request = new CustomerUpsert("X", "x@email.com");
        when(customerService.update(eq(99L), any())).thenThrow(new CustomerNotFoundException(99L));

        mockMvc.perform(put("/api/v1/customers/{id}", 99L)
                        .with(jwt().authorities(ROLE_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Customer not found: 99"));
    }

    @Test
    @DisplayName("PUT returns 400 with field errors when the body is invalid")
    void updateRejectsInvalidBody() throws Exception {
        mockMvc.perform(put("/api/v1/customers/{id}", 1L)
                        .with(jwt().authorities(ROLE_USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"email\":\"ivan@email.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());

        verify(customerService, never()).update(any(), any());
    }

    // ------------------------------------------------------------------
    // DELETE /api/v1/customers/{id}
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DELETE returns 204 with an empty body")
    void deleteReturnsNoContent() throws Exception {
        doNothing().when(customerService).delete(1L);

        mockMvc.perform(delete("/api/v1/customers/{id}", 1L).with(jwt().authorities(ROLE_USER)))
                .andExpect(status().isNoContent());

        verify(customerService).delete(1L);
    }

    @Test
    @DisplayName("DELETE returns 404 ProblemDetail when the customer is missing")
    void deleteReturnsNotFound() throws Exception {
        doThrow(new CustomerNotFoundException(99L)).when(customerService).delete(99L);

        mockMvc.perform(delete("/api/v1/customers/{id}", 99L).with(jwt().authorities(ROLE_USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Customer not found: 99"));
    }
}
