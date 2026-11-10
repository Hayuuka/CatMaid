package dev.catmaid.maid;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** config.yml 里 models 列表的只读视图。 */
public final class ModelRegistry {

    private final List<String> models;

    public ModelRegistry(FileConfiguration config) {
        this.models = List.copyOf(config.getStringList("models"));
    }

    public List<String> all() {
        return models;
    }

    /** 池为空时返回 null，由调用方决定报错还是跳过。 */
    public String random() {
        if (models.isEmpty()) {
            return null;
        }
        return models.get(ThreadLocalRandom.current().nextInt(models.size()));
    }

    public boolean contains(String modelId) {
        return models.contains(modelId);
    }
}
