package indira.yessimova.learn.exception;

import org.springframework.http.HttpStatus;

public class CustomerNotFoundException extends ApplicationException {

    public CustomerNotFoundException(Long id) {
        super("Customer not found: " + id);
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.NOT_FOUND;
    }
}
