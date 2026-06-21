package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class SessionController {

    @GetMapping("/api/me")
    public Map<String, Object> me(HttpSession session) {
        Map<String, Object> response = new HashMap<>();

        Object email = session.getAttribute("loggedInUserEmail");
        Object name = session.getAttribute("loggedInUserName");
        Object isAdmin = session.getAttribute("isAdmin");

        response.put("email", email);
        response.put("name", name);
        response.put("isAdmin", isAdmin != null && (Boolean) isAdmin);

        return response;
    }
}