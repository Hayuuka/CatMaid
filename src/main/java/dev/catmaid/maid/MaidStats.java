package dev.catmaid.maid;

/** 女仆统计，对应 maid_stats 表。 */
public final class MaidStats {

    private int kills;
    private int harvests;
    private int breeds;
    private int shears;
    private int milks;

    public MaidStats() {
    }

    public MaidStats(int kills, int harvests, int breeds, int shears, int milks) {
        this.kills = kills;
        this.harvests = harvests;
        this.breeds = breeds;
        this.shears = shears;
        this.milks = milks;
    }

    public int kills() {
        return kills;
    }

    public int harvests() {
        return harvests;
    }

    public int breeds() {
        return breeds;
    }

    public int shears() {
        return shears;
    }

    public int milks() {
        return milks;
    }

    public void addKill() {
        kills++;
    }

    public void addHarvest() {
        harvests++;
    }

    public void addBreed() {
        breeds++;
    }

    public void addShear() {
        shears++;
    }

    public void addMilk() {
        milks++;
    }

    /** 仅供 MaidRepository 从数据库回填，运行期请用五个 addXxx 方法累加。 */
    public void restore(int kills, int harvests, int breeds, int shears, int milks) {
        this.kills = kills;
        this.harvests = harvests;
        this.breeds = breeds;
        this.shears = shears;
        this.milks = milks;
    }
}
