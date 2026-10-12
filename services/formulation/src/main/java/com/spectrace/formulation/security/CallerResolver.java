package com.spectrace.formulation.security;

import com.spectrace.platform.starter.security.OrganisationContext;
import com.spectrace.platform.starter.security.OrganisationContexts;
import org.springframework.stereotype.Component;

/**
 * The caller as this service sees it: the starter security module's {@link OrganisationContext} (G1-M4.2)
 * plus this service's role-to-permission mapping. The starter has already validated the token and its
 * immutable sub, org_id and org_type (missing or unusable → 401 through the canonical ApiError), so this
 * only adapts the context for the application layer, which checks permissions and ownership (BR-11).
 */
@Component
public class CallerResolver {

    public Caller current() {
        OrganisationContext context = OrganisationContexts.current();
        return new Caller(context.subject(), context.organisationId(), context.organisationType().name(),
                Permission.fromRoles(context.roles()));
    }
}
