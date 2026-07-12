#file "/lib/lighting/forwardLighting.glsl"

#replace "vec3 sceneLighting = mix(ambientCol * lightmap.y, lightCol, fullShadow * shadowMult);"
vec3 sceneLighting = mix(ambientCol * lightmap.y, lightCol, fullShadow * shadowMult);
#endreplace

#replace "vec3 blockLighting = blocklightCol * newLightmap * newLightmap;"
vec3 blockLighting = blocklightCol * newLightmap * newLightmap;
#endreplace
