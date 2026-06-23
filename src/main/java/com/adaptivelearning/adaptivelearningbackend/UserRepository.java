package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    // Case-insensitive lookup only — this is the variant AuthController uses
    // for both registration (duplicate check) and login. A case-sensitive
    // findByEmail() used to exist here but had no callers anywhere in the
    // project, and keeping it around invited exactly the kind of confusion
    // where someone reads this interface, sees both methods, and assumes
    // the case-sensitive one might still be wired up somewhere.
    Optional<User> findByEmailIgnoreCase(String email);
}