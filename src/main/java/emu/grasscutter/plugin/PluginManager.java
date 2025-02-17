package emu.grasscutter.plugin;

import static emu.grasscutter.utils.lang.Language.translate;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.server.event.*;
import emu.grasscutter.utils.*;

import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.util.*;
import java.util.jar.*;
import javax.annotation.Nullable;

import lombok.*;

/**
 * Manages the server's plugins and the event system.
 */
public final class PluginManager {
    /*
     * This should only be changed when a breaking change is made to the plugin API.
     * A 'breaking change' is something which changes the existing logic of the API.
     */
    @SuppressWarnings("FieldCanBeLocal")
    public static int API_VERSION = 2;

    /* All loaded plugins. */
    private final Map<String, Plugin> plugins = new LinkedHashMap<>();
    /* All currently registered listeners per plugin. */
    private final Map<Class<? extends Event>, List<EventHandler<? extends Event>>> handlers =
        new LinkedHashMap<>();

    public PluginManager() {
        this.loadPlugins(); // Load all plugins from the plugins directory.
    }

    /**
     * Loads plugins from the config-specified directory.
     */
    private void loadPlugins() {
        File pluginsDir = FileUtils.getPluginPath("").toFile();
        if (!pluginsDir.exists() && !pluginsDir.mkdirs()) {
            Grasscutter.getLogger()
                .error(translate("plugin.directory_failed", pluginsDir.getAbsolutePath()));
            return;
        }

        File[] files = pluginsDir.listFiles();
        if (files == null) {
            // The directory is empty, there aren't any plugins to load.
            return;
        }

        //加载所有jar为后缀的文件
        List<File> pluginFiles = Arrays.stream(files).filter(file -> file.getName().endsWith(".jar")).toList();

        URL[] pluginURLs = new URL[pluginFiles.size()];
        pluginFiles.forEach(
            plugin -> {
                try {
                    pluginURLs[pluginFiles.indexOf(plugin)] = plugin.toURI().toURL();
                } catch (MalformedURLException exception) {
                    Grasscutter.getLogger().warn(translate("plugin.unable_to_load"), exception);
                }
            });

        // Create a class loader for the plugins. 加载插件用的类加载器
        URLClassLoader classLoader = new URLClassLoader(pluginURLs);
        // Create a list of plugins that require dependencies. 插件依赖项
        List<PluginData> dependencies = new ArrayList<>();

        // Initialize all plugins.
        for (File pluginFile : pluginFiles) {
            try {
                URL url = pluginFile.toURI().toURL();
                try (URLClassLoader loader = new URLClassLoader(new URL[]{url})) {
                    // Find the plugin.json file for each plugin.
                    URL configFile = loader.findResource("plugin.json");
                    // Open the config file for reading.
                    InputStreamReader fileReader = new InputStreamReader(configFile.openStream());

                    // Create a plugin config instance from the config file.
                    PluginConfig pluginConfig = JsonUtils.loadToClass(fileReader, PluginConfig.class);
                    // Check the plugin's API version.
                    if (pluginConfig.api == null) {
                        Grasscutter.getLogger().warn(translate("plugin.invalid_api.not_present", pluginFile.getName()));
                        continue;
                    } else if (pluginConfig.api != API_VERSION) {
                        Grasscutter.getLogger().warn(translate("plugin.invalid_api.lower", pluginFile.getName(), pluginConfig.api, API_VERSION));
                        continue;
                    }

                    // Check if the plugin config is valid.
                    if (!pluginConfig.validate()) {
                        Grasscutter.getLogger().warn(translate("plugin.invalid_config", pluginFile.getName()));
                        continue;
                    }

                    // Create a JAR file instance from the plugin's URL.
                    JarFile jarFile = new JarFile(pluginFile);
                    // Load all class files from the JAR file.
                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        if (entry.isDirectory()
                            || !entry.getName().endsWith(".class")
                            || entry.getName().contains("module-info")) continue;
                        String className = entry.getName().replace(".class", "").replace("/", ".");
                        classLoader.loadClass(className); // Use the same class loader for ALL plugins.
                    }

                    // Create a plugin instance.
                    Class<?> pluginClass = classLoader.loadClass(pluginConfig.mainClass);
                    Plugin pluginInstance = (Plugin) pluginClass.getDeclaredConstructor().newInstance();
                    // Close the file reader.
                    fileReader.close();

                    // Check if the plugin has alternate dependencies. 依赖项
                    if (pluginConfig.loadAfter != null && pluginConfig.loadAfter.length > 0) {
                        // Add the plugin to a "load later" list. 有依赖项 则先不加载 存起来 等依赖项加载完再说
                        dependencies.add(new PluginData(pluginInstance, PluginIdentifier.fromPluginConfig(pluginConfig), loader, pluginConfig.loadAfter));
                        continue;
                    }

                    // Load the plugin.
                    this.loadPlugin(pluginInstance, PluginIdentifier.fromPluginConfig(pluginConfig), loader);
                } catch (ClassNotFoundException ignored) {
                    Grasscutter.getLogger().warn(translate("plugin.invalid_main_class", pluginFile.getName()));
                } catch (FileNotFoundException ignored) {
                    Grasscutter.getLogger().warn(translate("plugin.missing_config", pluginFile.getName()));
                }
            } catch (Exception exception) {
                Grasscutter.getLogger().error(translate("plugin.failed_to_load_plugin", pluginFile.getName()), exception);
            }
        }

        // Load plugins with dependencies.
        int depth = 0;
        final int maxDepth = 30;
        while (!dependencies.isEmpty()) {
            // Check if the depth is too high.
            if (depth >= maxDepth) {
                Grasscutter.getLogger().error(translate("plugin.failed_to_load_dependencies"));
                break;
            }

            try {
                // Get the next plugin to load.todo atb 插件依赖 永远拿第一个??
                var pluginData = dependencies.get(0);

                // Check if the plugin's dependencies are loaded. 依赖的还有没加载的 先跳过 深度+1
                if (!this.plugins.keySet().containsAll(List.of(pluginData.getDependencies()))) {
                    depth++; // Increase depth counter.
                    continue; // Continue to next plugin.
                }

                // Remove the plugin from the list of dependencies.
                dependencies.remove(pluginData);

                // Load the plugin.依赖的全都加载了 则加载
                this.loadPlugin(pluginData.getPlugin(), pluginData.getIdentifier(), pluginData.getClassLoader());
            } catch (Exception exception) {
                Grasscutter.getLogger().error(translate("plugin.failed_to_load"), exception);
                depth++;
            }
        }
    }

    /**
     * Load the specified plugin.
     *
     * @param plugin The plugin instance.
     */
    private void loadPlugin(Plugin plugin, PluginIdentifier identifier, URLClassLoader classLoader) {
        Grasscutter.getLogger().info(translate("plugin.loading_plugin", identifier.name));

        // Add the plugin's identifier.
        try {
            Class<Plugin> pluginClass = Plugin.class;
            Method method = pluginClass.getDeclaredMethod("initializePlugin", PluginIdentifier.class, URLClassLoader.class);
            method.setAccessible(true);
            //反射调用初始化代码
            method.invoke(plugin, identifier, classLoader);
            method.setAccessible(false);
        } catch (Exception ignored) {
            Grasscutter.getLogger().warn(translate("plugin.failed_add_id", identifier.name));
        }

        // Add the plugin to the list of loaded plugins.
        this.plugins.put(identifier.name, plugin);

        // Call the plugin's onLoad method.
        try {
            plugin.onLoad();
        } catch (Throwable exception) {
            Grasscutter.getLogger().error(translate("plugin.failed_to_load_plugin", identifier.name), exception);
        }
    }

    /**
     * Enables all registered plugins.
     */
    public void enablePlugins() {
        this.plugins.forEach(
            (name, plugin) -> {
                Grasscutter.getLogger().info(translate("plugin.enabling_plugin", name));
                try {
                    plugin.onEnable();
                    return;
                } catch (NoSuchMethodError ignored) {
                    Grasscutter.getLogger().error(translate("plugin.invalid_api.outdated", name));
                } catch (Throwable exception) {
                    Grasscutter.getLogger().error(translate("plugin.enabling_failed", name), exception);
                }

                this.disablePlugin(plugin);
            });
    }

    /**
     * Disables all registered plugins.
     */
    public void disablePlugins() {
        this.plugins.forEach(
            (name, plugin) -> {
                Grasscutter.getLogger().info(translate("plugin.disabling_plugin", name));
                this.disablePlugin(plugin);
            });
    }

    /**
     * Registers a plugin's event listener.
     *
     * @param listener The event listener.
     */
    public void registerListener(EventHandler<? extends Event> listener) {
        // Check if the handlers map contains the event type.
        if (!this.handlers.containsKey(listener.handles()))
            this.handlers.put(listener.handles(), new LinkedList<>());

        // Add the listener to the list of handlers.
        this.handlers.get(listener.handles()).add(listener);

        this.sortListeners(); // Sort the listeners by priority.
    }

    /**
     * Removes all event listeners registered by the specified plugin.
     *
     * @param plugin The plugin.
     */
    public void removeListeners(Plugin plugin) {
        var newMap = new HashMap<Class<? extends Event>, List<EventHandler<? extends Event>>>();

        // Remove the plugin's listeners.
        this.handlers.forEach(
            (event, handlers) -> {
                // Add the event to the new map.
                newMap.put(event, new LinkedList<>());

                // Remove the plugin's listeners.
                handlers.forEach(
                    handler -> {
                        if (!handler.registrar().equals(plugin)) newMap.get(event).add(handler);
                    });
            });

        // Replace the old map with the new one.
        this.handlers.clear();
        this.handlers.putAll(newMap);
    }

    /**
     * Sorts the event listeners by priority. This method should be called after a listener has been
     * registered.
     */
    private void sortListeners() {
        // Create a new map to store the sorted listeners.
        var newMap = new HashMap<Class<? extends Event>, List<EventHandler<? extends Event>>>();

        // Sort the listeners by priority.
        this.handlers.forEach(
            (event, handlers) -> {
                // Add the event to the new map.
                newMap.put(event, new LinkedList<>());

                // Sort the handlers by priority.
                var sorted =
                    handlers.stream()
                        .sorted(Comparator.comparingInt(handler -> handler.getPriority().ordinal()))
                        .toList();
                newMap.get(event).addAll(sorted);
            });

        // Replace the old map with the new one.
        this.handlers.clear();
        this.handlers.putAll(newMap);
    }

    /**
     * Invoke the provided event on all registered event listeners.
     *
     * @param event The event to invoke.
     */
    public void invokeEvent(Event event) {
        var handlers = this.handlers.get(event.getClass());
        if (handlers == null) return;

        handlers.forEach(handler -> this.invokeHandler(event, handler));
    }

    /**
     * Gets a plugin's instance by its name.
     *
     * @param name The name of the plugin.
     * @return Either null, or the plugin's instance.
     */
    @Nullable
    public Plugin getPlugin(String name) {
        return this.plugins.get(name);
    }

    /**
     * Enables a plugin.
     *
     * @param plugin The plugin to enable.
     */
    public void enablePlugin(Plugin plugin) {
        try {
            // Call the plugin's onEnable method.
            plugin.onEnable();
        } catch (Exception exception) {
            Grasscutter.getLogger()
                .error(translate("plugin.enabling_failed", plugin.getName()), exception);
        }
    }

    /**
     * Disables a plugin.
     *
     * @param plugin The plugin to disable.
     */
    public void disablePlugin(Plugin plugin) {
        try {
            // Call the plugin's onDisable method.
            plugin.onDisable();
        } catch (Exception exception) {
            Grasscutter.getLogger()
                .error(translate("plugin.disabling_failed", plugin.getName()), exception);
        }

        // Un-register all listeners.
        this.removeListeners(plugin);
    }

    /**
     * Performs logic checks then invokes the provided event handler.
     *
     * @param event   The event passed through to the handler.
     * @param handler The handler to invoke.
     */
    @SuppressWarnings("unchecked")
    private <T extends Event> void invokeHandler(Event event, EventHandler<T> handler) {
        if (!event.isCanceled() || (event.isCanceled() && handler.ignoresCanceled()))
            handler.getCallback().consume((T) event);
    }

    /* Data about an unloaded plugin. */
    @AllArgsConstructor
    @Getter
    static class PluginData {
        private Plugin plugin;
        private PluginIdentifier identifier;
        private URLClassLoader classLoader;
        private String[] dependencies;
    }
}
