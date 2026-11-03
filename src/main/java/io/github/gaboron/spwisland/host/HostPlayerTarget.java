// SPDX-License-Identifier: GPL-3.0-only
// Structural discovery adapted from GaBoron/SPW-Lyrics (MIT), Copyright (c) 2026 GaBoron.
// The original license is retained in licenses/SPW-Lyrics-MIT.txt.
package io.github.gaboron.spwisland.host;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.net.JarURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;

/** Finds the host navigation composition and the expanded-player state without assuming obfuscated names. */
public record HostPlayerTarget(Member composition, String caller, String navigator,
                               Member playerState, Member expanded, List<Member> locals) {
    public record Member(String owner, String name, String descriptor) {}
    private record Body(Member member, List<Member> calls, List<Member> fields) {}
    private static final String COMPOSE = "(Landroidx/compose/runtime/Composer;I)V";
    private static final String STACK = "()Landroidx/navigation3/runtime/NavBackStack;";

    public static HostPlayerTarget discover(Class<?> host) throws Exception {
        return discover(source(host));
    }

    public static HostPlayerTarget discover(Path archive) throws IOException {
        var bodies = new ArrayList<Body>();
        if (Files.isDirectory(archive)) {
            try (var files = Files.walk(archive)) {
                var entries = files.filter(p -> p.toString().endsWith(".class")).iterator();
                while (entries.hasNext()) parse(Files.readAllBytes(entries.next()), bodies);
            }
        } else try (var jar = new JarFile(archive.toFile())) {
            for (var entry : Collections.list(jar.entries())) {
                if (!entry.getName().endsWith(".class")) continue;
                try (var input = jar.getInputStream(entry)) { parse(input.readAllBytes(), bodies); }
            }
        }
        var content = unique(bodies.stream().filter(b -> b.member.descriptor.equals(COMPOSE)
            && b.calls.stream().anyMatch(c -> c.owner.startsWith("androidx/navigation3/ui/")
                && c.descriptor.contains("Landroidx/compose/runtime/Composer;"))).toList(), "navigation content");
        var root = unique(bodies.stream().filter(b -> b.member.owner.equals(content.member.owner)
            && b.member.descriptor.equals(COMPOSE) && b.calls.stream().anyMatch(c ->
                c.owner.equals("androidx/compose/animation/SharedTransitionScopeKt"))).toList(), "navigation root");
        var stack = unique(content.calls.stream().filter(c -> c.descriptor.equals(STACK)).distinct().toList(),
            "navigation stack");
        var back = unique(bodies.stream().filter(b -> b.member.owner.equals(stack.owner)
            && b.member.descriptor.equals("()V")
            && b.calls.stream().anyMatch(c -> c.owner.equals("kotlin/collections/CollectionsKt")
                && c.name.equals("removeLastOrNull"))
            && b.calls.stream().noneMatch(c -> c.owner.equals("androidx/navigation3/runtime/NavBackStack")
                && c.name.equals("add"))).toList(), "navigation back");
        var player = unique(back.fields.stream().filter(f -> f.owner.equals(stack.owner)
            && back.calls.stream().anyMatch(c -> c.descriptor.equals("()Z")
                && f.descriptor.equals("L" + c.owner + ";"))).distinct().toList(), "player state");
        var expanded = unique(back.calls.stream().filter(c -> c.descriptor.equals("()Z")
            && player.descriptor.equals("L" + c.owner + ";")).distinct().toList(), "expanded player");
        var locals = content.fields.stream().filter(f -> f.owner.equals(root.member.owner)
            && f.descriptor.equals("Landroidx/compose/runtime/ProvidableCompositionLocal;")).distinct().toList();
        if (locals.isEmpty()) throw new IOException("Navigation composition locals are missing");
        var caller = unique(bodies.stream().filter(b -> !b.member.owner.equals(root.member.owner)
            && b.calls.contains(root.member) && b.member.descriptor.contains("Landroidx/compose/runtime/Composer;"))
            .map(b -> b.member.owner).distinct().toList(), "navigation caller");
        long sites = bodies.stream().filter(b -> b.member.owner.equals(caller))
            .flatMap(b -> b.calls.stream()).filter(root.member::equals).count();
        if (sites != 1) throw new IOException("Navigation call site is not unique: " + sites);
        return new HostPlayerTarget(root.member, caller, stack.owner, player, expanded, locals);
    }

    private static Path source(Class<?> host) throws Exception {
        var location = host.getProtectionDomain().getCodeSource();
        if (location != null && location.getLocation().getProtocol().equals("file"))
            return Path.of(location.getLocation().toURI());
        var resource = host.getResource("/" + host.getName().replace('.', '/') + ".class");
        if (resource != null && resource.getProtocol().equals("jar")) {
            var archive = ((JarURLConnection) resource.openConnection()).getJarFileURL();
            if (archive.getProtocol().equals("file")) return Path.of(archive.toURI());
        }
        throw new IOException("Host code source is unavailable");
    }

    private static void parse(byte[] bytes, List<Body> bodies) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        // Inspect relevant bytecode only; parsing does not initialize or execute host classes.
        var pool = new String(bytes, StandardCharsets.ISO_8859_1);
        if (!pool.contains("androidx/compose/runtime/Composer") && !pool.contains("androidx/navigation3/runtime/NavBackStack"))
            return;
        var type = ClassFile.of().parse(bytes);
        var owner = type.thisClass().asInternalName();
        for (var method : type.methods()) {
            if (method.code().isEmpty()) continue;
            var calls = new ArrayList<Member>();
            var fields = new ArrayList<Member>();
            for (var instruction : method.code().get()) {
                if (instruction instanceof InvokeInstruction call)
                    calls.add(new Member(call.owner().asInternalName(), call.name().stringValue(), call.type().stringValue()));
                if (instruction instanceof FieldInstruction field)
                    fields.add(new Member(field.owner().asInternalName(), field.name().stringValue(), field.type().stringValue()));
            }
            bodies.add(new Body(new Member(owner, method.methodName().stringValue(), method.methodType().stringValue()),
                List.copyOf(calls), List.copyOf(fields)));
        }
    }

    private static <T> T unique(List<T> matches, String role) throws IOException {
        if (matches.size() != 1) throw new IOException(role + ": expected one match, found " + matches.size());
        return matches.getFirst();
    }
}
