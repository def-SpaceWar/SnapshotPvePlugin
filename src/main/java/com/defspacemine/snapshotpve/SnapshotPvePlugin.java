package com.defspacemine.snapshotpve;

import org.bukkit.plugin.java.JavaPlugin;

import com.defspacemine.snapshotpvp.lobby.LobbyManager;

public class SnapshotPvePlugin extends JavaPlugin {

    @Override
    public void onLoad() {}

    @Override
    public void onEnable() {
        LobbyManager.registeredLobbies.add(new PveLobbyType());
    }

    @Override
    public void onDisable() {}
}
