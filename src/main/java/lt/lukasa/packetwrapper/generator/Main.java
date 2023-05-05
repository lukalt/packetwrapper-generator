package lt.lukasa.packetwrapper.generator;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.AbstractStructure;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.injector.packet.PacketRegistry;
import com.comphenix.protocol.reflect.ExactReflection;
import com.comphenix.protocol.reflect.FuzzyReflection;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.reflect.accessors.Accessors;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import com.hubspot.jinjava.Jinjava;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
        }        String template;
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
                
                if(!processPacketType(type, context)) {
                    System.out.println("Skipping " + type);
                    return;

                }

                String renderedTemplate = jinjava.render(template, context);

                try(PrintWriter writer = new PrintWriter(new File(outputFolder, className + ".java"))) {
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

    private static boolean processPacketType(PacketType packetType, Map<String, Object> context) {
        Optional<Class<?>> optionalPacketClass = PacketRegistry.tryGetPacketClass(packetType);
        if (!optionalPacketClass.isPresent()) {
            return false;
        }
        Set<String> imports = new HashSet<>();

        List<String> handles = new ArrayList<>();
        Class<?> clazz = optionalPacketClass.get();
        if(clazz.equals(MinecraftReflection.getBundleDelimiterClass().get())) {
            clazz = MinecraftReflection.getPackedBundlePacketClass().get();
        }
        Map<Method, Integer> fieldCounter = new HashMap<>();
        for (Field field : FuzzyReflection.fromClass(clazz, true).getFields()) {
            if(Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            Map<Method, StructureModifier> modifiers = findConverters(field);


            Method method = modifiers.size() == 1 ? modifiers.keySet().iterator().next() : null;
            int fieldIndex = method == null ? -1 : fieldCounter.compute(method, (m, val) -> (val == null ? 0 : (val + 1)));

            String dataType = "Object";
            if(method != null) {
                try {
                    StructureModifier modifier = modifiers.values().iterator().next();
                    if (modifier != null) {
                        modifier.get
                        dataType = modifier.getFieldType().getName();
                        if (dataType.contains(".")) {
                            if (!dataType.startsWith("java.lang.")) {
                                imports.add(dataType);
                            }
                            dataType = dataType.substring(dataType.lastIndexOf(".") + 1);
                        }
                    }
                } catch (Throwable t) {
                    System.out.println("Failed to process:  " + packetType);
                    t.printStackTrace();
                }
            }

            String fieldName = Character.toUpperCase(field.getName().charAt(0)) + field.getName().substring(1);
            StringBuilder sb = new StringBuilder();
            sb.append("public ").append(dataType).append(" get").append(fieldName).append("() {\n");
            if(method != null) {
                sb.append("    return this.handle.").append(method.getName()).append("().read(").append(fieldIndex).append(");");
                if(method.getName().equals("getModifier")) {
                    sb.append(" // TODO: No modifier has been found for type ").append(field.getType());
                }
                sb.append("\n");
            } else {
                sb.append("// TODO: Multiple candidates found for raw type + ").append(field.getType()).append(" here:\n");
                for (Method candidate : modifiers.keySet()) {
                    sb.append("//    return this.handle.").append(candidate.getName()).append("().read(").append(fieldIndex).append(");\n");
                }
            }
            sb.append("}\n");
            handles.add(sb.toString());

            sb = new StringBuilder();
            sb.append("public void set").append(fieldName).append("(").append(dataType).append(" value) {\n");
            if(method != null) {
                sb.append("    this.handle.").append(method.getName()).append("().write(").append(fieldIndex).append(", value);\n");
            } else {
                sb.append("// TODO: Multiple candidates found for raw type + ").append(field.getType()).append(" here:\n");
                for (Method candidate : modifiers.keySet()) {
                    sb.append("//    this.handle.").append(candidate.getName()).append("().write(").append(fieldIndex).append(", value);\n");
                }
                sb.append("\n");
            }
            sb.append("}\n");
            handles.add(sb.toString());
        }


        context.put("BODY", String.join("\n", handles));
        context.put("IMPORTS", imports);
        return true;
    }

    private static Map<Method, StructureModifier> findConverters(Field targetField) {
        Map<Method, StructureModifier> candidates = new HashMap<>();
        PacketContainer dummy = new PacketContainer(PacketType.Play.Server.BUNDLE);
        Class<AbstractStructure> abstractStructureClass = AbstractStructure.class;
        Method modifierMethod = ExactReflection.fromClass(abstractStructureClass, true).getMethod("getModifier");
        for (Method method : FuzzyReflection.fromClass(abstractStructureClass, true).getMethods()) {
            if (method.getParameterTypes().length == 0
                    && method.getReturnType().equals(StructureModifier.class)
                    && !method.getName().equals("getModifier")) {
                try {
                    StructureModifier<?> modifier = (StructureModifier<?>) Accessors.getMethodAccessor(method).invoke(dummy);
                    if (modifier.getFieldType() != null && modifier.getFieldType().isAssignableFrom(targetField.getType())) {
                        candidates.put(method, modifier);
                    }
                } catch (Throwable ignored) {
                    if (skipWarned.add(method.getName())) {
                        System.out.println("Skipping method " + method.getName());
                    }
                }
            }
        }
        if(candidates.isEmpty()) {
            try {
                candidates.put(modifierMethod, (StructureModifier) modifierMethod.invoke(dummy));
            } catch (IllegalAccessException | InvocationTargetException e) {
                e.printStackTrace();
            }
        }
        return candidates;
    }
}