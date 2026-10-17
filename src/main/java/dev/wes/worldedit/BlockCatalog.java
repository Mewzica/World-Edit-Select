package dev.wes.worldedit;

import com.sk89q.worldedit.world.block.BlockType;
import dev.wes.WesClient;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * WorldEdit 方块表 → 原版 {@link Block} 的桥。
 *
 * <h2>关于 getKnownBlocks()</h2>
 * <p>网上很多示例写 {@code WorldEdit.getInstance().getBlockFactory().getKnownBlocks()}，
 * 但这个方法在 WorldEdit 7.2 / 7.3 / 7.4 里<b>并不存在</b>
 * （{@code BlockFactory} 只负责「解析字符串」，不持有方块表）。
 * 7.x 真正的方块表是静态注册表 {@link BlockType#REGISTRY}，
 * 它实现了 {@code Iterable<BlockType>}，直接 for-each 即可。</p>
 *
 * <h2>为什么还要兜底到原版注册表</h2>
 * <p>WorldEdit 的 Fabric 模组只在 <b>SERVER_STARTING</b>（也就是开单人存档 /
 * 开 LAN 房）时才会把方块灌进 {@code BlockType.REGISTRY}。
 * 玩家连「别人开的服务器」时，客户端这边的 WorldEdit 实例是个空壳，
 * 注册表里一个方块都没有。这时候如果只信 WorldEdit API，界面会全空。
 * 所以这里先试 WorldEdit，空了就退回 {@code Registries.BLOCK} —— 两者的
 * 方块 ID 是同一套（{@code minecraft:stone} 之类），最终拼出来的指令完全一样。</p>
 */
public final class BlockCatalog {

    private BlockCatalog() {
    }

    /**
     * 收集当前环境下所有可选方块，按 ID 字典序排序。
     *
     * @return 已过滤掉空气（air / cave_air / void_air）的方块列表
     */
    public static List<Block> collectAll() {
        Set<Block> result = new LinkedHashSet<>();

        // ---- 方案 A：WorldEdit 注册表（单人 / LAN 房里有内容）----
        try {
            for (BlockType type : BlockType.REGISTRY) {
                Block block = toVanilla(type);
                if (block != null) {
                    result.add(block);
                }
            }
        } catch (Throwable t) {
            // WorldEdit 未初始化时 Registry 会抛 IllegalStateException，
            // 这里必须 catch Throwable：不同版本抛的异常类型不一样。
            WesClient.LOGGER.warn("[wes] WorldEdit 方块注册表不可用，改用原版注册表", t);
        }

        // ---- 方案 B：原版注册表（连远程服务器时的唯一可用来源）----
        if (result.isEmpty()) {
            for (Block block : Registries.BLOCK) {
                if (!isAir(block)) {
                    result.add(block);
                }
            }
        }

        List<Block> list = new ArrayList<>(result);
        list.sort(Comparator.comparing(BlockCatalog::idOf));
        return list;
    }

    /**
     * WorldEdit 的 {@link BlockType} → 原版 {@link Block}。
     *
     * <p>WorldEdit 的 ID 与原版注册表键一一对应（{@code minecraft:oak_log} 等），
     * 所以直接拿 ID 去原版注册表里查就行，不需要反射。</p>
     *
     * @return 对应的原版方块；查不到或是空气则返回 {@code null}
     */
    public static Block toVanilla(BlockType type) {
        if (type == null) {
            return null;
        }
        // getId() 在 7.4 被标了 @Deprecated(forRemoval)，但 7.2/7.3 只有它，
        // 为了跨版本兼容继续用它；7.4+ 可以用 id()。
        String rawId = type.getId();
        Identifier id = Identifier.tryParse(rawId);
        if (id == null) {
            return null;
        }
        Block block = Registries.BLOCK.get(id);
        return isAir(block) ? null : block;
    }

    /** 方块 ID，例如 {@code minecraft:stone}。用于排序和搜索匹配。 */
    public static String idOf(Block block) {
        return Registries.BLOCK.getId(block).toString();
    }

    /**
     * 拼进 WorldEdit 指令的方块 ID：<b>原版方块省略 {@code minecraft:}，模组方块保留命名空间</b>。
     *
     * <p>WorldEdit 解析图案时，没写命名空间的 ID 会按 {@code minecraft:} 补全，
     * 所以 {@code //set stone} 和 {@code //set minecraft:stone} 完全等价，
     * 但前者少了 10 个字符。聊天指令有 256 字符上限，方块一多，
     * 省下的这些前缀就是实打实的可用额度。</p>
     *
     * <p>模组方块必须带前缀，因为它们的命名空间不是 {@code minecraft}
     * （例如 {@code create:cogwheel}），省掉就解析不出来了。</p>
     */
    public static String commandId(Block block) {
        Identifier id = Registries.BLOCK.getId(block);
        return "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
    }

    private static boolean isAir(Block block) {
        return block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR;
    }
}
