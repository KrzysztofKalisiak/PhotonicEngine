/**
 * Precompiled [photonics-version.gradle.kts][Photonics_version_gradle] script plugin.
 *
 * @see Photonics_version_gradle
 */
public
class PhotonicsVersionPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Photonics_version_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
