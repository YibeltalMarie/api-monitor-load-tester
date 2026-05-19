package com.api_loader.api_monitor.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@Controller
public class DashboardController {

    // ── Dashboard home
    @GetMapping("/")
    public String dashboard(Authentication authentication, Model model) {
        // Pass username to the template for the welcome message
        // Navbar uses sec:authentication="name" so we only need this here
        model.addAttribute("username", authentication.getName());

        // Group 2: model.addAttribute("recentRuns", loadTestService.getHistory(user));
        // Group 3: model.addAttribute("monitorSummary", monitorService.getEndpoints(user));

        return "dashboard";
    }

    // ── Load test pages 
    @GetMapping("/load-test/new")
    public String loadTestConfig() {
        return "load-test-config";
    }

    @GetMapping("/load-test/history")
    public String loadTestHistory() {
        return "load-test-history";
    }

    @GetMapping("/load-test/{id}/live")
    public String loadTestLive(@PathVariable UUID id, Model model) {
        model.addAttribute("testRunId", id);
        return "load-test-live";
    }

    @GetMapping("/load-test/{id}/result")
    public String loadTestResult(@PathVariable UUID id, Model model) {
        // Group 2: check if RUNNING → redirect to live page
        // Group 2: model.addAttribute("result", loadTestService.getResult(id, user));
        return "load-test-result";
    }

    // ── Monitor pages
    @GetMapping("/monitor")
    public String monitorList() {
        return "monitor-list";
    }

    @GetMapping("/monitor/{id}")
    public String monitorDetail(@PathVariable Long id, Model model) {
        model.addAttribute("endpointId", id);
        return "monitor-detail";
    }
}