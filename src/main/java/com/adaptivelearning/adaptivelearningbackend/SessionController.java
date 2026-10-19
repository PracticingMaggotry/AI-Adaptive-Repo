package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class SessionController {

    @Autowired private ConsentService consentService;

    @GetMapping("/api/me")
    public Map<String, Object> me(HttpSession session) {
        Map<String, Object> response = new HashMap<>();

        Object email = session.getAttribute("loggedInUserEmail");
        Object name = session.getAttribute("loggedInUserName");
        Object isAdmin = session.getAttribute("isAdmin");
        boolean admin = isAdmin != null && (Boolean) isAdmin;

        response.put("email", email);
        response.put("name", name);
        response.put("isAdmin", admin);
        // Enrolled study participants can't upload their own handouts; the UI uses this to hide upload controls.
        response.put("isParticipant", !admin && email instanceof String s && consentService.isParticipant(s));

        return response;
    }
}
