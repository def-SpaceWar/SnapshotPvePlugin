package com.defspacemine.snapshotpve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class PveExecuteTest {
    private static final class Fixture {
        final List<World> worlds = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        CommandSender dispatchedSender;
        boolean dispatchResult = true;

        final CommandSender sender = proxy(CommandSender.class, (method, args) -> {
            if (method.equals("sendMessage")) {
                messages.add((String) args[0]);
                return null;
            }
            throw new AssertionError("Unexpected sender method: " + method);
        });
        final Server server = proxy(Server.class, (method, args) -> {
            if (method.equals("getWorlds")) {
                return worlds;
            }
            if (method.equals("dispatchCommand")) {
                dispatchedSender = (CommandSender) args[0];
                commands.add((String) args[1]);
                return dispatchResult;
            }
            throw new AssertionError("Unexpected server method: " + method);
        });
        final Plugin plugin = proxy(Plugin.class, (method, args) -> {
            if (method.equals("getServer")) {
                return server;
            }
            if (method.equals("getLogger")) {
                return Logger.getLogger("PveExecuteTest");
            }
            throw new AssertionError("Unexpected plugin method: " + method);
        });
        final PveExecute executor = new PveExecute(plugin);

        @SafeVarargs
        final void addWorld(Set<String>... tags) {
            List<Entity> entities = new ArrayList<>();
            for (Set<String> entityTags : tags) {
                entities.add(proxy(Entity.class, (method, args) -> {
                    if (method.equals("getScoreboardTags")) {
                        return entityTags;
                    }
                    throw new AssertionError("Unexpected entity method: " + method);
                }));
            }
            worlds.add(proxy(World.class, (method, args) -> {
                if (method.equals("getEntities")) {
                    return entities;
                }
                throw new AssertionError("Unexpected world method: " + method);
            }));
        }

        void run(String... args) {
            executor.onCommand(sender, null, "pveexecute", args);
        }
    }

    @FunctionalInterface
    private interface Handler {
        Object invoke(String method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (instance, method, args) -> handler.invoke(method.getName(), args)));
    }

    @Test
    void checksAllWorldsAndDispatchesOnceAsOriginalSender() {
        Fixture fixture = new Fixture();
        fixture.addWorld(Set.of("other"));
        fixture.addWorld(Set.of("playingPVE"), Set.of("playingPVE"));
        fixture.run("if", "tag=playingPVE", "run", "/execute", "if", "block", "~", "~", "~",
            "minecraft:crimson_button", "run", "setblock", "~", "~", "~", "air");
        assertEquals(List.of("execute if block ~ ~ ~ minecraft:crimson_button run setblock ~ ~ ~ air"),
            fixture.commands);
        assertSame(fixture.sender, fixture.dispatchedSender);
        assertTrue(fixture.messages.isEmpty());
    }

    @Test
    void noMatchDispatchesNothingAndRepeatsCanRecheck() {
        Fixture fixture = new Fixture();
        fixture.addWorld(Set.of("other"));
        fixture.run("if", "tag=playingPVE", "run", "say", "hello");
        assertTrue(fixture.commands.isEmpty());
        assertTrue(fixture.messages.isEmpty());
        fixture.addWorld(Set.of("playingPVE"));
        fixture.run("if", "tag=playingPVE", "run", "say", "hello");
        assertEquals(List.of("say hello"), fixture.commands);
    }

    @Test
    void unlessDispatchesOnceOnlyWhenNoLoadedEntityHasTag() {
        Fixture fixture = new Fixture();
        fixture.addWorld(Set.of("other"));
        fixture.run("unless", "tag=playingPVE", "run", "setblock", "~", "~", "~", "air");
        assertEquals(List.of("setblock ~ ~ ~ air"), fixture.commands);
        assertSame(fixture.sender, fixture.dispatchedSender);
        fixture.addWorld(Set.of("playingPVE"));
        fixture.run("unless", "tag=playingPVE", "run", "setblock", "~", "~", "~", "air");
        assertEquals(1, fixture.commands.size());
        assertTrue(fixture.messages.isEmpty());
    }

    @Test
    void rejectsMalformedInputAndReportsDispatchFailure() {
        Fixture fixture = new Fixture();
        fixture.addWorld(Set.of("playingPVE"));
        fixture.run("if", "tag=", "run", "say", "hello");
        fixture.run("if", "tag=playingPVE", "run");
        fixture.run("invalid", "tag=playingPVE", "run", "say", "hello");
        assertTrue(fixture.commands.isEmpty());
        assertEquals(3, fixture.messages.size());
        fixture.dispatchResult = false;
        fixture.run("if", "tag=playingPVE", "run", "unknown-command");
        assertTrue(fixture.messages.get(3).contains("Could not run command"));
    }
}
