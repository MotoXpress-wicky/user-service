package com.ecommerce.userservice.repository;

import com.ecommerce.userservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    // roles is an array column. array_contains cannot be used here: Hibernate writes an enum literal as its number,
    // and H2 cannot guess the type of a bound value. So the roles are joined into one text (this works on H2 and
    // PostgreSQL) and we look for ",ROLE_ADMIN,". The commas make sure a longer role name cannot match by accident.
    @Query("select count(u) > 0 from User u where concat(',', array_to_string(u.roles, ','), ',') like '%,ROLE_ADMIN,%'")
    boolean existsAdmin();
}
