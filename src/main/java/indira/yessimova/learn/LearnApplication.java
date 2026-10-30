package indira.yessimova.learn;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Task 1 - Hello-world application.
 *
 * <p>The application class itself implements {@link CommandLineRunner}, so Spring Boot
 * invokes {@link #run(String...)} once the application context has been fully initialised,
 * right before the application becomes ready to serve requests.
 */
@SpringBootApplication
public class LearnApplication implements CommandLineRunner {

	private static final String GREETING = "hello world";

	public static void main(String[] args) {
		SpringApplication.run(LearnApplication.class, args);
	}

	@Override
	public void run(String... args) {
		System.out.println(GREETING);
	}
}
