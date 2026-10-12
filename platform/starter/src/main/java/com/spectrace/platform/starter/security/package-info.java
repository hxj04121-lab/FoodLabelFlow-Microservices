/**
 * The starter security module (G1-M4.2, owner M4 Zhu Wenyu): JWT resource server, organisation
 * context ({@code org_id}, {@code org_type}, roles) and role/organisation guards.
 *
 * <p>The access token is validated against the realm issuer and JWKS, then turned into an
 * {@link com.spectrace.platform.starter.security.OrganisationContext}. Every rejection is reported
 * through the canonical {@code ApiError} envelope: 401 {@code AUTHENTICATION_REQUIRED} for a
 * missing, malformed, expired or untrusted token, and 403 {@code AUTHORIZATION_DENIED} for a
 * caller that lacks the role or does not own the organisation.</p>
 *
 * <p>Configure it with {@code spectrace.security.*} and, once the realm mints one, an
 * {@code audience}. The {@code X-Auth-*} bridge is local/test only.</p>
 */
package com.spectrace.platform.starter.security;
