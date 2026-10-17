package dev.wes;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端入口。
 *
 * <p>本模组没有任何注册表内容（方块/物品/网络包），全部逻辑由两个 Mixin
 * 和一个 Screen 组成，所以这里只留一个日志与常量入口，方便以后挂快捷键。</p>
 */
public final class WesClient implements ClientModInitializer {

    public static final String MOD_ID = "wes";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[wes] WorldEdit 方块选择器已加载");
    }
}
