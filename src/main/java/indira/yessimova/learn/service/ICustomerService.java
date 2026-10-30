package indira.yessimova.learn.service;

import indira.yessimova.learn.model.Customer;
import indira.yessimova.learn.model.CustomerUpsert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ICustomerService {

    Page<Customer> findAll(Pageable pageable);

    Customer findById(Long id);

    Customer create(CustomerUpsert request);

    Customer update(Long id, CustomerUpsert request);

    void delete(Long id);
}
