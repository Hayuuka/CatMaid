package dev.catmaid.maid;

import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.util.DataKey;

/**
 * 挂在 NPC 上的 Citizens Trait，让 NPC 与 Maid 领域对象关联，并作为每 tick 驱动的入口。
 *
 * <p>trait 名固定 "catmaid"，在 CatMaidPlugin#onEnable 注册；持久化只存 maidId 字符串，
 * 真正的数据在 SQLite（Citizens 的 saves.yml 不该承担业务数据）。
 */
public final class MaidTrait extends Trait {

    private static final String KEY_MAID_ID = "maid";

    private String maidId;
    private Maid maid;
    private int tickCounter;

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
    public void run() {
        if (maid == null) {
            return;
        }
        tickCounter++;
        // TODO(P3): 委托 BehaviorManager.tick(maid, tickCounter)
    }

    @Override
    public void onSpawn() {
        if (maid != null) {
            maid.applyEquipment();
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
