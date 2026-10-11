package com.spectrace.platform.starter.security;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Injects the current {@link OrganisationContext} into a controller method, so a service never has
 * to take organisation identity from a request body or a caller-supplied field (BR-11).
 *
 * <p>Usage: declare the parameter and guard inside the handler.</p>
 *
 * <pre>{@code
 * @GetMapping("/api/labels")
 * List<Label> list(OrganisationContext caller) {
 *     caller.requireRole("LABEL_OFFICER");
 *     return labels.findByOrganisation(caller.organisationId());
 * }
 * }</pre>
 */
public class OrganisationContextArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return OrganisationContext.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return OrganisationContexts.current();
    }
}
