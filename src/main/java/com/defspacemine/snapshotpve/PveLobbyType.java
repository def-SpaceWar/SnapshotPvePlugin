package com.defspacemine.snapshotpve;

import com.defspacemine.snapshotpvp.SnapshotPvpPlugin;
import com.defspacemine.snapshotpvp.lobby.LobbyType;
import java.util.List;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class PveLobbyType extends LobbyType {

    public String getPrefix() {
        return "pvelobby";
    }

    public LobbyInstance make(World world) {
        SnapshotPvpPlugin.server.sendMessage(
            Component.text("Pve Lobby!" + world.getName())
        );
        return new PveLobbyInstance(world);
    }

    enum LobbyState {
        WAITING, // everyone needs to ready up
        VOTE_CONFIG, // game settings
        KIT_SELECTION, // choose kits
        IN_GAME,
    }

    public class PveLobbyInstance
        extends LobbyType.LobbyInstance
        implements Listener
    {

        private World world;
        private World level1World;
        LobbyState lobbyState;
        boolean gameOver = false; // if the game ended bc someone died, then this should be true

        public PveLobbyInstance(World world) {
            this.world = world;
            level1World = Bukkit.getWorld(world.getName() + "_lvl1");
            lobbyState = LobbyState.WAITING;

            SnapshotPvpPlugin.pluginManager.registerEvents(
                this,
                SnapshotPvpPlugin.instance
            );
        }

        public World getWorld() {
            return world;
        }

        public List<Player> getPlayers() {
            // obviously it should just check who's in the game world, but i'm just getting this somewhat setup
            List<Player> players = Stream.concat(
                world.getPlayers().stream(),
                level1World.getPlayers().stream()
            ).toList();

            if (!gameOver) return players;

            for (Player p : players)
                p.teleport(new Location(world, 0.5, 65, 0.5));
            return null;
        }

        public void onTick() {
            // if (lobbyState == LobbyState.IN_GAME)
            {
                // run stuff
            }
        }

        public void onDestroy() {
            HandlerList.unregisterAll(this);

            SnapshotPvpPlugin.server.sendMessage(
                Component.text("No more pve lobby. ;( " + world.getName())
            );
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onPlayerDeath(PlayerDeathEvent event) {
            // handle ur death thingy here
        }
    }
}
