package dev.wes.chat;

import dev.wes.gui.ChatBlockPanel;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;

/**
 * 面板的「事件转发」逻辑，抽成静态方法给多个 mixin 共用。
 *
 * <h2>为什么要抽出来</h2>
 * <p>原版 {@code ChatScreen} 和第三方聊天模组（比如 AtomChat 的
 * {@code AtomChatScreen}）是两个不同的类，各自重写了 render / mouseClicked /
 * keyPressed。同一套转发逻辑要在两边各注入一次，如果直接复制粘贴，
 * 以后改面板交互就得改两处、很容易漏。</p>
 *
 * <p>所以这里只放「拿到面板 → 转发 → 告诉调用方吃没吃掉」的通用部分，
 * 各个 mixin 只负责把参数递进来。</p>
 */
public final class ChatPanelHooks {

    private ChatPanelHooks() {
    }

    /** 面板开着就画。调用方负责在目标屏幕的 render 末尾调用它。 */
    public static void render(ChatPanelHolder holder, DrawContext context,
                              int mouseX, int mouseY, float delta) {
        ChatBlockPanel panel = holder.wes$panel();
        if (panel != null && panel.isOpen()) {
            panel.render(context, mouseX, mouseY, delta);
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

    /**
     * 把字符送进面板里当前有焦点的输入框。
     *
     * <p>原版 {@code ChatScreen} 没有重写 {@code charTyped}，靠 {@code Screen}
     * 从 {@code ParentElement} 继承的默认实现转发，所以那边不需要这个钩子。
     * 但第三方聊天模组通常自己重写了 {@code charTyped}（先给自己的输入框），
     * 不转发的话面板里的搜索框就永远打不进字。</p>
     *
     * @return true 表示字符已经被消费，调用方不要再往下传
     */
    public static boolean charTyped(ParentElement parent, ChatPanelHolder holder, char chr, int modifiers) {
        ChatBlockPanel panel = holder.wes$panel();
        if (panel == null || !panel.isOpen()) {
            return false;
        }
        Element focused = parent.getFocused();
        return focused != null && focused.charTyped(chr, modifiers);
    }
}
