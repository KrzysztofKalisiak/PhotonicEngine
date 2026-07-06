/**
 * Precompiled [photonics-fabric.gradle.kts][Photonics_fabric_gradle] script plugin.
 *
 * @see Photonics_fabric_gradle
 */
public
class PhotonicsFabricPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Photonics_fabric_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
