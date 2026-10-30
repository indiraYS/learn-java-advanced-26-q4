package indira.yessimova.learn.integration;

import com.zaxxer.hikari.HikariDataSource;
import indira.yessimova.learn.exception.CustomerNotFoundException;
import indira.yessimova.learn.exception.EmailAlreadyUsedException;
import indira.yessimova.learn.jpa.entity.CustomerEntity;
import indira.yessimova.learn.jpa.repository.CustomerRepository;
import indira.yessimova.learn.model.Customer;
import indira.yessimova.learn.model.CustomerUpsert;
import indira.yessimova.learn.service.ICustomerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Database integration test for the {@code local} profile.
 *
 * <p>{@code local} is the one profile that needs no container:
 * {@code application-local.yml} points the datasource at an in-memory H2 running
 * in PostgreSQL compatibility mode ({@code MODE=PostgreSQL}), so Flyway's
 * migration SQL - written for PostgreSQL - still applies cleanly.
 *
 * <p>Scope is deliberately narrow: profile wiring, Flyway, JPA and the service's
 * behaviour against a real database. There is no HTTP client and no OAuth2
 * issuer here; authentication and authorization are covered once, over the real
 * filter chain, in {@code SecurityIntegrationTest}.
 *
 * <p>The {@link JwtDecoder} is the single exception. {@code SecurityConfig}
 * builds it with {@code JwtDecoders.fromIssuerLocation(...)}, which performs
 * OIDC discovery <em>over the network while the context refreshes</em>. Replacing
 * the bean keeps this test off the network without weakening anything it asserts.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("local profile (H2)")
class LocalProfileIntegrationTest {

    /** Keeps context refresh from dialling a real OAuth2 issuer. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ICustomerService customerService;

    @Autowired
    private CustomerRepository customerRepository;

    @BeforeEach
    void startFromAnEmptyTable() {
        // The application seeds nothing (LearnApplication#run only prints), so an
        // empty table is also the natural state of a freshly migrated database.
        customerRepository.deleteAll();
    }

    // ------------------------------------------------------------------
    // the right configuration really loaded
    // ------------------------------------------------------------------

    @Test
    @DisplayName("activates exactly the local profile")
    void activatesLocalProfile() {
        assertThat(environment.getActiveProfiles()).containsExactly("local");
    }

    @Test
    @DisplayName("builds the connection pool declared by application-local.yml")
    void buildsLocalConnectionPool() {
        // The pool name is per-profile and has no default, so matching it proves
        // application-local.yml was genuinely applied rather than some fallback.
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getPoolName()).isEqualTo("learn-pool-local");
    }

    @Test
    @DisplayName("talks to H2 in PostgreSQL compatibility mode")
    void runsAgainstH2() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("H2");
        }
    }

    @Test
    @DisplayName("Flyway created the customer table and JPA can reach it")
    void flywayMigratedTheSchema() {
        // ddl-auto is 'validate', so the context would not have started at all if
        // CustomerEntity disagreed with V1__create_customer_table.sql. Reaching
        // this point already proves the migration ran; the query proves the table
        // is usable through JPA.
        assertThat(customerRepository.count()).isZero();
    }

    // ------------------------------------------------------------------
    // CRUD against the real database
    // ------------------------------------------------------------------

    @Test
    @DisplayName("create inserts a row that is readable back through the repository")
    void createPersistsRow() {
        Customer created = customerService.create(new CustomerUpsert("Ivan", "ivan@email.com"));

        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("Ivan");

        Optional<CustomerEntity> stored = customerRepository.findById(created.id());
        assertThat(stored).isPresent();
        assertThat(stored.get().getEmail()).isEqualTo("ivan@email.com");
    }

    @Test
    @DisplayName("findAll pages over rows that are actually stored")
    void findAllPagesStoredRows() {
        seed("Ivan", "ivan@email.com");
        seed("Olga", "olga@email.com");
        seed("Pyotr", "pyotr@email.com");

        Page<Customer> firstPage = customerService.findAll(PageRequest.of(0, 2));

        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getContent()).hasSize(2);
    }

    @Test
    @DisplayName("findById returns the stored row and 404s on an unknown id")
    void findByIdReadsStoredRow() {
        Long id = seed("Ivan", "ivan@email.com").getId();

        assertThat(customerService.findById(id))
                .isEqualTo(new Customer(id, "Ivan", "ivan@email.com"));

        assertThatThrownBy(() -> customerService.findById(999_999L))
                .isInstanceOf(CustomerNotFoundException.class)
                .hasMessage("Customer not found: 999999");
    }

    @Test
    @DisplayName("update writes the new values through to the database")
    void updatePersistsChanges() {
        Long id = seed("Old Name", "old@email.com").getId();

        customerService.update(id, new CustomerUpsert("New Name", "new@email.com"));

        CustomerEntity reloaded = customerRepository.findById(id).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("New Name");
        assertThat(reloaded.getEmail()).isEqualTo("new@email.com");
    }

    @Test
    @DisplayName("delete removes the row, and 404s when there is nothing to remove")
    void deleteRemovesRow() {
        Long id = seed("Ivan", "ivan@email.com").getId();

        customerService.delete(id);

        assertThat(customerRepository.findById(id)).isEmpty();
        assertThatThrownBy(() -> customerService.delete(id))
                .isInstanceOf(CustomerNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // the uniqueness rule, against the real unique index
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rejects a duplicate email and leaves a single row behind")
    void rejectsDuplicateEmail() {
        seed("Ivan", "taken@email.com");

        assertThatThrownBy(() -> customerService.create(new CustomerUpsert("Impostor", "taken@email.com")))
                .isInstanceOf(EmailAlreadyUsedException.class)
                .hasMessage("Email already used: taken@email.com");

        // uk_customer_email would have rejected the insert anyway; this proves the
        // service refuses it first, so the caller gets 409 rather than a 500.
        assertThat(customerRepository.count()).isOne();
    }

    @Test
    @DisplayName("lets a row keep its own email on update but not steal another's")
    void enforcesEmailOwnership() {
        Long id = seed("Ivan", "ivan@email.com").getId();
        seed("Olga", "olga@email.com");

        // same row, same email: the duplicate guard must not trip
        assertThat(customerService.update(id, new CustomerUpsert("Ivan Renamed", "ivan@email.com")).name())
                .isEqualTo("Ivan Renamed");

        assertThatThrownBy(() -> customerService.update(id, new CustomerUpsert("Ivan", "olga@email.com")))
                .isInstanceOf(EmailAlreadyUsedException.class)
                .hasMessage("Email already used: olga@email.com");

        assertThat(customerRepository.findById(id).orElseThrow().getEmail())
                .isEqualTo("ivan@email.com");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Inserts a row directly, bypassing the service, to arrange test state. */
    private CustomerEntity seed(String name, String email) {
        CustomerEntity entity = new CustomerEntity();
        entity.setName(name);
        entity.setEmail(email);
        return customerRepository.save(entity);
    }
}
