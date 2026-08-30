package com.example.difyraglab.web;

import com.example.difyraglab.rewrite.QueryRewriteService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Query Rewrite 调试与可观测性接口。
 */
@RestController
@RequestMapping("/api/rag/rewrite")
public class RewriteController {

    private final QueryRewriteService queryRewriteService;

    public RewriteController(QueryRewriteService queryRewriteService) {
        this.queryRewriteService = queryRewriteService;
    }

    public record RewriteRequest(@NotBlank String query) {
    }

    @PostMapping
    public Map<String, Object> rewrite(@RequestBody RewriteRequest req) {
        QueryRewriteService.RewriteResult result = queryRewriteService.rewriteWithMetadata(req.query());
        return Map.of(
                "original", req.query(),
                "rewritten", result.query(),
                "metadata_filters", result.metadataConditions() == null ? List.of() : result.metadataConditions(),
                "changed", !req.query().equals(result.query())
        );
    }

    @GetMapping
    public String rewriteGet(@RequestParam @NotBlank String query) {
        return queryRewriteService.rewrite(query);
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return queryRewriteService.metrics();
    }
}
