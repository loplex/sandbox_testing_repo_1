package cz.loplex.inspectionfilter

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.InspectionFilterBundle"

internal object InspectionFilterBundle {
    private val instance = DynamicBundle(InspectionFilterBundle::class.java, BUNDLE)

    @JvmStatic
    fun message(key: @PropertyKey(resourceBundle = BUNDLE) String, vararg params: Any?): @Nls String {
        return instance.getMessage(key, *params)
    }
}
