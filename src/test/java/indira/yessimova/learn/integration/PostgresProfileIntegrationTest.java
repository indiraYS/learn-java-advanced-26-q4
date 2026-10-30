package indira.yessimova.learn.integration;

import com.zaxxer.hikari.HikariDataSource;
import indira.yessimova.learn.LearnApplication;
import indira.yessimova.learn.exception.CustomerNotFoundException;
import indira.yessimova.learn.exception.EmailAlreadyUsedException;
import indira.yessimova.learn.jpa.entity.CustomerEntity;
import indira.yessimova.learn.jpa.repository.CustomerRepository;
import indira.yessimova.learn.model.Customer;
import indira.yessimova.learn.model.CustomerUpsert;
import indira.yessimova.learn.service.ICustomerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Database integration test for the three PostgreSQL profiles - {@code dev},
 * {@code stg} and {@code prod} - against a throwaway PostgreSQL container.
 *
 * <h2>Why one parameterized test instead of three classes</h2>
 * The three profiles differ only in configuration values, so asserting the same
 * behaviour three times by hand would be pure duplication. {@code @ActiveProfiles}
 * is a class-level annotation baked into Spring's context cache key, though, so a
 * parameterized method cannot swap the profile on an injected context. The context
 * is therefore booted programmatically with
 * {@link SpringApplicationBuilder}, once per profile, inside the test method.
 *
 * <h2>Why a container rather than H2</h2>
 * These profiles target PostgreSQL. Running them on H2 would leave the real
 * dialect, driver and {@code flyway-database-postgresql} support untested, which
 * is precisely the part that differs from {@code local}. One container is shared
 * by all three profiles; Flyway migrates it on the first boot and the later boots
 * find an up-to-date schema history, which is itself worth knowing.
 *
 * <h2>What is deliberately absent</h2>
 * No HTTP client and no OAuth2 issuer. Authentication and authorization are
 * covered once, over the real filter chain, in {@code SecurityIntegrationTest}.
 * {@code SecurityConfig} does build its {@link JwtDecoder} by fetching OIDC
 * discovery <em>over the network during context refresh</em>, so {@link StubDecoder}
 * replaces that one bean to keep this test off the network.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("dev / stg / prod profiles (PostgreSQL)")
class PostgresProfileIntegrationTest {

    /**
     * Shared by every profile. Not a {@code @Container} field: Testcontainers'
     * JUnit integration would then tie its lifecycle to this class's instances,
     * whereas the contexts booted below need it alive across all invocations.
     * Ryuk removes it when the JVM exits.
     */
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("learn")
                    .withUsername("learn")
                    .withPassword("learn");

    static {
        POSTGRES.start();
    }

    /**
     * Boots the real application on one profile and exercises it end to end
     * below the web layer.
     *
     * <p>{@code stg} and {@code prod} declare {@code ${DB_URL}},
     * {@code ${DB_USERNAME}}, {@code ${DB_PASSWORD}} and {@code ${OAUTH_ISSUER_URI}}
     * with no defaults, so a context only starts if every one of them is supplied -
     * which is exactly what the command-line arguments below do, with the
     * container's own coordinates. {@code dev} defaults all four, so passing them
     * also proves the overrides win over the defaults.
     */
    @ParameterizedTest(name = "{0} profile uses {1}")
    @CsvSource({
            "dev,  learn-pool-dev",
            "stg,  learn-pool-stg",
            "prod, learn-pool-prod"
    })
    @DisplayName("migrates and serves CRUD on a real PostgreSQL")
    void profileRunsAgainstPostgres(String profile, String expectedPoolName) {
        try (ConfigurableApplicationContext context = boot(profile)) {

            // --- the intended configuration really loaded ------------------
            Environment environment = context.getEnvironment();
            assertThat(environment.getActiveProfiles()).containsExactly(profile);

            DataSource dataSource = context.getBean(DataSource.class);
            assertThat(dataSource).isInstanceOf(HikariDataSource.class);
            // The pool name is per-profile and has no default, so matching it
            // proves application-{profile}.yml was genuinely applied.
            assertThat(((HikariDataSource) dataSource).getPoolName()).isEqualTo(expectedPoolName);
            assertThat(databaseProductName(dataSource)).isEqualTo("PostgreSQL");

            // --- Flyway ----------------------------------------------------
            CustomerRepository repository = context.getBean(CustomerRepository.class);
            // ddl-auto is 'validate', so the context would not have started if
            // CustomerEntity disagreed with V1__create_customer_table.sql.
            // Clearing also isolates this profile from the previous invocation,
            // which shared the same container.
            repository.deleteAll();
            assertThat(repository.count()).isZero();

            // --- CRUD, on PostgreSQL --------------------------------------
            ICustomerService service = context.getBean(ICustomerService.class);

            Customer created = service.create(new CustomerUpsert("Ivan", "ivan@email.com"));
            assertThat(created.id()).isNotNull();
            assertThat(repository.findById(created.id()).orElseThrow().getEmail())
                    .isEqualTo("ivan@email.com");

            assertThat(service.findById(created.id())).isEqualTo(created);

            service.update(created.id(), new CustomerUpsert("Ivan Renamed", "renamed@email.com"));
            CustomerEntity reloaded = repository.findById(created.id()).orElseThrow();
            assertThat(reloaded.getName()).isEqualTo("Ivan Renamed");
            assertThat(reloaded.getEmail()).isEqualTo("renamed@email.com");

            seed(repository, "Olga", "olga@email.com");
            seed(repository, "Pyotr", "pyotr@email.com");
            Page<Customer> firstPage = service.findAll(PageRequest.of(0, 2));
            assertThat(firstPage.getTotalElements()).isEqualTo(3);
            assertThat(firstPage.getContent()).hasSize(2);

            service.delete(created.id());
            assertThat(repository.findById(created.id())).isEmpty();

            // --- the rules the database also enforces ---------------------
            assertThatThrownBy(() -> service.findById(999_999L))
                    .isInstanceOf(CustomerNotFoundException.class)
                    .hasMessage("Customer not found: 999999");

            assertThatThrownBy(() -> service.delete(999_999L))
                    .isInstanceOf(CustomerNotFoundException.class);

            // uk_customer_email would have rejected this insert anyway; the
            // service must refuse it first so the caller gets 409, not 500.
            assertThatThrownBy(() -> service.create(new CustomerUpsert("Impostor", "olga@email.com")))
                    .isInstanceOf(EmailAlreadyUsedException.class)
                    .hasMessage("Email already used: olga@email.com");
            assertThat(repository.count()).isEqualTo(2);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Starts the real {@link LearnApplication} on the given profile, pointed at
     * the container.
     *
     * <p>A servlet context is used rather than {@code WebApplicationType.NONE}
     * because {@code SecurityConfig} is annotated {@code @EnableWebSecurity} and
     * builds a {@code SecurityFilterChain} from {@code HttpSecurity}; port 0 keeps
     * the embedded server out of the way. Bean-definition overriding is enabled so
     * {@link StubDecoder} can displace the network-dialling decoder.
     */
    private static ConfigurableApplicationContext boot(String profile) {
        return new SpringApplicationBuilder(LearnApplication.class, StubDecoder.class)
                .web(WebApplicationType.SERVLET)
                .profiles(profile)
                .properties("spring.main.allow-bean-definition-overriding=true")
                .run(
                        "--server.port=0",
                        "--DB_URL=" + POSTGRES.getJdbcUrl(),
                        "--DB_USERNAME=" + POSTGRES.getUsername(),
                        "--DB_PASSWORD=" + POSTGRES.getPassword(),
                        // stg and prod leave this mandatory; the value is never
                        // dialled because StubDecoder replaces the decoder.
                        "--OAUTH_ISSUER_URI=http://localhost:1/unused"
                );
    }

    private static String databaseProductName(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            throw new IllegalStateException("could not read database metadata", e);
        }
    }

    private static void seed(CustomerRepository repository, String name, String email) {
        CustomerEntity entity = new CustomerEntity();
        entity.setName(name);
        entity.setEmail(email);
        repository.save(entity);
    }

    /**
     * Replaces {@code SecurityConfig#jwtDecoder()}, which would otherwise perform
     * OIDC discovery against a live issuer while the context refreshes. Any attempt
     * to actually use it fails loudly rather than silently accepting a token - no
     * test in this class authenticates anything.
     */
    @Configuration
    static class StubDecoder {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new JwtException("no issuer configured in PostgresProfileIntegrationTest");
            };
        }
    }
}
