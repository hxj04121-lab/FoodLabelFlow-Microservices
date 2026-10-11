package com.spectrace.specification.api;

import com.spectrace.specification.application.Commands;
import com.spectrace.specification.application.SpecificationService;
import com.spectrace.specification.application.Views;
import com.spectrace.specification.security.CallerResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter for contracts/openapi/specification.v1.yaml. */
@RestController
@RequestMapping("/api/specifications")
public class SpecificationController {

    private final SpecificationService service;
    private final CallerResolver callers;

    public SpecificationController(SpecificationService service, CallerResolver callers) {
        this.service = service;
        this.callers = callers;
    }

    @GetMapping("/suppliers")
    Views.Page<Views.Supplier> suppliers(@RequestParam(required = false) Integer limit,
                                         @RequestParam(required = false) String cursor) {
        callers.current();
        return service.suppliers(limit, cursor);
    }

    @GetMapping("/suppliers/{supplierId}")
    Views.Supplier supplier(@PathVariable String supplierId) {
        callers.current();
        return service.supplier(supplierId);
    }

    @GetMapping("/materials")
    Views.Page<Views.Material> materials(@RequestParam(required = false) String supplierId,
                                         @RequestParam(required = false) Integer limit,
                                         @RequestParam(required = false) String cursor) {
        callers.current();
        return service.materials(supplierId, limit, cursor);
    }

    @PostMapping("/materials")
    @ResponseStatus(HttpStatus.CREATED)
    Views.Material createMaterial(@RequestBody Commands.CreateMaterial command) {
        return service.createMaterial(callers.current(), command);
    }

    @GetMapping("/materials/{materialId}")
    Views.Material material(@PathVariable String materialId) {
        callers.current();
        return service.material(materialId);
    }

    @GetMapping("/ingredients")
    Views.Page<Views.Ingredient> ingredients(@RequestParam(name = "q", required = false) String prefix,
                                             @RequestParam(required = false) Integer limit,
                                             @RequestParam(required = false) String cursor) {
        callers.current();
        return service.ingredients(prefix, limit, cursor);
    }

    @GetMapping("/versions")
    Views.Page<Views.SpecificationVersion> versions(@RequestParam(required = false) String materialId,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) Integer limit,
                                                    @RequestParam(required = false) String cursor) {
        return service.versions(callers.current(), materialId, status, limit, cursor);
    }

    @PostMapping("/versions")
    @ResponseStatus(HttpStatus.CREATED)
    Views.SpecificationVersion createDraft(@RequestBody Commands.CreateSpecification command) {
        return service.createDraft(callers.current(), command);
    }

    @GetMapping("/versions/{specificationVersionId}")
    Views.SpecificationVersion version(@PathVariable String specificationVersionId) {
        return service.version(callers.current(), specificationVersionId);
    }

    @PostMapping("/versions/{specificationVersionId}/release")
    Views.SpecificationVersion release(@PathVariable String specificationVersionId) {
        return service.release(callers.current(), specificationVersionId);
    }
}
