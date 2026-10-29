package com.ecommerce.userservice.config.bootstrap;

import com.ecommerce.userservice.repository.AdminAuditLogRepository;
import com.ecommerce.userservice.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/** Two copies of the service start together: the loser of the race must not crash. */
class AdminBootstrapRaceTest {

    @Test
    @DisplayName("a unique-email conflict while creating the account does not stop the service from starting")
    @SuppressWarnings("unchecked")
    void lostRaceIsNotAnError() {
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
                .when(transaction).executeWithoutResult(any(Consumer.class));

        AdminBootstrap bootstrap = new AdminBootstrap(
                new AdminBootstrapProperties("boss@example.com", "a-long-password", "Administrator"),
                mock(UserRepository.class), mock(AdminAuditLogRepository.class), mock(PasswordEncoder.class), transaction);

        assertThatCode(() -> bootstrap.run(null)).doesNotThrowAnyException();
    }
}
