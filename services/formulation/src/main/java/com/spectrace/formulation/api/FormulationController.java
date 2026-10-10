package com.spectrace.formulation.api;

import com.spectrace.formulation.application.Commands;
import com.spectrace.formulation.application.FormulationService;
import com.spectrace.formulation.application.Views;
import com.spectrace.formulation.security.CallerResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter for contracts/openapi/formulation.v1.yaml. */
@RestController
@RequestMapping("/api/formulations")
public class FormulationController {

    private final FormulationService service;
    private final CallerResolver callers;

    public FormulationController(FormulationService service, CallerResolver callers) {
        this.service = service;
        this.callers = callers;
    }

    @GetMapping("/products")
    Views.Page<Views.Product> products(@RequestParam(required = false) Integer limit,
                                       @RequestParam(required = false) String cursor) {
        return service.products(callers.current(), limit, cursor);
    }

    @GetMapping("/products/{productId}")
    Views.Product product(@PathVariable String productId) {
        return service.product(callers.current(), productId);
    }

    @GetMapping("/products/{productId}/formula-versions")
    Views.Page<Views.FormulaVersion> formulas(@PathVariable String productId, @RequestParam(required = false) Integer limit,
                                              @RequestParam(required = false) String cursor) {
        return service.formulas(callers.current(), productId, limit, cursor);
    }

    @GetMapping("/released-specifications")
    Views.Page<Views.ReleasedSpecification> releasedSpecifications(@RequestParam(required = false) String materialId,
                                                                   @RequestParam(required = false) Integer limit,
                                                                   @RequestParam(required = false) String cursor) {
        callers.current();
        return service.releasedSpecifications(materialId, limit, cursor);
    }

    @PostMapping("/formula-versions")
    @ResponseStatus(HttpStatus.CREATED)
    Views.FormulaVersion createDraft(@RequestBody Commands.CreateFormula command) {
        return service.createDraft(callers.current(), command);
    }

    @GetMapping("/formula-versions/{formulaVersionId}")
    Views.FormulaVersion formula(@PathVariable String formulaVersionId) {
        return service.formula(callers.current(), formulaVersionId);
    }

    @PostMapping("/formula-versions/{formulaVersionId}/release")
    Views.FormulaVersion release(@PathVariable String formulaVersionId, @RequestBody Commands.ReleaseFormula command) {
        return service.release(callers.current(), formulaVersionId, command);
    }

    @GetMapping("/formula-versions/{formulaVersionId}/trace")
    Views.FormulaTrace trace(@PathVariable String formulaVersionId) {
        return service.trace(callers.current(), formulaVersionId);
    }
}
