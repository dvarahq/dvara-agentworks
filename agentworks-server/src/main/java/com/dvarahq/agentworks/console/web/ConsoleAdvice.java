package com.dvarahq.agentworks.console.web;

import com.dvarahq.agentworks.api.web.ApiFallbackExceptionHandler;
import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.identity.web.AuthController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The model and error handling of every Console page (issue #36). It runs before the API's fallback handler, and only
 * for the Console's controllers, so a Console request always gets an HTML answer: a full error page, or, for an htmx
 * action, a problem fragment that the page shows next to the form.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice(assignableTypes = {ConsoleSessionController.class, ConsoleAgentsController.class,
        ConsoleDeploymentsController.class, ConsoleRunsController.class, ConsoleInteractionsController.class,
        ConsoleSessionsController.class, ConsoleOperationsController.class,
        ConsoleArtifactsController.class, ConsoleMcpController.class, ConsoleWorkspaceController.class})
public final class ConsoleAdvice {
    private static final Logger LOG = LoggerFactory.getLogger(ConsoleAdvice.class);

    private final ConsoleApi api;

    public ConsoleAdvice(ConsoleApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    /**
     * The session every signed-in page shows; {@code null} on the sign-in page, and for actions, which render no page
     * (so an action costs no reads beyond its own).
     */
    @ModelAttribute("console")
    public ConsoleSupport.ConsoleContext console(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) return null;
        if (ConsoleSupport.htmx(request)) return fragmentContext();
        return context(request);
    }

    /**
     * An htmx fragment (a summary or actions refresh) renders no navigation, so it needs only the permissions that
     * decide which actions it offers, which the authenticated principal already carries: no API call is made.
     */
    private static ConsoleSupport.ConsoleContext fragmentContext() {
        if (!(SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                instanceof AuthenticatedPrincipal principal)) {
            return null;
        }
        Set<String> permissions = new LinkedHashSet<>();
        if (!principal.mustChangePassword()) principal.permissions().forEach(permission -> permissions.add(permission.name()));
        return new ConsoleSupport.ConsoleContext(principal.username(), principal.workspace().map(
                workspace -> workspace.value()).orElse(null), null, principal.role() == null ? null
                : principal.role().name(), permissions, principal.organizationAdmin(), List.of(), "{}");
    }

    private ConsoleSupport.ConsoleContext context(HttpServletRequest request) {
        if (!(SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                instanceof AuthenticatedPrincipal principal)) {
            return null;
        }
        AuthController.SessionView session = api.session(request, principal);
        List<AuthController.WorkspaceView> workspaces = principal.mustChangePassword() ? List.of()
                : api.workspaces(request, principal);
        String workspaceName = workspaces.stream().filter(workspace -> workspace.workspaceId().equals(
                session.workspaceId())).map(AuthController.WorkspaceView::name).findFirst().orElse(null);
        return new ConsoleSupport.ConsoleContext(session.username(), session.workspaceId(), workspaceName,
                session.role(), new LinkedHashSet<>(session.permissions()), session.organizationAdmin(), workspaces,
                ConsoleSupport.csrfHeaders(session.csrfToken()));
    }

    @ExceptionHandler(ConsoleProblemException.class)
    public String problem(ConsoleProblemException refused, HttpServletRequest request, HttpServletResponse response,
            Model model) {
        return render(refused.problem(), request, response, model);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            org.springframework.web.multipart.MultipartException.class})
    public String malformed(Exception malformed, HttpServletRequest request, HttpServletResponse response, Model model) {
        return render(new ConsoleProblems.ProblemView(400, "MALFORMED_REQUEST", "The request could not be read.",
                List.of(), null, null), request, response, model);
    }

    @ExceptionHandler(Exception.class)
    public String unexpected(Exception failure, HttpServletRequest request, HttpServletResponse response, Model model) {
        String errorId = UUID.randomUUID().toString();
        ApiFallbackExceptionHandler.logUnexpected(LOG, "unexpected Console failure", errorId, failure);
        return render(new ConsoleProblems.ProblemView(500, "INTERNAL_ERROR", "An unexpected error occurred.",
                List.of(), errorId, null), request, response, model);
    }

    private String render(ConsoleProblems.ProblemView problem, HttpServletRequest request,
            HttpServletResponse response, Model model) {
        response.setStatus(problem.status());
        model.addAttribute("problem", problem);
        if (ConsoleSupport.htmx(request)) return "console/fragments :: problem";
        // A full error page keeps the navigation, the user, and sign-out; if the session cannot be read either, it is
        // shown without them.
        try {
            model.addAttribute("console", context(request));
        } catch (RuntimeException unavailable) {
            model.addAttribute("console", null);
        }
        return "console/error";
    }
}
