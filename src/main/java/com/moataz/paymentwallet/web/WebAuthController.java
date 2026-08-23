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
