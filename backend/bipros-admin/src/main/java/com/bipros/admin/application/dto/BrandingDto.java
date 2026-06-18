package com.bipros.admin.application.dto;

/**
 * Public branding payload — the active theme's logos + app name. All fields may be
 * null/default when the active theme has no uploaded logo (e.g. a predefined theme).
 */
public record BrandingDto(
        String logoLight,
        String logoDark,
        String appNamePrimary,
        String appNameSecondary) {
}
