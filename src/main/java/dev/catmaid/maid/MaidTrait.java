package dev.catmaid.maid;

import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.util.DataKey;

/**
 * 挂在 NPC 上的 Citizens Trait，让 NPC 与 Maid 领域对象关联。
 *
 * <p>trait 名固定 "catmaid"，在 CatMaidPlugin#onEnable 注册；持久化只存 maidId 字符串，
 * 真正的数据在 SQLite（Citizens 的 saves.yml 不该承担业务数据）。
 *
 * <p><b>这里不驱动行为</b>。蓝图原计划在 {@code run()} 里委托 BehaviorManager，但实测
 * 玩家型 NPC 的 {@code run()} 根本不会被调用：Citizens 的 {@code PlayerUpdateTask} 只对带
 * {@code PacketNPC} trait 的 NPC 调 {@code AbstractNPC#update()}（trait 与导航都在里面跑），
 * 真实玩家实体的 NPC 走的是原版 {@code NMS.playerTicker}。行为 tick 因此改由 CatMaidPlugin
 * 自己的调度器驱动，详见 docs/api-facts.md 17.2。
 */
public final class MaidTrait extends Trait {

    private static final String KEY_MAID_ID = "maid";

    private String maidId;
    private Maid maid;

    public MaidTrait() {
        super("catmaid");
    }

    /** onAttach/恢复后由 MaidManager 注入内存对象。 */
    public void bind(Maid maid) {
        this.maid = maid;
        this.maidId = maid.getId().toString();
    }

    public Maid maid() {
        return maid;
    }

    public String maidId() {
        return maidId;
    }

    @Override
    public void onSpawn() {
        if (maid != null) {
            // 装备与血量上限都要在每次生成后补：Citizens 重建实体时这两样都回到默认值，
            // 而启动恢复时本回调早于 bind()（maid 为 null），由 MaidManager#attach 兜底
            maid.applyVitals();
        }
    }

    @Override
    public void onDespawn() {
        // NPC 坐标由 Citizens 自身的 saves.yml 持久化，业务数据由 MaidManager.saveAll 落库，
        // 这里无需重复保存（蓝图 P1 的"保存坐标"已由 Citizens 覆盖）。
    }

    @Override
    public void load(DataKey key) {
        this.maidId = key.getString(KEY_MAID_ID, "");
        if (this.maidId.isEmpty()) {
            this.maidId = null;
        }
    }

    @Override
    public void save(DataKey key) {
        if (maidId != null) {
            key.setString(KEY_MAID_ID, maidId);
        }
    }
}
