#ifndef PH_RADIANCE_UTILITY_INCLUDE
#define PH_RADIANCE_UTILITY_INCLUDE

const float PH_HISTORY_MAX_RADIANCE = 65504.0f;

vec3 ph_restir_sanitize_radiance(vec3 value) {
    if (any(isnan(value)) || any(isinf(value)))
        return vec3(0.0f);

    return clamp(
        value,
        vec3(0.0f),
        vec3(PH_HISTORY_MAX_RADIANCE)
    );
}

float ph_restir_sanitize_variance(float value) {
    if (isnan(value) || isinf(value))
        return 0.0f;

    return clamp(value, 0.0f, PH_HISTORY_MAX_RADIANCE);
}

#endif
