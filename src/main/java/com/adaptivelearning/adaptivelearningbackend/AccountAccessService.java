package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Single source of truth for "is this email allowed to use the app right now" (banned or suspended). */
@Service
public class AccountAccessService {

    @Autowired private UserRepository userRepository;
    @Autowired private BannedEmailRepository bannedEmailRepository;

    public static class AccessResult {
        public final boolean blocked;
        public final String reason;
        AccessResult(boolean blocked, String reason) { this.blocked = blocked; this.reason = reason; }
        static final AccessResult OK = new AccessResult(false, null);
    }

    /** Checked at login/registration time. */
    public AccessResult checkEmail(String email) {
        if (email == null || email.isBlank()) return AccessResult.OK;
        if (bannedEmailRepository.existsByEmailIgnoreCase(email.trim())) {
            return new AccessResult(true,
                    "This email has been banned from the platform. Contact support if you believe this is a mistake.");
        }
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email.trim());
        if (userOpt.isPresent() && userOpt.get().isArchived()) {
            String reason = userOpt.get().getArchiveReason();
            return new AccessResult(true, "This account has been suspended."
                    + (reason != null && !reason.isBlank() ? " Reason: " + reason : "")
                    + " Contact support if you believe this is a mistake.");
        }
        return AccessResult.OK;
    }

    /** Checked on every authenticated request — catches a ban/suspension applied mid-session. */
    public boolean isCurrentlyBlocked(String email) {
        return checkEmail(email).blocked;
    }
}