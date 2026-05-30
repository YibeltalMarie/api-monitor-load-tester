package com.api_loader.api_monitor.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.api_loader.api_monitor.model.User;
import com.api_loader.api_monitor.service.LoadTestService;
import com.api_loader.api_monitor.service.MonitorService;
import com.api_loader.api_monitor.service.UserService;

import java.util.UUID;

@Controller
public class DashboardController {

    private final LoadTestService loadTestService;
    private final MonitorService  monitorService;
    private final UserService     userService;

    public DashboardController(LoadTestService loadTestService,
                               MonitorService monitorService,
                               UserService userService) {
        this.loadTestService = loadTestService;
        this.monitorService  = monitorService;
        this.userService     = userService;
    }

    private User getUser(Authentication authentication) {
        return userService.findByUsername(authentication.getName());
    }

    // ── Dashboard home ───────────────────────────────────────────────
    //
    // WHAT monitorSummary IS:
    //   A List<EndpointStatusResponse> — the same list the monitor
    //   board uses, but passed to dashboard.html so it can show
    //   a quick overview: how many endpoints are UP, how many DOWN,
    //   which ones are currently DOWN (needs attention).
    //
    // HOW Dev C uses it in dashboard.html:
    //   Count UP:   th:text="${#lists.size(monitorSummary
    //                    .?[currentStatus == 'UP'])}"
    //   Count DOWN: th:text="${#lists.size(monitorSummary
    //                    .?[currentStatus == 'DOWN'])}"
    //   List DOWN endpoints:
    //     th:each="ep : ${monitorSummary.?[currentStatus == 'DOWN']}"
    //       → show ep.url, ep.lastCheckedAt, ep.lastErrorMsg
    @GetMapping("/")
    public String dashboard(Authentication authentication,
                            Model model) {

        User user = getUser(authentication);

        model.addAttribute("username",
                authentication.getName());

        // Recent load test runs — shown in the test history widget
        model.addAttribute("recentRuns",
                loadTestService.getHistory(user));

        // Monitor summary — shown in the monitor overview widget.
        // Same data as /api/monitor but passed server-side to
        // the template so the page renders with data on first load
        // (no extra fetch() needed for the initial render).
        model.addAttribute("monitorSummary",
                monitorService.getEndpoints(user));

        return "dashboard";
    }

    // ── Load test pages ──────────────────────────────────────────────
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
        return "load-test-result";
    }

    // ── Monitor pages ────────────────────────────────────────────────
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