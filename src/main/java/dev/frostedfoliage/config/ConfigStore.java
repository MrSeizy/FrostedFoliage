package dev.frostedfoliage.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private IOException loadFailure;
    public ConfigStore(Path file) { this.file = file; }

    public SnowConfig load() throws IOException {
        loadFailure = null;
        try { return read(); }
        catch (IOException e) { loadFailure = e; throw e; }
    }

    public String loadError() { return loadFailure == null ? null : loadFailure.getMessage(); }

    private SnowConfig read() throws IOException {
        if (!Files.exists(file)) {
            SnowConfig defaults = SnowConfig.defaults();
            save(defaults);
            return defaults;
        }
        try {
            JsonObject merged = GSON.toJsonTree(SnowConfig.defaults()).getAsJsonObject();
            JsonObject supplied = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            supplied.entrySet().forEach(e -> { if (!e.getValue().isJsonNull()) merged.add(e.getKey(), e.getValue()); });
            return GSON.fromJson(merged, SnowConfig.class);
        } catch (RuntimeException e) {
            Throwable detail = e;
            while (detail.getCause() != null) detail = detail.getCause();
            throw new IOException("Invalid configuration at " + file + ": " + detail.getMessage(), e);
        }
    }

    public void save(SnowConfig config) throws IOException {
        if (loadFailure != null) {
            throw new IOException("Cannot save after a failed configuration load. Fix the file and reload settings first: "
                    + loadFailure.getMessage(), loadFailure);
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "frostedfoliage-", ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(config) + "\n");
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
