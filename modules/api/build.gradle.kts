plugins {
    id("java")
}

version = constants.versions.photonics.get()
group = constants.versions.mavenGroup.get()

base.archivesName = "photonics-mc-api"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17

    withSourcesJar()
}

repositories {
    providers.environmentVariable("PHOTONICS_LOCAL_MOJANG").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let {
            maven {
                name = "PhotonicsLocalMojang"
                url = uri(it)
            }
        }

    mavenCentral()
    mojang()
}

dependencies {
    implementation(sharedLibs.joml)
    implementation(sharedLibs.gson)
    implementation(sharedLibs.dataFixerUpper)
    implementation(sharedLibs.brigadier)
    implementation(sharedLibs.fastutil)
}


tasks {
    jar {
        inputs.property("archivesName", project.base.archivesName)
    }
}
