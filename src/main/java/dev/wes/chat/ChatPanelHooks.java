package dev.wes.chat;

import dev.wes.gui.ChatBlockPanel;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 面板的「事件转发」逻辑，抽成静态方法给 mixin 用。
 *
 * <p>这里只放「拿到面板 → 转发 → 告诉调用方吃没吃掉」的通用部分，
 * {@code ChatScreenMixin} 只负责把参数递进来。以后如果要给别的聊天界面
 * （比如某个替换 {@code ChatScreen} 的模组）打补丁，复用这几个方法即可。</p>
 */
public final class ChatPanelHooks {

    private ChatPanelHooks() {
    }

    /** 面板开着就画。调用方负责在目标屏幕的 render 末尾调用它。 */
    public static void render(ChatPanelHolder holder, GuiGraphics graphics,
                              int mouseX, int mouseY, float partialTick) {
        ChatBlockPanel panel = holder.wes$panel();
        if (panel != null && panel.isOpen()) {
            panel.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    /** @return true 表示这次点击被面板吃掉了，调用方应该取消原逻辑 */
    public static boolean mouseClicked(ChatPanelHolder holder, double mouseX, double mouseY, int button) {
        ChatBlockPanel panel = holder.wes$panel();
        if (panel == null || !panel.isOpen()) {
            return false;
        }
        if (panel.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (!panel.contains(mouseX, mouseY)) {
            // 点在面板外面：收起面板，让原版逻辑继续
            panel.close();
        }
        return false;
    }

    /** @return true 表示滚轮被面板吃掉了 */
    public static boolean mouseScrolled(ChatPanelHolder holder, double mouseX, double mouseY, double amount) {
        ChatBlockPanel panel = holder.wes$panel();
        return panel != null && panel.mouseScrolled(mouseX, mouseY, amount);
    }

    /** @return true 表示按键被面板吃掉了 */
    public static boolean keyPressed(ChatPanelHolder holder, int keyCode, int scanCode, int modifiers) {
        ChatBlockPanel panel = holder.wes$panel();
        return panel != null && panel.isOpen() && panel.keyPressed(keyCode, scanCode, modifiers);
    }
}
