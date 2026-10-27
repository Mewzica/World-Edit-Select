package dev.wes;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * 客户端入口（Forge）。
 *
 * <p>本模组没有任何注册表内容（方块/物品/网络包），全部逻辑由一个 Mixin
 * 和一个浮层面板组成，所以这里只留一个日志与常量入口，方便以后挂快捷键。</p>
 *
 * <p>Forge 用 {@link Mod} 注解声明模组，构造器在模组加载时被调用一次。
 * 这里不碰任何客户端专属类，所以即使被装到服务端也只会打一行日志，不会崩。</p>
 */
@Mod(WesClient.MOD_ID)
public final class WesClient {

    public static final String MOD_ID = "wes";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WesClient() {
        LOGGER.info("[wes] WorldEdit 方块选择器已加载");
    }
}
