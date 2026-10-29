package com.dvarahq.agentworks.console.web;

import com.dvarahq.agentworks.identity.web.AuthController;
import com.dvarahq.agentworks.identity.web.CurrentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Objects;

/**
 * Console sign-in, sign-out, password change, and workspace switching (issue #36), through the same session API as
 * {@code /api/v1/auth}: the same throttles, lockout, uniform failures, cookie, rotation, and audit.
 */
@Controller
public final class ConsoleSessionController {
    private final ConsoleApi api;

    public ConsoleSessionController(ConsoleApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @GetMapping("/console")
    public ResponseEntity<Void> home(HttpServletRequest request) {
        return ConsoleSupport.redirect(request, "/console/dashboard");
    }

    @GetMapping("/console/login")
    public String loginPage() {
        return "console/login";
    }

    /**
     * Signs in with a plain form, so it works before any script has loaded. Sign-in has no session and so no CSRF
     * token; a submission from another site is refused by its {@code Origin}. Every failure shows the API's uniform
     * answer, never which part was wrong.
     */
    @PostMapping("/console/login")
    public Object login(HttpServletRequest request, HttpServletResponse response,
            @RequestParam(name = "username", required = false) String username,
            @RequestParam(name = "password", required = false) String password, Model model) {
        if (!ConsoleSupport.sameOrigin(request)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            model.addAttribute("problem", new ConsoleProblems.ProblemView(403, "CROSS_ORIGIN_REQUEST",
                    "Sign in from this server's own sign-in page.", java.util.List.of(), null, null));
            return "console/login";
        }
        try {
            // The API treats a missing field as a wrong one: the same uniform failure, and the same throttles.
            AuthController.SessionView session = api.login(request, response, username == null ? "" : username,
                    password == null ? "" : password);
            return ConsoleSupport.redirect(request, session.mustChangePassword() ? "/console/password" : "/console/dashboard");
        } catch (ConsoleProblemException refused) {
            response.setStatus(refused.problem().status());
            model.addAttribute("problem", refused.problem());
            return "console/login";
        }
    }

    @PostMapping("/console/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        api.logout(request, CurrentPrincipal.require(), response);
        return ConsoleSupport.redirect(request, "/console/login");
    }

    /**
     * The password form. A request with a query string is refused rather than served: only a form submitted without
     * its script (a GET, which the form no longer allows) would put passwords there, and a page must never echo it.
     */
    @GetMapping("/console/password")
    public String passwordPage(HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw new ConsoleProblemException(new ConsoleProblems.ProblemView(400, "CREDENTIAL_IN_URL",
                    "Passwords must never be sent in a URL. Change the password with the form.", java.util.List.of(),
                    null, null));
        }
        return "console/password";
    }

    /** Changes the password; the API revokes every session of the user and issues this browser a fresh one. */
    @PostMapping("/console/password")
    public ResponseEntity<Void> changePassword(HttpServletRequest request, HttpServletResponse response,
            @RequestParam(name = "currentPassword", required = false) String current,
            @RequestParam(name = "newPassword", required = false) String replacement) {
        // A blank field is a form error, never a failed password verification that would count toward lockout.
        if (current == null || current.isEmpty() || replacement == null || replacement.isEmpty()) {
            throw ConsoleSupport.invalid("INVALID_REQUEST", "Enter your current and your new password.");
        }
        api.changePassword(request, CurrentPrincipal.require(), response, current, replacement);
        return ConsoleSupport.redirect(request, "/console/dashboard");
    }

    /** Switches to another of the user's workspaces; the API issues a new session value, so the page reloads. */
    @PostMapping("/console/workspace")
    public ResponseEntity<Void> selectWorkspace(HttpServletRequest request, HttpServletResponse response,
            @RequestParam(name = "workspaceId", required = false) String workspaceId) {
        var target = ConsoleSupport.uuid(workspaceId, "workspaceId");
        if (target == null) throw ConsoleSupport.invalid("INVALID_REQUEST", "Choose a workspace.");
        api.selectWorkspace(request, CurrentPrincipal.require(), response, target);
        return ConsoleSupport.redirect(request, "/console/dashboard");
    }
}
