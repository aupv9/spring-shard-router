package org.springframework.boot.starter.sharding.aop;

import org.springframework.boot.starter.sharding.jpa.ShardBy;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Utility for extracting shard keys from method invocations based on {@link ShardBy} metadata.
 *
 * <p>Supports three extraction modes:
 * <ol>
 *   <li><b>First long parameter</b> — when {@code @ShardBy} has no {@code value}, the first
 *       {@code long}/{@code Long} parameter is used directly.</li>
 *   <li><b>Named parameter</b> — when {@code value} is set and {@code fromEntity} is false,
 *       the parameter whose name matches {@code value} is cast to {@code Long}.</li>
 *   <li><b>Entity field</b> — when {@code fromEntity} is true, the parameter matching
 *       {@code value} is treated as an object and the field named {@code value} is read
 *       via reflection.</li>
 * </ol>
 */
public class ShardKeyExtractor {

    private static final ParameterNameDiscoverer DISCOVERER = new DefaultParameterNameDiscoverer();

    /**
     * Extract the shard key from the arguments of a method call.
     *
     * @param method the intercepted method
     * @param args   the runtime arguments
     * @param annotation the {@link ShardBy} annotation on the method
     * @return the resolved shard key as a {@code long}
     * @throws ShardKeyExtractionException if extraction fails
     */
    public static long extract(Method method, Object[] args, ShardBy annotation) {
        String keyName = annotation.value();
        boolean fromEntity = annotation.fromEntity();

        if (keyName.isEmpty()) {
            return extractFirstLongParameter(method, args);
        }

        if (fromEntity) {
            return extractFromEntityField(method, args, keyName);
        }

        return extractNamedParameter(method, args, keyName);
    }

    private static long extractFirstLongParameter(Method method, Object[] args) {
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == long.class || types[i] == Long.class) {
                return toLong(args[i], method, "first long parameter");
            }
        }
        throw new ShardKeyExtractionException(
            "@ShardBy on method '" + method.getName() + "' requires a long/Long parameter " +
            "but none was found. Specify value() or add a long parameter.");
    }

    private static long extractNamedParameter(Method method, Object[] args, String name) {
        String[] names = DISCOVERER.getParameterNames(method);
        if (names == null) {
            throw new ShardKeyExtractionException(
                "Cannot resolve parameter names for method '" + method.getName() +
                "'. Ensure the class is compiled with -parameters or use fromEntity=true.");
        }
        for (int i = 0; i < names.length; i++) {
            if (name.equals(names[i])) {
                return toLong(args[i], method, "parameter '" + name + "'");
            }
        }
        throw new ShardKeyExtractionException(
            "@ShardBy(\"" + name + "\") on method '" + method.getName() +
            "': no parameter named '" + name + "' found. Available: " + String.join(", ", names));
    }

    private static long extractFromEntityField(Method method, Object[] args, String fieldName) {
        String[] names = DISCOVERER.getParameterNames(method);
        // With fromEntity=true, look for the first non-primitive parameter
        // whose class has a field matching fieldName. Fall back to first object param.
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (args[i] == null) continue;
            Field field = findField(args[i].getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                try {
                    return toLong(field.get(args[i]), method, "field '" + fieldName + "' on " + args[i].getClass().getSimpleName());
                } catch (IllegalAccessException e) {
                    throw new ShardKeyExtractionException(
                        "Cannot access field '" + fieldName + "' on " + args[i].getClass().getSimpleName(), e);
                }
            }
        }
        throw new ShardKeyExtractionException(
            "@ShardBy(value=\"" + fieldName + "\", fromEntity=true) on method '" + method.getName() +
            "': no parameter has a field named '" + fieldName + "'.");
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static long toLong(Object value, Method method, String location) {
        if (value instanceof Long l) return l;
        if (value instanceof Number n) return n.longValue();
        throw new ShardKeyExtractionException(
            "@ShardBy on method '" + method.getName() + "': " + location +
            " must be long/Long but was " + (value == null ? "null" : value.getClass().getName()));
    }
}
