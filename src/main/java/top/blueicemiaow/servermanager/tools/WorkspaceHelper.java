package top.blueicemiaow.servermanager.tools;

import net.minecraft.world.entity.Entity;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.UUID;

public class WorkspaceHelper {
    public static final Path defaultWorkspace = Path.of("").toAbsolutePath();
    public static final HashMap<UUID, Path> workspaces = new HashMap<>();

    public static Path getWorkspace(UUID uuid) {
        return workspaces.getOrDefault(uuid, defaultWorkspace);
    }

    public static Path getWorkspace(Entity entity) {
        return getWorkspace(entity.getUUID());
    }

    public static String getStringWorkspace(UUID uuid) {
        return getWorkspace(uuid).toString();
    }

    public static String getStringWorkspace(Entity entity) {
        return getWorkspace(entity).toString();
    }

    public static void setWorkspace(UUID uuid, Path path) {
        workspaces.put(uuid, path.toAbsolutePath());
    }

    public static void setWorkspace(Entity entity, Path path) {
        setWorkspace(entity.getUUID(), path.toAbsolutePath());
    }

    public static void setWorkspace(UUID uuid, String path) {
        setWorkspace(uuid, Path.of(path));
    }

    public static void setWorkspace(Entity entity, String path) {
        setWorkspace(entity, Path.of(path));
    }
}
