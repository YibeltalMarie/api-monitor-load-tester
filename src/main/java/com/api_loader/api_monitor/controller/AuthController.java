package com.api_loader.api_monitor.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.api_loader.api_monitor.dto.SignupRequest;
import com.api_loader.api_monitor.exception.UsernameAlreadyExistsException;
import com.api_loader.api_monitor.service.UserService;

@Controller
@RequestMapping("/auth")
public class AuthController {
    private final UserService userService;

    // Spring injects UserService automatically
    public AuthController(UserService userService) {
        this.userService = userService;
    }

    //Serve login page
    @GetMapping("/login")
    public String loginPage() {
        return "auth/login";   // maps to templates/auth/login.html
    }

    // Serve signup page 
    @GetMapping("/signup")
    public String signupPage() {
        return "auth/signup";  // maps to templates/auth/signup.html
    }

    //Handle signup form POST
    @PostMapping("/signup")
    public String handleSignup(@ModelAttribute SignupRequest request) {
        try {
            userService.register(request);
            // if success, tell login page to show a success message
            return "redirect:/auth/login?registered=true";

        } catch (UsernameAlreadyExistsException e) {
            // if username taken, tell signup page to show an error
            return "redirect:/auth/signup?error=username_taken";
        }
    }
}
