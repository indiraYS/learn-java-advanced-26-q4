package indira.yessimova.learn.service;

import indira.yessimova.learn.exception.CustomerNotFoundException;
import indira.yessimova.learn.exception.EmailAlreadyUsedException;
import indira.yessimova.learn.jpa.entity.CustomerEntity;
import indira.yessimova.learn.jpa.repository.CustomerRepository;
import indira.yessimova.learn.model.Customer;
import indira.yessimova.learn.model.CustomerUpsert;
import indira.yessimova.learn.service.impl.CustomerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for the service layer.
 *
 * <p>No Spring context is started here: {@link MockitoExtension} builds the
 * collaborator graph, {@code @Mock} stubs the DB access and
 * {@code @InjectMocks} wires the stub into the class under test through its
 * constructor. Everything the repository would normally read from PostgreSQL
 * or H2 is stubbed in-memory, so these tests are fast and assert business
 * rules only (mapping, not-found handling, duplicate-email handling).
 */
@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private CustomerService customerService;

    // ------------------------------------------------------------------
    // findAll
    // ------------------------------------------------------------------

    @Test
    @DisplayName("findAll maps each entity in the page to a Customer and keeps paging metadata")
    void findAllMapsPageOfEntitiesToCustomers() {
        Pageable pageable = PageRequest.of(0, 2);
        Page<CustomerEntity> stored = new PageImpl<>(
                List.of(
                        entity(1L, "Ivan", "ivan@email.com"),
                        entity(2L, "Olga", "olga@email.com")
                ),
                pageable,
                5 // pretend the DB holds 5 rows in total
        );
        when(customerRepository.findAll(pageable)).thenReturn(stored);

        Page<Customer> result = customerService.findAll(pageable);

        assertThat(result.getContent())
                .containsExactly(
                        new Customer(1L, "Ivan", "ivan@email.com"),
                        new Customer(2L, "Olga", "olga@email.com")
                );
        // paging metadata must survive the mapping
        assertThat(result.getTotalElements()).isEqualTo(5);
        assertThat(result.getTotalPages()).isEqualTo(3);
        assertThat(result.getNumber()).isZero();
        verify(customerRepository).findAll(pageable);
    }

    @Test
    @DisplayName("findAll returns an empty page when the table is empty")
    void findAllReturnsEmptyPage() {
        Pageable pageable = PageRequest.of(0, 20);
        when(customerRepository.findAll(pageable)).thenReturn(Page.empty(pageable));

        Page<Customer> result = customerService.findAll(pageable);

        assertThat(result).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    // ------------------------------------------------------------------
    // findById
    // ------------------------------------------------------------------

    @Test
    @DisplayName("findById returns the mapped customer when the row exists")
    void findByIdReturnsMappedCustomer() {
        when(customerRepository.findById(1L))
                .thenReturn(Optional.of(entity(1L, "Ivan", "ivan@email.com")));

        Customer result = customerService.findById(1L);

        assertThat(result).isEqualTo(new Customer(1L, "Ivan", "ivan@email.com"));
    }

    @Test
    @DisplayName("findById throws CustomerNotFoundException (404) when the row is missing")
    void findByIdThrowsWhenMissing() {
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.findById(99L))
                .isInstanceOf(CustomerNotFoundException.class)
                .hasMessage("Customer not found: 99")
                .extracting(e -> ((CustomerNotFoundException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Test
    @DisplayName("create saves a new entity and returns it with the generated id")
    void createSavesNewCustomer() {
        CustomerUpsert request = new CustomerUpsert("Ivan", "ivan@email.com");
        when(customerRepository.findByEmail("ivan@email.com")).thenReturn(Optional.empty());
        // simulate the DB assigning the identity value on insert
        when(customerRepository.save(any(CustomerEntity.class)))
                .thenAnswer(invocation -> {
                    CustomerEntity toSave = invocation.getArgument(0);
                    return entity(1L, toSave.getName(), toSave.getEmail());
                });

        Customer result = customerService.create(request);

        assertThat(result).isEqualTo(new Customer(1L, "Ivan", "ivan@email.com"));

        // the entity handed to the repository must carry the request values
        // and must NOT carry a client-supplied id
        ArgumentCaptor<CustomerEntity> captor = ArgumentCaptor.forClass(CustomerEntity.class);
        verify(customerRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNull();
        assertThat(captor.getValue().getName()).isEqualTo("Ivan");
        assertThat(captor.getValue().getEmail()).isEqualTo("ivan@email.com");
    }

    @Test
    @DisplayName("create throws EmailAlreadyUsedException (409) and never saves when the email is taken")
    void createRejectsDuplicateEmail() {
        CustomerUpsert request = new CustomerUpsert("Ivan", "taken@email.com");
        when(customerRepository.findByEmail("taken@email.com"))
                .thenReturn(Optional.of(entity(7L, "Someone Else", "taken@email.com")));

        assertThatThrownBy(() -> customerService.create(request))
                .isInstanceOf(EmailAlreadyUsedException.class)
                .hasMessage("Email already used: taken@email.com")
                .extracting(e -> ((EmailAlreadyUsedException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        // the uniqueness check must short-circuit before the insert
        verify(customerRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // update
    // ------------------------------------------------------------------

    @Test
    @DisplayName("update mutates the loaded entity and saves it")
    void updateMutatesExistingCustomer() {
        CustomerEntity stored = entity(1L, "Old Name", "old@email.com");
        when(customerRepository.findById(1L)).thenReturn(Optional.of(stored));
        when(customerRepository.findByEmail("new@email.com")).thenReturn(Optional.empty());
        when(customerRepository.save(any(CustomerEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Customer result = customerService.update(1L, new CustomerUpsert("New Name", "new@email.com"));

        assertThat(result).isEqualTo(new Customer(1L, "New Name", "new@email.com"));
        // the service edits the managed instance it loaded, it does not create a new row
        assertThat(stored.getName()).isEqualTo("New Name");
        assertThat(stored.getEmail()).isEqualTo("new@email.com");
        verify(customerRepository).save(stored);
    }

    @Test
    @DisplayName("update allows keeping the same email on the same row")
    void updateAllowsUnchangedEmailOnSameRow() {
        CustomerEntity stored = entity(1L, "Ivan", "ivan@email.com");
        when(customerRepository.findById(1L)).thenReturn(Optional.of(stored));
        // findByEmail returns THIS row, so the duplicate guard must not trip
        when(customerRepository.findByEmail("ivan@email.com")).thenReturn(Optional.of(stored));
        when(customerRepository.save(any(CustomerEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Customer result = customerService.update(1L, new CustomerUpsert("Ivan Renamed", "ivan@email.com"));

        assertThat(result).isEqualTo(new Customer(1L, "Ivan Renamed", "ivan@email.com"));
    }

    @Test
    @DisplayName("update throws CustomerNotFoundException (404) when the row is missing")
    void updateThrowsWhenMissing() {
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.update(99L, new CustomerUpsert("X", "x@email.com")))
                .isInstanceOf(CustomerNotFoundException.class);

        verify(customerRepository, never()).save(any());
    }

    @Test
    @DisplayName("update throws EmailAlreadyUsedException (409) when the email belongs to another row")
    void updateRejectsEmailOwnedByAnotherCustomer() {
        when(customerRepository.findById(1L))
                .thenReturn(Optional.of(entity(1L, "Ivan", "ivan@email.com")));
        when(customerRepository.findByEmail("olga@email.com"))
                .thenReturn(Optional.of(entity(2L, "Olga", "olga@email.com")));

        assertThatThrownBy(() -> customerService.update(1L, new CustomerUpsert("Ivan", "olga@email.com")))
                .isInstanceOf(EmailAlreadyUsedException.class)
                .hasMessage("Email already used: olga@email.com");

        verify(customerRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // delete
    // ------------------------------------------------------------------

    @Test
    @DisplayName("delete deletes by id when the row exists")
    void deleteRemovesExistingCustomer() {
        when(customerRepository.existsById(1L)).thenReturn(true);

        customerService.delete(1L);

        verify(customerRepository).deleteById(1L);
    }

    @Test
    @DisplayName("delete throws CustomerNotFoundException (404) and never deletes when the row is missing")
    void deleteThrowsWhenMissing() {
        when(customerRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> customerService.delete(99L))
                .isInstanceOf(CustomerNotFoundException.class)
                .hasMessage("Customer not found: 99");

        verify(customerRepository, never()).deleteById(anyLong());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Builds a persisted-looking entity, i.e. one that already has an id. */
    private static CustomerEntity entity(Long id, String name, String email) {
        CustomerEntity e = new CustomerEntity();
        e.setId(id);
        e.setName(name);
        e.setEmail(email);
        return e;
    }
}
