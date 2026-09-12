package com.nevis.search.controllers;

import com.nevis.search.search.SearchService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Server-rendered UI. Returns HTML fragments for htmx to swap in, backed by the same
 * {@link SearchService} as the API. {@code @Hidden} keeps these out of the OpenAPI document.
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
     * The "Find Clients" button. {@code q} is optional here, unlike the JSON API: an empty box
     * should render a prompt rather than swap a 400 into the results area.
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
