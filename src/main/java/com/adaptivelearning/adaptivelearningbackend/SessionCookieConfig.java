package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.boot.web.servlet.server.Session;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SessionCookieConfig implements WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> {

    @Value("${app.cookie.secure:true}")
    private boolean secureCookies;

    @Override
    public void customize(ConfigurableServletWebServerFactory factory) {
        Session session = new Session();
        Session.Cookie cookie = session.getCookie();
        cookie.setSecure(secureCookies);
        cookie.setHttpOnly(true);
        cookie.setSameSite(Session.Cookie.SameSite.LAX);
        factory.setSession(session);
    }
}