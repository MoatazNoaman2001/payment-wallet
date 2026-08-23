package com.moataz.paymentwallet.web;

import com.moataz.paymentwallet.auth.AuthCookies;
import com.moataz.paymentwallet.auth.AuthService;
import com.moataz.paymentwallet.common.error.UnauthorizedException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * @Controller, not @RestController: the return value is a view name that Thymeleaf
 * resolves to templates/<name>.html, rather than a body to serialise.
 *
 * The browser needs no JavaScript for any of this. Logging in sets the same HttpOnly
 * access-token cookie the JSON API sets, and the browser attaches it to every later
 * request on its own.
 */
@Controller
@RequiredArgsConstructor
public class WebAuthController {

    private final AuthService authService;
    private final AuthCookies authCookies;

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String email,
                        @RequestParam String password,
                        HttpServletResponse response,
                        Model model) {
        try {
            AuthService.Session session = authService.login(email, password);
            authCookies.set(response, session.accessToken(), session.refreshToken());
            // redirect after POST, so a refresh does not resubmit the form
            return "redirect:/accounts";
        } catch (UnauthorizedException ex) {
            model.addAttribute("error", "Email or password is incorrect");
            model.addAttribute("email", email);
            return "login";
        }
    }

    @PostMapping("/logout")
    public String logout(HttpServletResponse response) {
        authCookies.clear(response);
        return "redirect:/login?loggedOut";
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/accounts";
    }
}
