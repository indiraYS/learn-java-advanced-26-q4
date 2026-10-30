package indira.yessimova.learn.exception;

import org.springframework.http.HttpStatus;

public class EmailAlreadyUsedException extends ApplicationException {

    public EmailAlreadyUsedException(String email) {
        super("Email already used: " + email);
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.CONFLICT;
    }
}
