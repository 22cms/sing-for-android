package org.francasio.sing.util

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Clean reflection utilities replacing legacy de.robv.android.xposed.XposedHelpers.
 * Operates across full class hierarchies with accessibility flags preserved.
 */
object ReflectionUtils {

    fun findClass(className: String, classLoader: ClassLoader): Class<*>? {
        return try {
            Class.forName(className, false, classLoader)
        } catch (_: Throwable) {
            null
        }
    }

    fun findMethod(clazz: Class<*>, methodName: String, vararg paramTypes: Class<*>): Method? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            try {
                val method = current.getDeclaredMethod(methodName, *paramTypes)
                method.isAccessible = true
                return method
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        return null
    }

    fun findMethods(clazz: Class<*>, methodName: String): List<Method> {
        val matches = mutableListOf<Method>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (method in current.declaredMethods) {
                if (method.name == methodName) {
                    method.isAccessible = true
                    matches.add(method)
                }
            }
            current = current.superclass
        }
        return matches
    }

    /**
     * Update-proof lookup: find a method by its signature (return type + parameter
     * types) regardless of its (possibly obfuscated) name. Useful when R8 renames
     * methods across app updates.
     */
    fun findMethodBySignature(
        clazz: Class<*>,
        returnType: Class<*>,
        vararg paramTypes: Class<*>
    ): Method? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (method in current.declaredMethods) {
                if (method.returnType == returnType && method.parameterTypes.contentEquals(paramTypes)) {
                    method.isAccessible = true
                    return method
                }
            }
            current = current.superclass
        }
        return null
    }

    /**
     * Update-proof field lookup by type. When several fields share the type, the
     * first declared one is returned.
     */
    fun findFieldByType(clazz: Class<*>, type: Class<*>, instanceOnly: Boolean = true): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (field.type == type && (!instanceOnly || !java.lang.reflect.Modifier.isStatic(field.modifiers))) {
                    field.isAccessible = true
                    return field
                }
            }
            current = current.superclass
        }
        return null
    }

    fun findConstructor(clazz: Class<*>, vararg paramTypes: Class<*>): Constructor<*>? {
        return try {
            val ctor = clazz.getDeclaredConstructor(*paramTypes)
            ctor.isAccessible = true
            ctor
        } catch (_: NoSuchMethodException) {
            null
        }
    }

    fun findField(clazz: Class<*>, fieldName: String): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            try {
                val field = current.getDeclaredField(fieldName)
                field.isAccessible = true
                return field
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private val fieldCache = java.util.Collections.synchronizedMap(HashMap<String, java.lang.reflect.Field?>())

    fun getCachedField(clazz: Class<*>, fieldName: String): java.lang.reflect.Field? {
        val key = clazz.name + "#" + fieldName
        synchronized(fieldCache) {
            if (fieldCache.containsKey(key)) {
                return fieldCache[key]
            }
            val field = findField(clazz, fieldName)
            fieldCache[key] = field
            return field
        }
    }

    fun getObjectField(obj: Any?, fieldName: String): Any? {
        if (obj == null) return null
        return getCachedField(obj.javaClass, fieldName)?.get(obj)
    }

    fun getIntField(obj: Any?, fieldName: String): Int {
        if (obj == null) return 0
        return getCachedField(obj.javaClass, fieldName)?.getInt(obj) ?: 0
    }

    fun getFloatField(obj: Any?, fieldName: String): Float {
        if (obj == null) return 0.0f
        return getCachedField(obj.javaClass, fieldName)?.getFloat(obj) ?: 0.0f
    }

    fun getBooleanField(obj: Any?, fieldName: String): Boolean {
        if (obj == null) return false
        return getCachedField(obj.javaClass, fieldName)?.getBoolean(obj) ?: false
    }

    fun setObjectField(obj: Any?, fieldName: String, value: Any?) {
        if (obj == null) return
        getCachedField(obj.javaClass, fieldName)?.set(obj, value)
    }

    fun setIntField(obj: Any?, fieldName: String, value: Int) {
        if (obj == null) return
        getCachedField(obj.javaClass, fieldName)?.setInt(obj, value)
    }

    fun setFloatField(obj: Any?, fieldName: String, value: Float) {
        if (obj == null) return
        getCachedField(obj.javaClass, fieldName)?.setFloat(obj, value)
    }

    fun setBooleanField(obj: Any?, fieldName: String, value: Boolean) {
        if (obj == null) return
        getCachedField(obj.javaClass, fieldName)?.setBoolean(obj, value)
    }

    fun getStaticFloatField(clazz: Class<*>, fieldName: String): Float {
        return getCachedField(clazz, fieldName)?.getFloat(null) ?: 0.0f
    }

    fun setStaticFloatField(clazz: Class<*>, fieldName: String, value: Float) {
        val field = findField(clazz, fieldName) ?: return
        field.isAccessible = true
        try {
            field.setFloat(null, value)
            return
        } catch (_: Throwable) {}

        try {
            val flagsField = Field::class.java.getDeclaredField("accessFlags")
            flagsField.isAccessible = true
            val flags = flagsField.getInt(field)
            flagsField.setInt(field, flags and java.lang.reflect.Modifier.FINAL.inv())
            field.setFloat(null, value)
            return
        } catch (_: Throwable) {}

        try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = theUnsafeField.get(null)
            val staticBase = unsafeClass.getMethod("staticFieldBase", Field::class.java).invoke(unsafe, field)
            val staticOffset = unsafeClass.getMethod("staticFieldOffset", Field::class.java).invoke(unsafe, field) as Long
            unsafeClass.getMethod("putFloat", Any::class.java, java.lang.Long.TYPE, java.lang.Float.TYPE)
                .invoke(unsafe, staticBase, staticOffset, value)
        } catch (t: Throwable) {
            android.util.Log.e("AppleMusicSing", "setStaticFloatField failed for $fieldName: ${t.message}")
        }
    }

    fun getStaticObjectField(clazz: Class<*>, fieldName: String): Any? {
        return findField(clazz, fieldName)?.get(null)
    }

    private fun isCompatible(paramTypes: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (paramTypes.size != args.size) return false
        for (i in paramTypes.indices) {
            val param = paramTypes[i]
            val arg = args[i]
            if (arg == null) {
                if (param.isPrimitive) return false
            } else {
                if (param.isPrimitive) {
                    val boxed = when (param) {
                        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
                        java.lang.Byte.TYPE -> java.lang.Byte::class.java
                        java.lang.Character.TYPE -> java.lang.Character::class.java
                        java.lang.Short.TYPE -> java.lang.Short::class.java
                        java.lang.Integer.TYPE -> java.lang.Integer::class.java
                        java.lang.Long.TYPE -> java.lang.Long::class.java
                        java.lang.Float.TYPE -> java.lang.Float::class.java
                        java.lang.Double.TYPE -> java.lang.Double::class.java
                        else -> param
                    }
                    if (!boxed.isInstance(arg)) return false
                } else if (!param.isInstance(arg)) {
                    return false
                }
            }
        }
        return true
    }

    fun callMethod(obj: Any?, methodName: String, vararg args: Any?): Any? {
        if (obj == null) throw NullPointerException("callMethod called on null object for method $methodName")
        val clazz = obj.javaClass
        
        // Pass 1: find compatible method in public methods
        for (m in clazz.methods) {
            if (m.name == methodName && isCompatible(m.parameterTypes, args)) {
                m.isAccessible = true
                return m.invoke(obj, *args)
            }
        }
        // Pass 2: find compatible method in declared methods (including superclasses)
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (m in current.declaredMethods) {
                if (m.name == methodName && isCompatible(m.parameterTypes, args)) {
                    m.isAccessible = true
                    return m.invoke(obj, *args)
                }
            }
            current = current.superclass
        }
        // Pass 3: fallback to parameter count only if types couldn't be strictly verified
        for (m in clazz.methods) {
            if (m.name == methodName && m.parameterTypes.size == args.size) {
                m.isAccessible = true
                return m.invoke(obj, *args)
            }
        }
        current = clazz
        while (current != null && current != Any::class.java) {
            for (m in current.declaredMethods) {
                if (m.name == methodName && m.parameterTypes.size == args.size) {
                    m.isAccessible = true
                    return m.invoke(obj, *args)
                }
            }
            current = current.superclass
        }
        throw NoSuchMethodException("Method $methodName with ${args.size} parameters not found on ${clazz.name}")
    }
}

