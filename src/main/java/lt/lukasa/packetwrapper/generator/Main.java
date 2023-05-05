package lt.lukasa.packetwrapper.generator;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.AbstractStructure;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.injector.packet.PacketRegistry;
import com.comphenix.protocol.reflect.FuzzyReflection;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.reflect.accessors.Accessors;
import com.google.common.base.Charsets;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import com.hubspot.jinjava.Jinjava;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.sqlite.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
        Jinjava jinjava = new Jinjava();
        String template;
        try {
            template = Resources.toString(Resources.getResource("templates/BaseWrapper.java.j2"), Charsets.UTF_8);
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

                try(PrintWriter writer = new PrintWriter(outputFolder, className + ".java")) {
                    writer.print(renderedTemplate);
                }
               
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        
        Bukkit.shutdown();

    }
    
    private static String createClassName(PacketType type) {
        return "Wrapper" + type.getProtocol().getPacketName() + (type.getSender() == PacketType.Sender.CLIENT ? "Client" : "Server") + type.name();
    }

    private static boolean processPacketType(PacketType packetType, Map<String, Object> context) {
        Optional<Class<?>> optionalPacketClass = PacketRegistry.tryGetPacketClass(packetType);
        if (!optionalPacketClass.isPresent()) {
            return false;
        }

        List<String> handles = new ArrayList<>();
        Class<?> clazz = optionalPacketClass.get();
        System.out.println("Processing: " + clazz + " " + packetType);
        for (Field field : FuzzyReflection.fromClass(clazz, true).getFields()) {


            Map<Method, StructureModifier> modifiers = findConverters(field);
            String fieldName = Character.toUpperCase(field.getName().charAt(0)) + field.getName().substring(1);
            StringBuilder sb = new StringBuilder();
            sb.append("public Object get" + fieldName + "() {\n");
            if(modifiers.size() == 1) {
                sb.append( "    return this.handle." + modifiers.keySet().iterator().next().getName() + "().read(0);\n");
            } else {
                sb.append("    // TODO\n");
            }
            sb.append("\n");
            handles.add(sb.toString());

            sb = new StringBuilder();
            sb.append("public void set" + fieldName + "(Object value) {\n");
            if(modifiers.size() == 1) {
                sb.append( "    return this.handle." + modifiers.keySet().iterator().next().getName() + "().write(0, value);\n");
            } else {
                sb.append("    // TODO\n");
            }
            sb.append("\n");
            handles.add(sb.toString());
        }


        context.put("BODY", String.join("\n", handles));

        return true;
    }

    private static Map<Method, StructureModifier> findConverters(Field targetField) {
        Map<Method, StructureModifier> candidates = new HashMap<>();
        PacketContainer dummy = new PacketContainer(PacketType.Play.Server.BUNDLE);
        Class<AbstractStructure> abstractStructureClass = AbstractStructure.class;
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
        return candidates;
    }
}