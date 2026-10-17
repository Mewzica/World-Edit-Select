package dev.wes.chat;

import dev.wes.command.BlockCommand;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;

/**
 * 触发器的唯一入口。
 *
 * <p>为什么要把判断逻辑单独抽出来？因为「文本变化」在 1.20.1 里可能来自三个地方：</p>
 * <ol>
 *   <li>{@code TextFieldWidget#write} —— 键盘打字、Ctrl+V 粘贴；</li>
 *   <li>{@code TextFieldWidget#setText} —— 输入法(IME)上屏、历史消息回填；</li>
 *   <li>{@code ChatScreen#keyPressed} —— 回车前的一次兜底检查。</li>
 * </ol>
 * <p>三处都会调用 {@code TextFieldWidget#onChanged}，所以真正挂载点是
 * {@link dev.wes.mixin.TextFieldWidgetMixin}，这里只负责「认出是哪条指令 + 防重入 + 开面板」。</p>
 */
public final class ChatTrigger {

    /**
     * 防重入闸门。
     *
     * <p>同一次按键可能同时经过 onChanged 和 keyPressed 两个钩子，
     * 不加闸门会连开两次面板。用 volatile 是因为
     * {@link MinecraftClient#execute} 会在主线程任务队列里晚一拍执行。</p>
     */
    private static volatile boolean firing = false;

    private ChatTrigger() {
    }

    /**
     * 判断这段文本是不是「某条指令 + 一个空格」。
     *
     * @return 命中的指令；没命中返回 null
     */
    public static BlockCommand find(String text) {
        if (text == null) {
            return null;
        }
        for (BlockCommand command : BlockCommand.values()) {
            if (command.trigger().equals(text)) {
                return command;
            }
        }
        return null;
    }

    /** 供 keyPressed 兜底用：文本是否正好停在某个触发前缀上。 */
    public static boolean matches(String text) {
        return find(text) != null;
    }

    /**
     * 检查当前输入内容是否命中某个指令前缀，命中则打开聊天框里的选择浮层。
     *
     * @param screen 当前聊天界面实例（用来确认「现在真的是这个界面」）
     * @param text   输入框里的完整文本
     */
    public static void check(ChatScreen screen, String text) {
        BlockCommand command = find(text);
        if (firing || command == null) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        // 必须校验：可能已经有别的钩子先一步把界面换掉了
        if (client.currentScreen != screen || client.player == null) {
            return;
        }
        if (!(screen instanceof ChatPanelHolder holder) || holder.wes$isPanelOpen()) {
            return;
        }

        firing = true;

        // 延后一 tick 再动手：此刻正处在 TextFieldWidget.onChanged 的回调里，
        // 直接 setText("") 会在同一个方法栈内重入文本框，光标/选区状态容易错乱。
        client.execute(() -> {
            firing = false;
            if (client.currentScreen == screen && screen instanceof ChatPanelHolder h) {
                h.wes$openPanel(command);
            }
        });
    }
}
