package top.blueicemiaow.servermanager.tools;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.Entity;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class WorkspaceHelper {
    public static final Path defaultWorkspace = Path.of("").toAbsolutePath();
    public static final HashMap<UUID, Path> workspaces = new HashMap<>();
    public static final HashMap<UUID, Path> previousWorkspaces = new HashMap<>();

    /**
     * The command console has no entity, so it gets a fixed id and keeps its own working directory.
     */
    public static final UUID SERVER_WORKSPACE_ID = new UUID(0L, 0L);

    public static UUID getWorkspaceId(CommandSourceStack source) {
        Entity entity = source.getEntity();
        return entity == null ? SERVER_WORKSPACE_ID : entity.getUUID();
    }

    public static Path getWorkspace(CommandSourceStack source) {
        return getWorkspace(getWorkspaceId(source));
    }

    public static Path getWorkspace(UUID uuid) {
        return workspaces.getOrDefault(uuid, defaultWorkspace);
    }

    public static Path getWorkspace(Entity entity) {
        return getWorkspace(entity.getUUID());
    }

    public static Path getPreviousWorkspace(CommandSourceStack source) {
        return previousWorkspaces.get(getWorkspaceId(source));
    }

    public static Path getPreviousWorkspace(UUID uuid) {
        return previousWorkspaces.get(uuid);
    }

    public static String getStringWorkspace(UUID uuid) {
        return getWorkspace(uuid).toString();
    }

    public static String getStringWorkspace(Entity entity) {
        return getWorkspace(entity).toString();
    }

    public static void setWorkspace(CommandSourceStack source, Path path) {
        setWorkspace(getWorkspaceId(source), path);
    }

    public static void setWorkspace(UUID uuid, Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = getWorkspace(uuid);
        if (!current.equals(absolute)) {
            previousWorkspaces.put(uuid, current);
        }
        workspaces.put(uuid, absolute);
    }

    public static void setWorkspace(Entity entity, Path path) {
        setWorkspace(entity.getUUID(), path);
    }

    public static void setWorkspace(UUID uuid, String path) {
        setWorkspace(uuid, Path.of(path));
    }

    public static void setWorkspace(Entity entity, String path) {
        setWorkspace(entity, Path.of(path));
    }

    /**
     * Moves every stored working directory that pointed inside {@code from} over to {@code to}.
     * This keeps a {@code cd} session valid after its current directory is renamed or moved.
     */
    public static void remapWorkspaces(Path from, Path to) {
        remap(workspaces, from, to);
        remap(previousWorkspaces, from, to);
    }

    private static void remap(Map<UUID, Path> map, Path from, Path to) {
        for (Map.Entry<UUID, Path> entry : map.entrySet()) {
            Path path = entry.getValue();
            if (path.startsWith(from)) {
                entry.setValue(to.resolve(from.relativize(path)).toAbsolutePath().normalize());
            }
        }
    }
}
