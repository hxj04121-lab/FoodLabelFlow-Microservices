package com.spectrace.compliance.impact;

import com.spectrace.compliance.authorization.ValidationAuthorizationPort;
import org.springframework.web.bind.annotation.*;

/** Frozen north-south impact API; every operation obtains the actor from verified JWT context. */
@RestController
@RequestMapping("/api/compliance")
public class ImpactController {
    private final ValidationAuthorizationPort authorization;
    private final ImpactQueryService queries;
    public ImpactController(ValidationAuthorizationPort authorization, ImpactQueryService queries) {
        this.authorization = authorization; this.queries = queries;
    }
    @PostMapping("/impact-previews")
    public ImpactQueryService.Preview preview(@RequestBody ImpactQueryService.PreviewRequest request) {
        return queries.preview(request, authorization.requireActor("IMPACT.RUN"));
    }
    @GetMapping("/impact-findings")
    public ImpactQueryService.Page list(@RequestParam(required = false) ImpactFinding.Kind kind,
            @RequestParam(required = false) ImpactFinding.Outcome outcome, @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {
        return queries.list(authorization.requireActor("IMPACT.RUN"), kind, outcome, limit, cursor);
    }
    @GetMapping("/impact-findings/{findingId}")
    public ImpactFinding get(@PathVariable String findingId) {
        return queries.get(findingId, authorization.requireActor("IMPACT.RUN"));
    }
}
