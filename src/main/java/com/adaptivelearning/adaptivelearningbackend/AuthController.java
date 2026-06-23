package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;


import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;


@Controller
public class AuthController {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * Server-side secret required to create an admin account. Set this in
     * application.properties (admin.signup.key=...) or as an environment
     * variable — never hardcode a real value here or commit one to source
     * control. If left unset, admin self-registration is impossible, which
     * is the safe default.
     *
     * This replaces the previous "@admin.com email = automatic admin" rule,
     * which let ANYONE grant themselves full admin rights just by choosing
     * an email address — no verification, no approval, no gatekeeping at
     * all. That was a critical privilege-escalation hole: it didn't require
     * compromising anything, just typing a different email.
     */
    @Value("${admin.signup.key:}")
    private String adminSignupKey;

    @PostMapping("/register")
    @ResponseBody
    public Map<String, Object> registerUser(@RequestParam String fullName,
                                            @RequestParam String email,
                                            @RequestParam String password,
                                            @RequestParam(required = false) String adminKey) {
        Map<String, Object> response = new HashMap<>();

        if (fullName == null || fullName.isBlank() || email == null || email.isBlank() || password == null || password.isBlank()) {
            response.put("success", false);
            response.put("message", "Please complete all required fields.");
            return response;
        }

        Optional<User> existingUser = userRepository.findByEmailIgnoreCase(email.trim());
        if (existingUser.isPresent()) {
            response.put("success", false);
            response.put("message", "Email already exists. Please use another email.");
            return response;
        }

        // Admin accounts can ONLY be created by someone who already has the
        // server-side admin signup key (configured by whoever deploys/runs
        // the app, not chosen by the registrant). A blank/unset key means
        // this field can never match, so admin signup is fully disabled
        // until an operator deliberately sets one.
        boolean requestedAdmin = adminKey != null && !adminKey.isBlank();
        boolean adminAccount = requestedAdmin
                && adminSignupKey != null
                && !adminSignupKey.isBlank()
                && adminSignupKey.equals(adminKey);

        if (requestedAdmin && !adminAccount) {
            response.put("success", false);
            response.put("message", "Invalid admin signup key.");
            return response;
        }

        try {
            User user = new User(email.trim().toLowerCase(Locale.ROOT), passwordEncoder.encode(password), fullName.trim());
            user.setAdmin(adminAccount);
            userRepository.save(user);
            response.put("success", true);
            response.put("message", adminAccount
                    ? "Admin account created! You can now log in."
                    : "Registration successful! You can now log in.");
        } catch (DataIntegrityViolationException ex) {
            // Log the real cause server-side; never expose schema details to the client.
            System.err.println("Registration DataIntegrityViolationException: " + ex.getMessage());
            response.put("success", false);
            response.put("message", "Registration failed due to a database error. Please contact support.");
        }
        return response;
    }

    @PostMapping("/login")
    @ResponseBody
    public Map<String, Object> loginUser(@RequestParam String email,
                                         @RequestParam String password,
                                         HttpSession session,
                                         HttpServletRequest request) {

        Map<String, Object> response = new HashMap<>();

        Optional<User> userOptional = userRepository.findByEmailIgnoreCase(email.trim());

        if (userOptional.isPresent()) {
            User user = userOptional.get();

            if (passwordEncoder.matches(password, user.getPassword())) {

                session.setAttribute("loggedInUserEmail", user.getEmail());
                session.setAttribute("loggedInUserName", user.getFullName());
                session.setAttribute("isAdmin", user.isAdmin());

                // ✅ NON-FATAL IP logging (this is the fix)
                try {
                    String ip = IpBlockFilter.extractClientIp(request);
                    user.setLastKnownIp(ip);

                    // IMPORTANT: do NOT let DB failure break login
                    userRepository.save(user);

                } catch (Exception e) {
                    System.err.println("IP update failed (ignored): " + e.getMessage());
                }

                response.put("success", true);
                response.put("message", "Login successful.");
                response.put("redirect", "/dashboard.html");
                response.put("name", user.getFullName());
                response.put("email", user.getEmail());
                response.put("isAdmin", user.isAdmin());

                return response;
            }
        }

        response.put("success", false);
        response.put("message", "Invalid email or password.");
        return response;
    }

    @PostMapping("/logout")
    @ResponseBody
    public Map<String, Object> logout(HttpSession session) {
        session.invalidate();
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Logged out successfully.");
        return response;
    }
}