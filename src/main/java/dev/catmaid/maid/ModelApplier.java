package dev.catmaid.maid;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * BetterModel 模型挂载的单点隔离层（blueprint 附录 A 要求）。
 *
 * <p>为什么要反射，而不是 compileOnly 依赖：
 * <ol>
 *   <li>Maven Central 只有 BetterModel 1.15.2 纯 API 包，不含 Citizens 集成类 ModelTrait；</li>
 *   <li>BetterModel 以 paper-plugin 形式加载，与 Bukkit 插件的 classloader 隔离，
 *       直接 import 运行期会 NoClassDefFoundError。</li>
 * </ol>
 * 因此从 BetterModel 自己的 classloader 取类，调用 api-facts 3.1 已确认的两个方法：
 * {@code ModelTrait#setRenderer(ModelRenderer)} 与 {@code BetterModel.modelOrNull(String)}。
 */
public final class ModelApplier {

    private static final String MODEL_TRAIT = "kr.toxicity.model.bukkit.compatibility.citizens.trait.ModelTrait";
    private static final String BETTER_MODEL_API = "kr.toxicity.model.api.BetterModel";
    // 渲染器实际类名（api-facts 已更正）：kr.toxicity.model.api.data.renderer.ModelRenderer。
    // 这里不写死它，改为按名字找 setRenderer(单参数)，这样渲染器换包也不至于整条链路失效。

    private final Logger logger;
    private boolean warned;

    public ModelApplier(Logger logger) {
        this.logger = logger;
    }

    /**
     * 给 NPC 挂模型（模型名为 null 或不存在时等于移除模型）。
     *
     * @return 是否成功应用；false 表示 BetterModel 不可用，女仆会以裸玩家皮显示
     */
    public boolean apply(NPC npc, String modelId) {
        Plugin betterModel = Bukkit.getPluginManager().getPlugin("BetterModel");
        if (betterModel == null) {
            warnOnce("BetterModel 未加载，女仆将不显示自定义模型");
            return false;
        }
        try {
            ClassLoader loader = betterModel.getClass().getClassLoader();
            Class<?> traitClass = Class.forName(MODEL_TRAIT, true, loader);
            Class<?> apiClass = Class.forName(BETTER_MODEL_API, true, loader);

            Method getOrAddTrait = NPC.class.getMethod("getOrAddTrait", Class.class);
            Object trait = getOrAddTrait.invoke(npc, traitClass);
            if (trait == null) {
                logger.warning("BetterModel 未能为 NPC " + npc.getId() + " 创建 model trait");
                return false;
            }

            Object renderer = null;
            if (modelId != null && !modelId.isBlank()) {
                Method modelOrNull = apiClass.getMethod("modelOrNull", String.class);
                renderer = modelOrNull.invoke(null, modelId);
                if (renderer == null) {
                    logger.warning("BetterModel 找不到模型 " + modelId + "，请确认 plugins/BetterModel/models/ 内有该模型");
                }
            }

            Method setRenderer = findSetRenderer(traitClass);
            if (setRenderer == null) {
                logger.warning("ModelTrait 上找不到 setRenderer 方法，BetterModel 版本可能已变");
                return false;
            }
            setRenderer.invoke(trait, renderer);
            return renderer != null;
        } catch (ReflectiveOperationException | LinkageError e) {
            warnOnce("挂载 BetterModel 模型失败（API 可能已随版本变化）：" + e);
            return false;
        }
    }

    private Method findSetRenderer(Class<?> traitClass) {
        for (Method method : traitClass.getMethods()) {
            if (method.getName().equals("setRenderer") && method.getParameterCount() == 1) {
                return method;
            }
        }
        return null;
    }

    private void warnOnce(String message) {
        if (!warned) {
            warned = true;
            logger.log(Level.WARNING, "" + message);
        }
    }
}
