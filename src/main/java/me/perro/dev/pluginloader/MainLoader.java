package me.perro.dev.pluginloader;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

public class MainLoader extends JavaPlugin {

    private File subPluginsFolder;
    private File downloadsFolder;
    private final String USER_AGENT = "PluginLoader/1.0";
    private VersionTracker versionTracker;

    @Override
    public void onLoad() {
        // Guardar configuración por defecto
        saveDefaultConfig();

        // Crear carpetas necesarias
        subPluginsFolder = new File(getDataFolder(), "sub-plugins");
        downloadsFolder = new File(getDataFolder(), "downloads");

        if (!subPluginsFolder.exists()) {
            subPluginsFolder.mkdirs();
            getLogger().info("Carpeta de sub-plugins creada en: " + subPluginsFolder.getAbsolutePath());
        }

        if (!downloadsFolder.exists()) {
            downloadsFolder.mkdirs();
        }

        // Inicializar el logger de versiones
        versionTracker = new VersionTracker(getDataFolder(), getLogger());

        // Descargar plugins desde GitHub si está habilitado
        if (getConfig().getBoolean("github.enabled", false)) {
            downloadPluginsFromGitHub();
        }

        // Cargar los sub-plugins
        loadSubPlugins();
    }

    public void onEnable() {
        getLogger().info("PluginLoader ha sido habilitado con éxito.");
        getLogger().info("Coloca tus plugins en: " + subPluginsFolder.getAbsolutePath());
    }

    @Override
    public void onDisable() {
        // Guardar el archivo de versiones
        if (versionTracker != null) {
            versionTracker.saveVersionFile();
        }

        getLogger().info("PluginLoader ha sido deshabilitado.");
    }

    private void downloadPluginsFromGitHub() {
        getLogger().info("Comprobando actualizaciones desde GitHub...");

        for (String repoKey : getConfig().getConfigurationSection("github.repositories").getKeys(false)) {
            String repoPath = "github.repositories." + repoKey + ".";
            String owner = getConfig().getString(repoPath + "owner");
            String repo = getConfig().getString(repoPath + "repo");
            String artifactName = getConfig().getString(repoPath + "artifact", "*.jar");
            String token = getConfig().getString(repoPath + "token", "");
            String branch = getConfig().getString(repoPath + "branch", "main");
            String workflow = getConfig().getString(repoPath + "workflow");

            if (owner == null || repo == null || workflow == null) {
                getLogger().warning("Configuración incompleta para el repositorio: " + repoKey);
                continue;
            }

            getLogger().info("Verificando actualizaciones de " + owner + "/" + repo + " - workflow: " + workflow);

            try {
                // Obtener el último ID de ejecución del workflow
                Long runId = getLatestWorkflowRunId(owner, repo, workflow, branch, token);

                if (runId == null) {
                    getLogger().warning("No se encontraron ejecuciones de workflow para " + owner + "/" + repo);
                    continue;
                }

                getLogger().info("Último ID de ejecución de workflow encontrado: " + runId);

                // Obtener la lista de artefactos
                JSONArray artifacts = getWorkflowRunArtifacts(owner, repo, runId, token);

                if (artifacts.isEmpty()) {
                    getLogger().warning("No se encontraron artefactos para la ejecución " + runId);
                    continue;
                }

                // Descargar cada artefacto que coincida con el patrón
                boolean found = false;
                for (Object obj : artifacts) {
                    JSONObject artifact = (JSONObject) obj;
                    String name = (String) artifact.get("name");

                    // Verificar si el nombre coincide con el patrón (usando * como comodín)
                    if (matchesPattern(name, artifactName)) {
                        long artifactId = (long) artifact.get("id");
                        String downloadUrl = (String) artifact.get("archive_download_url");

                        // Verificar si esta versión ya está instalada
                        if (!versionTracker.isNewVersion(repoKey, artifactId, runId)) {
                            getLogger().info("Ya tienes la última versión de " + name + " (Artifact ID: " + artifactId + ", Run ID: " + runId + ")");
                            found = true;
                            continue;
                        }

                        getLogger().info("¡Nueva versión disponible! Descargando artefacto: " + name + " (ID: " + artifactId + ")");

                        // Eliminar la versión anterior si existe
                        String oldFileName = versionTracker.getCurrentPluginFileName(repoKey);
                        if (oldFileName != null) {
                            File oldFile = new File(subPluginsFolder, oldFileName);
                            if (oldFile.exists() && oldFile.delete()) {
                                getLogger().info("Versión anterior eliminada: " + oldFileName);
                            }
                        }

                        // Crear un nombre de archivo único con fecha
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd-HHmmss");
                        String timestamp = sdf.format(new Date());
                        String fileName = repo + "-" + timestamp + ".zip";
                        File zipFile = new File(downloadsFolder, fileName);

                        // Descargar el archivo ZIP
                        downloadFile(downloadUrl, zipFile, token);

                        // Descomprimir el archivo ZIP y mover los JARs a la carpeta de sub-plugins
                        String extractedFileName = extractJarsFromZip(zipFile, subPluginsFolder, repoKey);

                        // Actualizar la información de versión
                        if (extractedFileName != null) {
                            versionTracker.updatePluginInfo(repoKey, String.valueOf(artifactId),
                                    "run-" + runId, runId, extractedFileName);
                            getLogger().info("Información de versión actualizada para " + repoKey);
                        }

                        found = true;
                    }
                }

                if (!found) {
                    getLogger().warning("No se encontraron artefactos que coincidan con el patrón: " + artifactName);
                }

            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Error al descargar artefactos para " + owner + "/" + repo, e);
            }
        }
    }

    private boolean matchesPattern(String name, String pattern) {
        // Convertir el patrón a una expresión regular
        String regex = pattern.replace(".", "\\.").replace("*", ".*");
        return name.matches(regex);
    }

    private Long getLatestWorkflowRunId(String owner, String repo, String workflow, String branch, String token) throws Exception {
        String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/actions/workflows/" + workflow + "/runs?branch=" + branch + "&status=success&per_page=1";

        JSONObject response = makeGitHubApiRequest(apiUrl, token);
        JSONArray workflowRuns = (JSONArray) response.get("workflow_runs");

        if (workflowRuns != null && !workflowRuns.isEmpty()) {
            JSONObject latestRun = (JSONObject) workflowRuns.get(0);
            return (Long) latestRun.get("id");
        }

        return null;
    }

    private JSONArray getWorkflowRunArtifacts(String owner, String repo, Long runId, String token) throws Exception {
        String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/actions/runs/" + runId + "/artifacts";

        JSONObject response = makeGitHubApiRequest(apiUrl, token);
        return (JSONArray) response.get("artifacts");
    }

    private JSONObject makeGitHubApiRequest(String apiUrl, String token) throws Exception {
        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);

        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("Authorization", "token " + token);
        }

        int responseCode = conn.getResponseCode();

        if (responseCode != 200) {
            throw new IOException("Error en la API de GitHub. Código de respuesta: " + responseCode);
        }

        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String inputLine;
        StringBuilder response = new StringBuilder();

        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();

        JSONParser parser = new JSONParser();
        return (JSONObject) parser.parse(response.toString());
    }

    private void downloadFile(String downloadUrl, File outputFile, String token) throws IOException {
        URL url = new URL(downloadUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);

        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("Authorization", "token " + token);
        }

        int responseCode = conn.getResponseCode();

        if (responseCode != 200) {
            throw new IOException("Error al descargar el archivo. Código de respuesta: " + responseCode);
        }

        try (InputStream inputStream = conn.getInputStream();
             FileOutputStream outputStream = new FileOutputStream(outputFile)) {

            byte[] buffer = new byte[4096];
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }

        getLogger().info("Archivo descargado en: " + outputFile.getAbsolutePath());
    }

    private String extractJarsFromZip(File zipFile, File destFolder, String repoKey) {
        getLogger().info("Extrayendo archivos JAR de " + zipFile.getName() + " a " + destFolder.getAbsolutePath());

        String extractedFileName = null;

        try {
            java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();

            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                // Solo procesar archivos JAR
                if (name.toLowerCase().endsWith(".jar")) {
                    getLogger().info("Extrayendo: " + name);

                    File outFile = new File(destFolder, new File(name).getName());
                    extractedFileName = outFile.getName();

                    // Evitar la duplicación si el archivo ya existe
                    if (outFile.exists()) {
                        String baseName = outFile.getName().substring(0, outFile.getName().lastIndexOf('.'));
                        String extension = outFile.getName().substring(outFile.getName().lastIndexOf('.'));
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd-HHmmss");
                        String timestamp = sdf.format(new Date());
                        extractedFileName = baseName + "-" + timestamp + extension;
                        outFile = new File(destFolder, extractedFileName);
                    }

                    // Copiar el archivo
                    try (InputStream in = zip.getInputStream(entry);
                         FileOutputStream out = new FileOutputStream(outFile)) {

                        byte[] buffer = new byte[4096];
                        int bytesRead;

                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }
                }
            }

            zip.close();
            getLogger().info("Extracción completada.");

            // Opcionalmente, eliminar el archivo ZIP después de extraerlo
            if (getConfig().getBoolean("github.delete-after-extract", true)) {
                if (zipFile.delete()) {
                    getLogger().info("Archivo ZIP eliminado: " + zipFile.getName());
                } else {
                    getLogger().warning("No se pudo eliminar el archivo ZIP: " + zipFile.getName());
                }
            }

        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Error al extraer archivos del ZIP: " + zipFile.getName(), e);
        }

        return extractedFileName;
    }

    private void loadSubPlugins() {
        if (!subPluginsFolder.exists() || !subPluginsFolder.isDirectory()) {
            getLogger().warning("La carpeta de sub-plugins no existe o no es un directorio.");
            return;
        }

        File[] jarFiles = subPluginsFolder.listFiles((dir, name) -> name.endsWith(".jar"));

        if (jarFiles == null || jarFiles.length == 0) {
            getLogger().info("No se encontraron sub-plugins para cargar.");
            return;
        }

        int loadedPlugins = 0;
        for (File jarFile : jarFiles) {
            try {
                if (loadPlugin(jarFile)) {
                    loadedPlugins++;
                }
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Error al cargar sub-plugin: " + jarFile.getName(), e);
            }
        }

        getLogger().info("Se cargaron " + loadedPlugins + " sub-plugins correctamente.");
    }

    private boolean loadPlugin(File jarFile) throws InvalidPluginException, InvalidDescriptionException {
        try {
            // Verificar si el archivo es un plugin válido
            JarFile jar = new JarFile(jarFile);
            JarEntry pluginYml = jar.getJarEntry("plugin.yml");

            if (pluginYml == null) {
                jar.close();
                throw new InvalidPluginException("El archivo JAR no contiene plugin.yml");
            }

            // Leer el plugin.yml para verificar información básica
            InputStream in = jar.getInputStream(pluginYml);
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(in));

            String pluginName = config.getString("name");
            String mainClass = config.getString("main");

            if (pluginName == null || mainClass == null) {
                jar.close();
                throw new InvalidPluginException("El plugin.yml no contiene información necesaria");
            }

            jar.close();

            // Verificar si el plugin ya está cargado
            Plugin existingPlugin = Bukkit.getPluginManager().getPlugin(pluginName);
            if (existingPlugin != null) {
                getLogger().info("El plugin " + pluginName + " ya está cargado. No se cargará de nuevo.");
                return false;
            }

            // Modificamos la carga del plugin para usar la carpeta de configuración principal
            // Primero cargamos el plugin
            Plugin plugin = Bukkit.getPluginManager().loadPlugin(jarFile);
            if (plugin != null) {
                // Preparamos el directorio de configuraciones para este plugin en la carpeta principal de plugins
                File pluginConfigDir = new File(getDataFolder().getParentFile(), plugin.getName());
                if (!pluginConfigDir.exists()) {
                    pluginConfigDir.mkdirs();
                    getLogger().info("Creado directorio de configuración para " + plugin.getName() + " en: " + pluginConfigDir.getAbsolutePath());
                }

                // Establecemos el datafolder en la carpeta principal de plugins antes de inicializar
                try {
                    // Usamos reflexión para acceder al campo dataFolder que es protected en JavaPlugin
                    java.lang.reflect.Field dataFolderField = JavaPlugin.class.getDeclaredField("dataFolder");
                    dataFolderField.setAccessible(true);
                    dataFolderField.set(plugin, pluginConfigDir);
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "No se pudo establecer la carpeta de datos para " + plugin.getName(), e);
                }

                // Ahora inicializamos el plugin
                plugin.onLoad();
                //Bukkit.getPluginManager().enablePlugin(plugin);
                getLogger().info("Sub-plugin cargado con éxito: " + plugin.getName() + " v" + plugin.getDescription().getVersion());
                getLogger().info("Configuraciones para " + plugin.getName() + " en: " + pluginConfigDir.getAbsolutePath());
                return true;
            } else {
                getLogger().warning("No se pudo cargar el sub-plugin: " + jarFile.getName());
                return false;
            }

        } catch (IOException e) {
            throw new InvalidPluginException("Error al acceder al archivo JAR: " + e.getMessage());
        }
    }
}