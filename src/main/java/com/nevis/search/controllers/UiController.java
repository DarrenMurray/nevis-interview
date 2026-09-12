package com.nevis.search.controllers;

import com.nevis.search.search.SearchService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Server-rendered UI.
 *
 * <p>Separate from the JSON controllers because it returns HTML: HTMX swaps markup into the
 * page, so these endpoints render fragments rather than data. They delegate to the same
 * {@link SearchService} as the API, so the UI cannot drift from what the API reports.
 *
 * <p>Annotated {@code @Hidden} to keep these out of the OpenAPI document — that describes the
 * API contract, and HTML fragments are an implementation detail of the page.
 */
@Controller
@Hidden
public class UiController {

    private final SearchService searchService;

    UiController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    /**
     * The "Find Clients" button.
     *
     * <p>{@code q} is optional here, unlike the JSON API which rejects a blank query with a
     * 400. A user pressing the button with an empty box should see a prompt, not an error
     * page swapped into the results area.
     */
    @GetMapping("/ui/search/clients")
    public String searchClients(@RequestParam(name = "q", required = false) String q, Model model) {
        return renderResults("clients", q, model);
    }

    /** The "Find Documents" button. */
    @GetMapping("/ui/search/documents")
    public String searchDocuments(@RequestParam(name = "q", required = false) String q, Model model) {
        return renderResults("documents", q, model);
    }

    private String renderResults(String kind, String q, Model model) {
        String query = q == null ? "" : q.trim();
        model.addAttribute("query", query);
        model.addAttribute("kind", kind);
        model.addAttribute("blank", query.isEmpty());
        model.addAttribute("results", query.isEmpty()
                ? java.util.List.of()
                : "clients".equals(kind)
                        ? searchService.searchClients(query)
                        : searchService.searchDocuments(query));
        return "fragments/results :: results";
    }
}
