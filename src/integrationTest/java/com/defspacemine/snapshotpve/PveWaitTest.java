package com.defspacemine.snapshotpve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

class PveWaitTest {
    private static final class Fixture {
        Runnable task;
        long delay;
        boolean permitted = true;
        boolean dispatchResult = true;
        CommandSender dispatchedSender;
        String dispatchedCommand;
        final List<String> messages = new ArrayList<>();

        final CommandSender sender = proxy(CommandSender.class, (method, args) -> {
            if (method.equals("hasPermission")) {
                return permitted;
            }
            if (method.equals("sendMessage")) {
                messages.add((String) args[0]);
                return null;
            }
            throw new AssertionError("Unexpected sender method: " + method);
        });

        final BukkitScheduler scheduler = proxy(BukkitScheduler.class, (method, args) -> {
            if (method.equals("runTaskLater")) {
                task = (Runnable) args[1];
                delay = (long) args[2];
                return null;
            }
            throw new AssertionError("Unexpected scheduler method: " + method);
        });

        final Server server = proxy(Server.class, (method, args) -> {
            if (method.equals("getScheduler")) {
                return scheduler;
            }
            if (method.equals("dispatchCommand")) {
                dispatchedSender = (CommandSender) args[0];
                dispatchedCommand = (String) args[1];
                return dispatchResult;
            }
            throw new AssertionError("Unexpected server method: " + method);
        });

        final Plugin plugin = proxy(Plugin.class, (method, args) -> {
            if (method.equals("getServer")) {
                return server;
            }
            if (method.equals("getLogger")) {
                return Logger.getLogger("PveWaitTest");
            }
            throw new AssertionError("Unexpected plugin method: " + method);
        });

        final PveWait command = new PveWait(plugin);
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
    void schedulesFunctionAndRunsWithOriginalSender() {
        Fixture fixture = new Fixture();
        assertTrue(fixture.command.onCommand(fixture.sender, null, "pvewait",
            new String[] {"40", "story:open_gate"}));
        assertEquals(40, fixture.delay);
        assertNull(fixture.dispatchedCommand);
        fixture.task.run();
        assertSame(fixture.sender, fixture.dispatchedSender);
        assertEquals("minecraft:function story:open_gate", fixture.dispatchedCommand);
    }

    @Test
    void supportsZeroDelayAndFunctionTags() {
        Fixture fixture = new Fixture();
        fixture.command.onCommand(fixture.sender, null, "pvewait", new String[] {"0", "#story:wave"});
        assertEquals(0, fixture.delay);
        fixture.task.run();
        assertEquals("minecraft:function #story:wave", fixture.dispatchedCommand);
    }

    @Test
    void schedulesRegularCommandsWithOrWithoutASlash() {
        for (String commandName : new String[] {"say", "/say"}) {
            Fixture fixture = new Fixture();
            fixture.command.onCommand(fixture.sender, null, "pvewait",
                new String[] {"20", "run", commandName, "Hello", "@a"});
            assertEquals(20, fixture.delay);
            assertNull(fixture.dispatchedCommand);
            fixture.task.run();
            assertSame(fixture.sender, fixture.dispatchedSender);
            assertEquals("say Hello @a", fixture.dispatchedCommand);
        }
        Fixture fixture = new Fixture();
        fixture.command.onCommand(fixture.sender, null, "pvewait", new String[] {"1", "run", "save-all"});
        fixture.task.run();
        assertEquals("save-all", fixture.dispatchedCommand);

        Fixture nested = new Fixture();
        nested.command.onCommand(nested.sender, null, "pvewait",
            new String[] {"1", "run", "execute", "as", "@a", "run", "say", "hello"});
        nested.task.run();
        assertEquals("execute as @a run say hello", nested.dispatchedCommand);
    }

    @Test
    void preservesFunctionNamedRun() {
        Fixture fixture = new Fixture();
        fixture.command.onCommand(fixture.sender, null, "pvewait", new String[] {"1", "run"});
        fixture.task.run();
        assertEquals("minecraft:function run", fixture.dispatchedCommand);
    }

    @Test
    void rejectsInvalidInputAndUnauthorizedSenders() {
        for (String[] args : new String[][] {
            {}, {"-1", "story:test"}, {"2147483648", "story:test"},
            {"NaN", "story:test"}, {"1", "#"}, {"1", "BAD NAME"}, {"1", "story:test", "extra"},
            {"1", "run", "/"}, {"1", "run", ""}
        }) {
            Fixture fixture = new Fixture();
            fixture.command.onCommand(fixture.sender, null, "pvewait", args);
            assertNull(fixture.task);
            assertTrue(!fixture.messages.isEmpty());
        }
        Fixture fixture = new Fixture();
        fixture.permitted = false;
        fixture.command.onCommand(fixture.sender, null, "pvewait", new String[] {"1", "story:test"});
        assertNull(fixture.task);
        assertTrue(!fixture.messages.isEmpty());
    }

    @Test
    void reportsDispatchFailure() {
        Fixture fixture = new Fixture();
        fixture.dispatchResult = false;
        fixture.command.onCommand(fixture.sender, null, "pvewait", new String[] {"1", "story:missing"});
        fixture.task.run();
        assertTrue(fixture.messages.get(0).contains("story:missing"));
    }
}
