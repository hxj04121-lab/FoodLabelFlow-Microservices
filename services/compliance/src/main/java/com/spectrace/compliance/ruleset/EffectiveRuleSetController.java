package com.spectrace.compliance.ruleset;

import com.spectrace.compliance.authorization.ValidationAuthorizationPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal Label Workflow lookup; the active rule set remains owned by Compliance. */
@RestController
@RequestMapping("/internal/rule-sets")
public class EffectiveRuleSetController {

    private final ValidationAuthorizationPort authorization;
    private final EffectiveRuleSetService ruleSets;

    public EffectiveRuleSetController(ValidationAuthorizationPort authorization, EffectiveRuleSetService ruleSets) {
        this.authorization = authorization;
        this.ruleSets = ruleSets;
    }

    @GetMapping("/effective")
    public EffectiveRuleSet effective(@RequestParam String jurisdiction) {
        authorization.requireActor("LABEL.CREATE");
        return ruleSets.resolve(jurisdiction);
    }
}
