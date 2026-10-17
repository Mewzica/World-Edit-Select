package dev.wes.chat;

import dev.wes.gui.ChatBlockPanel;

/**
 * 由 {@code ChatScreenMixin} 实现的桥接口。
 *
 * <p>触发器（{@link ChatTrigger}）和文本框监听（TextFieldWidgetMixin）都只能拿到
 * 一个 {@code ChatScreen} 实例，而面板挂在 mixin 的 {@code @Unique} 字段上，
 * 所以用这个接口把「打开面板」暴露出来，避免两边互相强依赖。</p>
 */
public interface ChatPanelHolder {

    /** @return 当前聊天界面的面板实例（可能尚未创建，也返回非 null） */
    ChatBlockPanel wes$panel();

    /** 打开面板（已打开则忽略）。 */
    void wes$openPanel();

    /** @return 面板是否正开着 */
    boolean wes$isPanelOpen();
}
