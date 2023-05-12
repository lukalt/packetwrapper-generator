package lt.lukasa.packetwrapper.generator;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.AbstractStructure;
import com.comphenix.protocol.events.InternalStructure;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.injector.packet.PacketRegistry;
import com.comphenix.protocol.reflect.EquivalentConverter;
import com.comphenix.protocol.reflect.FuzzyReflection;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.reflect.accessors.Accessors;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.Converters;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.io.Resources;
import com.hubspot.jinjava.Jinjava;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nullable;
import javax.lang.model.type.ReferenceType;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Lukas Alt
 * @since ${DATE}
 */
public class Main extends JavaPlugin {
    private static Set<String> skipWarned = new HashSet<>();

    @Override
    public void onEnable() {

        File outputFolder = new File("output");
        outputFolder.mkdirs();
        Jinjava jinjava;
        ClassLoader curClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());
            jinjava = new Jinjava();
        } finally {
            Thread.currentThread().setContextClassLoader(curClassLoader);
        }
        String template;
        try {
            template = Resources.toString(Main.class.getResource("/templates/BaseWrapper.java.j2"), Charsets.UTF_8);
            for (PacketType type : PacketType.values()) {
                if (!type.isSupported()) {
                    continue;
                }

                Map<String, Object> context = new HashMap<>();
                String className = createClassName(type);
                context.put("CLASS_NAME", className);
                context.put("IMPORTS", Collections.emptyList());

                if (!processPacketType(type, context)) {
                    System.out.println("Skipping " + type);
                    return;

                }

                String renderedTemplate = jinjava.render(template, context);

                try (PrintWriter writer = new PrintWriter(new File(outputFolder, className + ".java"))) {
                    writer.print(renderedTemplate);
                }

            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


        Bukkit.shutdown();

    }

    private static String formatPacketName(String name) {
        return Arrays.stream(name.split("_")).map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase()).collect(Collectors.joining(""));
    }

    private static String createClassName(PacketType type) {
        return "Wrapper" + type.getProtocol().getPacketName() + (type.getSender() == PacketType.Sender.CLIENT ? "Client" : "Server") + formatPacketName(type.name());
    }

    private static String reconstructPacketTypeAccess(PacketType packetType) {
        String direction = packetType.getSender() == PacketType.Sender.CLIENT ? "Client" : "Server";
        String stage = switch (packetType.getProtocol()) {
            case PLAY -> "Play";
            case LOGIN -> "Login";
            case STATUS -> "Status";
            case HANDSHAKING -> "Handshake";
            case LEGACY -> "LEGACY";
        };
        return stage + "." + direction + "." + packetType.name();
    }

    private static String formatClassName(String dataType, Set<String> imports) {
        if (dataType.startsWith("[")) {
            dataType = Utils.parseByteCodeType(dataType);
        }
        dataType = dataType.replace('$', '.');
        if (dataType.contains(".")) {
            if (!dataType.startsWith("java.lang.")) {
                String importName = dataType;
                while (importName.endsWith("[]")) {
                    importName = importName.substring(0, importName.length() - 2);
                }
                imports.add(importName);
            }
            dataType = dataType.substring(dataType.lastIndexOf(".") + 1);
        }
        return dataType;
    }

    private static boolean processPacketType(PacketType packetType, Map<String, Object> context) {
        Optional<Class<?>> optionalPacketClass = PacketRegistry.tryGetPacketClass(packetType);
        if (!optionalPacketClass.isPresent()) {
            return false;
        }

        context.put("PACKET_TYPE", reconstructPacketTypeAccess(packetType));
        Set<String> imports = new HashSet<>();

        List<String> handles = new ArrayList<>();
        Class<?> clazz = optionalPacketClass.get();
        if (clazz.equals(MinecraftReflection.getBundleDelimiterClass().get())) {
            clazz = MinecraftReflection.getPackedBundlePacketClass().get();
        }
        Map<String, Integer> fieldCounter = new HashMap<>();
        int globalNonPrimitiveFieldIndex = 0;
        for (Field field : FuzzyReflection.fromClass(clazz, true).getFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            List<MethodHandle> modifiers = findConverters(field, imports);


            MethodHandle handle = modifiers.isEmpty() ? null : modifiers.iterator().next();
            int fieldIndex = handle == null ? globalNonPrimitiveFieldIndex : fieldCounter.compute(handle.methodName, (m, val) -> (val == null ? 0 : (val + 1)));
            if (handle == null) {
                handle = MethodHandle.createFallback();
            }
            String dataType = formatClassName(handle.dataType, imports);
            String methodName = handle.methodName;

            String formattedFieldName = Character.toUpperCase(field.getName().charAt(0)) + field.getName().substring(1);
            StringBuilder sb = new StringBuilder();

            sb.append("/**\n");
            sb.append("/* Retrieves the value of field '").append(field.getName()).append("'\n");
            if (methodName.equals("getStructures")) {
                sb.append("/* ProtocolLib currently does not provide a wrapper for this type. Access to this type is only provided by an InternalStructure").append("\n");
            }
            sb.append("/* @return '").append(field.getName()).append("'\n");
            sb.append("*/").append("\n");
            sb.append("public ").append(dataType).append(" get").append(formattedFieldName).append("() {\n");
            if(modifiers.size() > 1) {
                sb.append("   // TODO: Multiple modifier have been found for type ").append(field.getType()).append(" Generic type: ").append((field.getGenericType() instanceof ParameterizedType parameterizedType ? Arrays.toString(parameterizedType.getActualTypeArguments()) : field.getGenericType().toString())).append("\n");
                for(int i = 1; i < modifiers.size(); i++) {
                    sb.append("   // ").append(modifiers.get(i).dataType).append(" ").append(modifiers.get(i).methodName).append("\n");
                }
            }
            sb.append("    return this.handle.").append(methodName).append("(").append(handle.methodArgumentString).append(").read(").append(fieldIndex).append(");");
            if (methodName.equals("getStructures")) {
                sb.append(" // TODO: No specific modifier has been found for type ").append(field.getType()).append(" Generic type: ").append((field.getGenericType() instanceof ParameterizedType parameterizedType ? Arrays.toString(parameterizedType.getActualTypeArguments()) : field.getGenericType().toString()));
            }
            sb.append("\n");
            sb.append("}\n");
            handles.add(sb.toString());

            sb = new StringBuilder();
            sb.append("/**\n");
            sb.append("/* Sets the value of field '").append(field.getName()).append("'\n");
            if (methodName.equals("getStructures")) {
                sb.append("/* ProtocolLib currently does not provide a wrapper for this type. Access to this type is only provided by an InternalStructure").append("\n");
            }
            sb.append("/* @param value New value for field '").append(field.getName()).append("'\n");
            sb.append("*/").append("\n");
            boolean optional = dataType.startsWith("Optional");
            String paramType = dataType;
            if(optional) {
                imports.add("javax.annotation.Nullable");
                paramType = "@Nullable " + dataType.substring(dataType.indexOf("<") + 1, dataType.lastIndexOf(">"));
            }
            sb.append("public void set").append(formattedFieldName).append("(").append(paramType).append(" value) {\n");
            String valHandle = "value";
            if(optional) {
                valHandle = "Optional.ofNullable(value)";
            }

            sb.append("    this.handle.").append(methodName).append("(").append(handle.methodArgumentString).append(").write(").append(fieldIndex).append(",").append(valHandle).append(");");

            if (methodName.equals("getStructures")) {
                sb.append(" // TODO: No specific modifier has been found for type ").append(field.getType()).append(" Generic type: ").append((field.getGenericType() instanceof ParameterizedType parameterizedType ? Arrays.toString(parameterizedType.getActualTypeArguments()) : field.getGenericType().toString()));
            }
            sb.append("\n");
            sb.append("}\n");
            handles.add(sb.toString());
            if(!field.getType().isPrimitive()) {
                globalNonPrimitiveFieldIndex++;
            }
        }


        context.put("BODY", handles.stream().map(s ->
                Arrays.stream(s.split("\n")).map(m -> Strings.repeat(" ", 4) + m).collect(Collectors.joining("\n")))
                .collect(Collectors.joining("\n\n")));
        context.put("IMPORTS", imports);
        return true;
    }

    private static class MethodHandle {
        private final String dataType;
        private final String methodName;
        private final String methodArgumentString;

        public MethodHandle(String dataType, String methodName, String methodArgumentString) {
            this.dataType = dataType;
            this.methodName = methodName;
            this.methodArgumentString = methodArgumentString;
        }

        public static MethodHandle fromSpecific(Method method, StructureModifier modifier) throws Exception {
            Field f = StructureModifier.class.getDeclaredField("converter");
            f.setAccessible(true);
            EquivalentConverter converter = (EquivalentConverter) f.get(modifier);
            String dataType;
            if (converter != null && converter.getSpecificType() != null) {
                dataType = converter.getSpecificType().getName();
            } else {
                dataType = modifier.getFieldType().getName();
            }
            return new MethodHandle(dataType, method.getName(), "");
        }

        public static MethodHandle createFallback() {
            return new MethodHandle("com.comphenix.protocol.events.InternalStructure", "getStructures", "");
        }

    }

    private static List<MethodHandle> findConverters(Field targetField, Set<String> imports) {
        return findConverters(targetField.getType(), (targetField.getGenericType() instanceof ParameterizedType parameterizedType ? parameterizedType.getActualTypeArguments() : new Type[0]), imports);
    }

    private static List<MethodHandle> findConverters(Class targetType, Type[] genericType, Set<String> imports) {
        if(targetType == Optional.class) {
            imports.add("java.util.Optional");
            String genericTypeStr = "com.comphenix.protocol.events.InternalStructure";
            String converter = "InternalStructure.getConverter()";
            if(genericType.length == 1) {
                Type t = genericType[0];
                if(t instanceof Class clazz) {
                    if (clazz.isPrimitive() || clazz.getName().startsWith("java.")) {
                        genericTypeStr = clazz.getName();
                        imports.add("com.comphenix.protocol.wrappers.Converters");
                        converter = "Converters.passthrough(" + formatClassName(clazz.getName(), imports) + ".class)";
                    } else {
                        converter = converter + " /* TODO: could not determine converter for " + clazz.getName() + " */";
                    }
                }
            }

            if(genericTypeStr.contains(".")) {
                genericTypeStr = formatClassName(genericTypeStr, imports);
            }
            return List.of(new MethodHandle("Optional<" + genericTypeStr + ">", "getOptionals", converter));
        }
        List<MethodHandle> exactCandidates = new ArrayList<>();
        List<MethodHandle> candidates = new ArrayList<>();
        PacketContainer dummy = new PacketContainer(PacketType.Play.Server.BUNDLE);
        Class<AbstractStructure> abstractStructureClass = AbstractStructure.class;
        for (Method method : FuzzyReflection.fromClass(abstractStructureClass, true).getMethods()) {
            if (method.getParameterTypes().length == 0
                    && method.getReturnType().equals(StructureModifier.class)
                    && !method.getName().equals("getModifier")) {
                try {
                    StructureModifier<?> modifier = (StructureModifier<?>) Accessors.getMethodAccessor(method).invoke(dummy);
                    if (modifier.getFieldType() != null && modifier.getFieldType().isAssignableFrom(targetType)) {
                        final MethodHandle e = MethodHandle.fromSpecific(method, modifier);
                        if(modifier.getFieldType() == targetType) {
                            exactCandidates.add(e);
                        } else {
                            candidates.add(e);
                        }
                    }
                } catch (Throwable ignored) {
                    if (skipWarned.add(method.getName())) {
                        System.out.println("Skipping method " + method.getName());
                    }
                }
            }
        }
        return exactCandidates.isEmpty() ? candidates : exactCandidates;
    }
}