package indira.yessimova.learn.service.impl;

import indira.yessimova.learn.exception.CustomerNotFoundException;
import indira.yessimova.learn.exception.EmailAlreadyUsedException;
import indira.yessimova.learn.jpa.entity.CustomerEntity;
import indira.yessimova.learn.jpa.repository.CustomerRepository;
import indira.yessimova.learn.model.Customer;
import indira.yessimova.learn.model.CustomerUpsert;
import indira.yessimova.learn.service.ICustomerService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CustomerService implements ICustomerService {

    private final CustomerRepository customerRepository;

    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Customer> findAll(Pageable pageable) {
        return customerRepository.findAll(pageable).map(this::mapToCustomer);
    }

    @Override
    @Transactional(readOnly = true)
    public Customer findById(Long id) {
        return customerRepository.findById(id)
                .map(this::mapToCustomer)
                .orElseThrow(() -> new CustomerNotFoundException(id));
    }

    @Override
    public Customer create(CustomerUpsert request) {
        customerRepository.findByEmail(request.email()).ifPresent(existing -> {
            throw new EmailAlreadyUsedException(request.email());
        });

        CustomerEntity entity = new CustomerEntity();
        entity.setName(request.name());
        entity.setEmail(request.email());

        return mapToCustomer(customerRepository.save(entity));
    }

    @Override
    public Customer update(Long id, CustomerUpsert request) {
        CustomerEntity entity = customerRepository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));

        customerRepository.findByEmail(request.email()).ifPresent(other -> {
            if (!other.getId().equals(id)) {
                throw new EmailAlreadyUsedException(request.email());
            }
        });

        entity.setName(request.name());
        entity.setEmail(request.email());

        return mapToCustomer(customerRepository.save(entity));
    }

    @Override
    public void delete(Long id) {
        if (!customerRepository.existsById(id)) {
            throw new CustomerNotFoundException(id);
        }
        customerRepository.deleteById(id);
    }

    private Customer mapToCustomer(CustomerEntity entity) {
        return new Customer(entity.getId(), entity.getName(), entity.getEmail());
    }
}
