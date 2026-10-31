package com.ifp.auth.web;

import com.ifp.auth.config.AuthServerProperties;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The sign-in form. Spring Security processes {@code POST /login} and redirects failures to {@code /login?error}.
 * The page links back to the Angular app, whose address is the SPA client's first allowed origin.
 */
@Controller
public class LoginPageController {

    private final String appUrl;

    public LoginPageController(AuthServerProperties properties) {
        this.appUrl = stripTrailingSlash(properties.spa().allowedOrigins().getFirst());
    }

    @GetMapping("/login")
    @PreAuthorize("permitAll()")
    public String login(Model model) {
        model.addAttribute("appUrl", appUrl);
        model.addAttribute("registerUrl", appUrl + "/register");
        return "login";
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
