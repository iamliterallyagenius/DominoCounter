package com.example.dominocounter.util

import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.viewbinding.ViewBinding
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * ViewBinding that clears itself when the Fragment's *view* is destroyed.
 *
 * A Fragment outlives its view (back stack, config change), so holding the binding in a
 * plain field leaks the whole view hierarchy. This binds lazily from `requireView()` and
 * drops the reference on `onDestroyView`, which removes the `_binding` / `binding!!`
 * boilerplate from every screen.
 *
 * Requires the Fragment(@LayoutRes) constructor so a view always exists by onViewCreated.
 */
class FragmentViewBindingDelegate<T : ViewBinding>(
    fragment: Fragment,
    private val bind: (View) -> T
) : ReadOnlyProperty<Fragment, T> {

    private var binding: T? = null

    init {
        fragment.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                fragment.viewLifecycleOwnerLiveData.observe(fragment) { viewOwner ->
                    viewOwner?.lifecycle?.addObserver(object : DefaultLifecycleObserver {
                        override fun onDestroy(owner: LifecycleOwner) {
                            binding = null
                        }
                    })
                }
            }
        })
    }

    override fun getValue(thisRef: Fragment, property: KProperty<*>): T =
        binding ?: bind(thisRef.requireView()).also { binding = it }
}

fun <T : ViewBinding> Fragment.viewBinding(bind: (View) -> T) =
    FragmentViewBindingDelegate(this, bind)
