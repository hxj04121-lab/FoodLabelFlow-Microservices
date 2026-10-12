/**
 * Reserved for the starter security module (G1-M4.2, owner M4 Zhu Wenyu): JWT resource server,
 * organisation context ({@code org_id}, {@code org_type}, roles) and role/organisation guards.
 *
 * <p>Failures from that module must be reported through
 * {@link com.spectrace.platform.starter.error.ApiException#unauthenticated()} and
 * {@link com.spectrace.platform.starter.error.ApiException#forbidden()} (or written with
 * {@link com.spectrace.platform.starter.error.ApiErrors}) so that 401/403 use the canonical envelope.</p>
 */
package com.spectrace.platform.starter.security;
