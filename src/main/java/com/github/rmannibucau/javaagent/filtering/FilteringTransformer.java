package com.github.rmannibucau.javaagent.filtering;

import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.toSet;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ASM7;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.NEW;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

class FilteringTransformer implements ClassFileTransformer {
    private final Predicate<String> matcher;

    FilteringTransformer(final Map<String, String> configuration) {
        final Collection<Predicate<String>> exclude = toPredicateCollection(configuration.get("exclude"));
        final Collection<Predicate<String>> include = toPredicateCollection(configuration.get("include"));
        if (exclude.isEmpty() && include.isEmpty()) {
            matcher = null;
        } else {
            final Predicate<String> includeMatcher = name -> include.stream().anyMatch(it -> it.test(name));
            final Predicate<String> excludeMatcher = name -> exclude.stream().anyMatch(it -> it.test(name));
            if (include.isEmpty()) {
                matcher = name -> !excludeMatcher.test(name);
            } else if (exclude.isEmpty()) {
                matcher = includeMatcher;
            } else {
                final String order = configuration.getOrDefault("order", "exclude-include");
                switch (order) {
                    case "include-exclude":
                        matcher = name -> includeMatcher.test(name) || !excludeMatcher.test(name);
                        break;
                    case "exclude-include":
                        matcher = name -> includeMatcher.test(name) && !excludeMatcher.test(name);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown order value: " + order);
                }
            }
        }
    }

    // supports direct value (prefix), prefix:value, regex:value
    private Collection<Predicate<String>> toPredicateCollection(final String raw) {
        return raw == null ? emptySet() : Stream.of(raw.split(","))
                .map(String::trim)
                .filter(it -> !it.isEmpty())
                .map(v -> {
                    final int sep = v.indexOf(':');
                    if (sep > 0) {
                        final String type = v.substring(0, sep);
                        final String value = v.substring(sep + 1);
                        switch (type) {
                            case "regex":
                                final Pattern pattern = Pattern.compile(value);
                                return (Predicate<String>) n -> pattern.matcher(n).matches();
                            case "prefix":
                                return (Predicate<String>) n -> n.startsWith(value);
                            default:
                                throw new IllegalArgumentException("Unsupported matcher: " + type);
                        }
                    }
                    // default to prefix
                    return (Predicate<String>) n -> n.startsWith(v);
                })
                .collect(toSet());
    }

    @Override
    public byte[] transform(final ClassLoader loader, final String className,
                            final Class<?> classBeingRedefined, final ProtectionDomain protectionDomain,
                            final byte[] classfileBuffer) throws IllegalClassFormatException {
        if (className == null || matcher == null) {
            return classfileBuffer;
        }
        final String fqn = className.replace('/', '.');
        if (matcher.test(fqn)) {
            final ClassReader reader = new ClassReader(classfileBuffer);
            final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(final String type1, final String type2) {
                    Class<?> c, d;
                    try {
                        c = findClass(loader, type1.replace('/', '.'));
                        d = findClass(loader, type2.replace('/', '.'));
                    } catch (final Exception e) {
                        throw new RuntimeException(e.toString());
                    } catch (final ClassCircularityError e) {
                        return "java/lang/Object";
                    }
                    if (c.isAssignableFrom(d)) {
                        return type1;
                    }
                    if (d.isAssignableFrom(c)) {
                        return type2;
                    }
                    if (c.isInterface() || d.isInterface()) {
                        return "java/lang/Object";
                    } else {
                        do {
                            c = c.getSuperclass();
                        } while (!c.isAssignableFrom(d));
                        return c.getName().replace('.', '/');
                    }
                }

                private Class<?> findClass(final ClassLoader tccl, final String name)
                        throws ClassNotFoundException {
                    try {
                        return className.equals(name) ? Object.class : Class.forName(name, false, tccl);
                    } catch (final ClassNotFoundException e) {
                        return Class.forName(className, false, getClass().getClassLoader());
                    }
                }
            };
            reader.accept(new ClassVisitor(ASM7, writer) {
                private boolean hasStaticInit = false;

                @Override
                public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                                 final String signature, final String[] exceptions) {
                    final MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if ("<clinit>".equals(name)) {
                        hasStaticInit = true;
                        throwException(visitor);
                        return null;
                    }
                    return visitor;
                }

                @Override
                public void visitEnd() {
                    if (!hasStaticInit) {
                        throwException(super.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null));
                    }
                    super.visitEnd();
                }

                private void throwException(final MethodVisitor visitor) {
                    visitor.visitCode();
                    visitor.visitTypeInsn(NEW, "java/lang/IllegalStateException");
                    visitor.visitInsn(DUP);
                    visitor.visitLdcInsn("Forbidden class '" + fqn + "'");
                    visitor.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
                    visitor.visitInsn(ATHROW);
                    visitor.visitMaxs(0, 0);
                    visitor.visitEnd();
                }
            }, ClassReader.SKIP_FRAMES);
            return writer.toByteArray();
        }
        return classfileBuffer;
    }
}
