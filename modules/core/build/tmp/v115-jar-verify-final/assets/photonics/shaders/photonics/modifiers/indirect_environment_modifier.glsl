void modify_indirect_environment(
        inout vec3 sun_color,
        inout vec3 sky_color
) {
#if defined PHOTONICS_IN_USE
    // Photon stores its current direct and hemispherical environment lighting
    // in fixed texels. The split GI pass cannot inherit values assigned by the
    // earlier fragment-data program, so load them explicitly for this program.
#if defined OVERWORLD
    sun_color = texelFetch(colortex4, ivec2(191, 0), 0).rgb;
#if defined SH_SKYLIGHT
    sky_color = texelFetch(colortex4, ivec2(191, 11), 0).rgb;
#else
    sky_color = texelFetch(colortex4, ivec2(191, 1), 0).rgb;
#endif
#else
    sky_color = mix(
        texelFetch(colortex4, ivec2(191, 1), 0).rgb,
        vec3(1.0f),
        0.5f
    );
    sun_color = sky_color;
#endif
#endif
}
