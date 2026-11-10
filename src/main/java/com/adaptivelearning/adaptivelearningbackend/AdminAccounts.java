package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Single place that answers "is this email an admin account?" for the usage limits (daily action quotas and the
 * per-student AI budget). Admins are exempt from those; every student-facing limit is unchanged.
 *
 * Fails closed: if the lookup throws, the account is treated as a normal student, so a database hiccup can never
 * switch limits off for anyone.
 */
@Component
public class AdminAccounts {

    @Autowired
    private UserRepository users;

    public boolean isAdmin(String email) {
        if (email == null || email.isBlank()) return false;
        try {
            return users.findByEmailIgnoreCase(email.trim()).map(User::isAdmin).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }
}
