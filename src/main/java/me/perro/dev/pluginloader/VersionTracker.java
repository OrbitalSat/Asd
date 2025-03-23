package me.perro.dev.pluginloader;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class VersionTracker {
    private final File versionFile;
    private YamlConfiguration versionConfig;
    private final Logger logger;

    // Almacena información sobre los plugins instalados
    private final Map<String, PluginInfo> pluginInfoMap = new HashMap<>();

    public VersionTracker(File dataFolder, Logger logger) {
        this.versionFile = new File(dataFolder, "versions.yml");
        this.logger = logger;
        loadVersionFile();
    }

    private void loadVersionFile() {
        if (!versionFile.exists()) {
            try {
                versionFile.createNewFile();
            } catch (IOException e) {
                logger.severe("No se pudo crear el archivo de versiones: " + e.getMessage());
            }
        }

        versionConfig = YamlConfiguration.loadConfiguration(versionFile);

        // Cargar la información guardada
        if (versionConfig.contains("plugins")) {
            for (String key : versionConfig.getConfigurationSection("plugins").getKeys(false)) {
                String path = "plugins." + key + ".";

                String artifactId = versionConfig.getString(path + "artifactId");
                String version = versionConfig.getString(path + "version");
                long runId = versionConfig.getLong(path + "runId");
                String fileName = versionConfig.getString(path + "fileName");

                pluginInfoMap.put(key, new PluginInfo(artifactId, version, runId, fileName));
            }
        }
    }

    public void saveVersionFile() {
        // Guardar la información actual
        for (Map.Entry<String, PluginInfo> entry : pluginInfoMap.entrySet()) {
            String key = entry.getKey();
            PluginInfo info = entry.getValue();

            String path = "plugins." + key + ".";
            versionConfig.set(path + "artifactId", info.getArtifactId());
            versionConfig.set(path + "version", info.getVersion());
            versionConfig.set(path + "runId", info.getRunId());
            versionConfig.set(path + "fileName", info.getFileName());
        }

        try {
            versionConfig.save(versionFile);
        } catch (IOException e) {
            logger.severe("No se pudo guardar el archivo de versiones: " + e.getMessage());
        }
    }

    public boolean isNewVersion(String repoKey, long artifactId, long runId) {
        if (!pluginInfoMap.containsKey(repoKey)) {
            return true; // No hay versión previa, es nueva
        }

        PluginInfo info = pluginInfoMap.get(repoKey);

        // Si el ID de ejecución o el ID de artefacto es diferente, es una nueva versión
        return info.getRunId() != runId || !info.getArtifactId().equals(String.valueOf(artifactId));
    }

    public String getCurrentPluginFileName(String repoKey) {
        if (pluginInfoMap.containsKey(repoKey)) {
            return pluginInfoMap.get(repoKey).getFileName();
        }
        return null;
    }

    public void updatePluginInfo(String repoKey, String artifactId, String version, long runId, String fileName) {
        pluginInfoMap.put(repoKey, new PluginInfo(artifactId, version, runId, fileName));
        saveVersionFile();
    }

    // Clase interna para almacenar información sobre las versiones de los plugins
    private static class PluginInfo {
        private final String artifactId;
        private final String version;
        private final long runId;
        private final String fileName;

        public PluginInfo(String artifactId, String version, long runId, String fileName) {
            this.artifactId = artifactId;
            this.version = version;
            this.runId = runId;
            this.fileName = fileName;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public String getVersion() {
            return version;
        }

        public long getRunId() {
            return runId;
        }

        public String getFileName() {
            return fileName;
        }
    }
}