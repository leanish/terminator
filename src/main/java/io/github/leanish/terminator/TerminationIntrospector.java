/*
 * Copyright (c) 2025 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.terminator;

import io.github.leanish.terminator.annotation.AwaitsTermination;
import io.github.leanish.terminator.annotation.Terminates;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

final class TerminationIntrospector {

    private TerminationIntrospector() {
    }

    static TerminationRegistration inspect(Object candidate) {
        Objects.requireNonNull(candidate, "candidate");
        NonBlockingTerminable nonBlocking = inspectNonBlocking(candidate).orElse(null);
        if (nonBlocking != null) {
            return TerminationRegistration.nonBlocking(nonBlocking);
        }
        BlockingTerminable blocking = inspectBlocking(candidate).orElse(null);
        if (blocking != null) {
            return TerminationRegistration.blocking(blocking);
        }
        return TerminationRegistration.empty();
    }

    private static Optional<NonBlockingTerminable> inspectNonBlocking(Object candidate) {
        Optional<Method> terminateMethod = locateSingleAnnotatedMethod(candidate, Terminates.class);
        Optional<Method> awaitMethod = locateSingleAnnotatedMethod(candidate, AwaitsTermination.class);
        if (awaitMethod.isEmpty()) {
            return Optional.empty();
        }
        Method terminate = terminateMethod.orElseThrow(() -> new IllegalStateException(
                "Service " + candidate + " declares @AwaitsTermination but no @Terminates method"));
        validateTerminateSignature(terminate, true);
        return Optional.of(new AnnotationDrivenNonBlockingTerminable(candidate, terminate, awaitMethod.get()));
    }

    private static Optional<BlockingTerminable> inspectBlocking(Object candidate) {
        Optional<Method> terminateMethod = locateSingleAnnotatedMethod(candidate, Terminates.class);
        Optional<Method> awaitMethod = locateSingleAnnotatedMethod(candidate, AwaitsTermination.class);
        if (awaitMethod.isPresent()) {
            return Optional.empty();
        }
        if (terminateMethod.isPresent()) {
            Method terminate = terminateMethod.get();
            validateTerminateSignature(terminate, false);
            return Optional.of(new AnnotationDrivenBlockingTerminable(candidate, terminate));
        }
        if (candidate instanceof AutoCloseable autoCloseable) {
            Method closeMethod = findMethod(autoCloseable.getClass(), "close");
            if (closeMethod != null) {
                closeMethod.setAccessible(true);
                return Optional.of(new AutoCloseableTerminable(autoCloseable, closeMethod));
            }
        }
        return Optional.empty();
    }

    private static Optional<Method> locateSingleAnnotatedMethod(Object candidate, Class<? extends Annotation> annotationType) {
        Map<MethodSignature, Method> methods = findAnnotatedMethods(candidate.getClass(), annotationType);
        if (methods.isEmpty()) {
            return Optional.empty();
        }
        if (methods.size() > 1) {
            throw new IllegalStateException("Multiple methods on " + candidate + " annotated with @" + annotationType.getSimpleName() + "; only one is supported");
        }
        Method method = methods.values().iterator().next();
        method.setAccessible(true);
        return Optional.of(method);
    }

    private static Map<MethodSignature, Method> findAnnotatedMethods(Class<?> type, Class<? extends Annotation> annotationType) {
        Map<MethodSignature, Method> result = new HashMap<>();
        for (Class<?> candidate : typeHierarchy(type)) {
            for (Method method : candidate.getDeclaredMethods()) {
                if (method.isAnnotationPresent(annotationType)) {
                    MethodSignature signature = MethodSignature.from(method);
                    result.putIfAbsent(signature, method);
                }
            }
        }
        return result;
    }

    private static List<Class<?>> typeHierarchy(Class<?> type) {
        List<Class<?>> resolved = new ArrayList<>();
        Deque<Class<?>> stack = new ArrayDeque<>();
        stack.push(type);
        while (!stack.isEmpty()) {
            Class<?> current = stack.pop();
            if (!resolved.add(current)) {
                continue;
            }
            Class<?> superClass = current.getSuperclass();
            if (superClass != null && superClass != Object.class) {
                stack.push(superClass);
            }
            for (Class<?> iface : current.getInterfaces()) {
                stack.push(iface);
            }
        }
        return resolved;
    }

    private static void validateTerminateSignature(Method method, boolean nonBlocking) {
        if (method.getParameterCount() != 0) {
            throw new IllegalStateException("Termination method " + method + " must not declare parameters");
        }
        Class<?> returnType = method.getReturnType();
        if (returnType != void.class && returnType != Void.class) {
            throw new IllegalStateException("Termination method " + method + " must return void");
        }
        if (nonBlocking && throwsInterruptedException(method)) {
            throw new IllegalStateException("Non-blocking termination method " + method + " must not declare InterruptedException");
        }
    }

    private static boolean throwsInterruptedException(Method method) {
        for (Class<?> exceptionType : method.getExceptionTypes()) {
            if (exceptionType == InterruptedException.class || InterruptedException.class.isAssignableFrom(exceptionType)) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable Method findMethod(Class<?> type, String name) {
        Set<Class<?>> visited = new HashSet<>();
        Deque<Class<?>> stack = new ArrayDeque<>();
        stack.push(type);
        while (!stack.isEmpty()) {
            Class<?> current = stack.pop();
            if (!visited.add(current)) {
                continue;
            }
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0) {
                    return method;
                }
            }
            Class<?> superClass = current.getSuperclass();
            if (superClass != null) {
                stack.push(superClass);
            }
            for (Class<?> iface : current.getInterfaces()) {
                stack.push(iface);
            }
        }
        return null;
    }

    private static final class AnnotationDrivenBlockingTerminable implements BlockingTerminable {

        private final Object target;
        private final Method terminate;

        private AnnotationDrivenBlockingTerminable(Object target, Method terminate) {
            this.target = target;
            this.terminate = terminate;
        }

        @Override
        public void terminate()
                throws InterruptedException {
            invoke(target, terminate);
        }
    }

    private static final class AnnotationDrivenNonBlockingTerminable implements NonBlockingTerminable {

        private final Object target;
        private final Method terminate;
        private final Method awaitTermination;

        private AnnotationDrivenNonBlockingTerminable(Object target, Method terminate, Method awaitTermination) {
            this.target = target;
            this.terminate = terminate;
            this.awaitTermination = awaitTermination;
        }

        @Override
        public void terminate() {
            invokeUnchecked(target, terminate);
        }

        @Override
        public boolean awaitTermination(Duration timeout)
                throws InterruptedException {
            return invokeAwait(timeout);
        }

        private boolean invokeAwait(Duration timeout)
                throws InterruptedException {
            Class<?>[] parameterTypes = awaitTermination.getParameterTypes();
            Object[] arguments;
            if (parameterTypes.length == 1 && Duration.class.isAssignableFrom(parameterTypes[0])) {
                arguments = new Object[] {timeout};
            } else if (parameterTypes.length == 2
                    && isNumericType(parameterTypes[0])
                    && TimeUnit.class.isAssignableFrom(parameterTypes[1])) {
                long nanos = saturatingToNanos(timeout);
                Object numeric = adaptNumericArgument(parameterTypes[0], nanos);
                arguments = new Object[] {numeric, TimeUnit.NANOSECONDS};
            } else {
                throw new IllegalStateException("Unsupported @AwaitsTermination signature: " + awaitTermination);
            }
            Object result = invoke(target, awaitTermination, arguments);
            if (!(result instanceof Boolean bool)) {
                throw new IllegalStateException("Await termination method " + awaitTermination + " must return boolean");
            }
            return bool;
        }
    }

    private static final class AutoCloseableTerminable implements BlockingTerminable {

        private final AutoCloseable delegate;
        private final Method closeMethod;

        private AutoCloseableTerminable(AutoCloseable delegate, Method closeMethod) {
            this.delegate = delegate;
            this.closeMethod = closeMethod;
        }

        @Override
        public void terminate()
                throws InterruptedException {
            try {
                closeMethod.invoke(delegate);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new RuntimeException(cause);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Could not invoke AutoCloseable#close", e);
            }
        }
    }

    private static Object invoke(Object target, Method method, Object... arguments)
            throws InterruptedException {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Could not invoke method: " + method, e);
        }
    }

    private static void invokeUnchecked(Object target, Method method, Object... arguments) {
        try {
            method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Could not invoke method: " + method, e);
        }
    }

    private static boolean isNumericType(Class<?> type) {
        return type == long.class
                || type == Long.class
                || type == int.class
                || type == Integer.class;
    }

    private static Object adaptNumericArgument(Class<?> targetType, long nanos) {
        long value = nanos;
        if ((targetType == int.class || targetType == Integer.class) && (nanos > Integer.MAX_VALUE || nanos < Integer.MIN_VALUE)) {
            value = nanos > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        }
        if (targetType == long.class || targetType == Long.class) {
            return value;
        }
        if (targetType == int.class || targetType == Integer.class) {
            return (int) value;
        }
        throw new IllegalArgumentException("Unsupported numeric type: " + targetType);
    }

    private static long saturatingToNanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private record MethodSignature(String name, Class<?>[] parameterTypes) {

        private static MethodSignature from(Method method) {
            return new MethodSignature(method.getName(), method.getParameterTypes());
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof MethodSignature other)) {
                return false;
            }
            if (!name.equals(other.name)) {
                return false;
            }
            if (parameterTypes.length != other.parameterTypes.length) {
                return false;
            }
            for (int i = 0; i < parameterTypes.length; i++) {
                if (parameterTypes[i] != other.parameterTypes[i]) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            int result = name.hashCode();
            for (Class<?> parameterType : parameterTypes) {
                result = 31 * result + parameterType.hashCode();
            }
            return result;
        }
    }
}
