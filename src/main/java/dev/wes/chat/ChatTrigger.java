package dev.wes.chat;

import dev.wes.WesClient;
import dev.wes.gui.BlockSelectScreen;
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
 * {@link dev.wes.mixin.TextFieldWidgetMixin}，这里只负责「防重入 + 切界面」。</p>
 */
public final class ChatTrigger {

    /** 触发串：注意末尾那个空格，是需求里明确要求的。 */
    public static final String TRIGGER = "//set ";

    /**
     * 防重入闸门。
     *
     * <p>同一次按键可能同时经过 onChanged 和 keyPressed 两个钩子，
     * 不加闸门会连续 new 两次 Screen。用 volatile 是因为
     * {@link MinecraftClient#execute} 会在主线程任务队列里晚一拍执行。</p>
     */
    private static volatile boolean firing = false;

    private ChatTrigger() {
    }

    /**
     * 检查当前输入内容是否命中触发串，命中则关闭聊天界面并打开方块选择界面。
     *
     * @param screen 当前聊天界面实例（用来确认「现在真的是这个界面」）
     * @param text   输入框里的完整文本
     */
    public static void check(ChatScreen screen, String text) {
        if (firing || text == null || !TRIGGER.equals(text)) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        // 必须校验：可能已经有别的钩子先一步把界面换掉了
        if (client.currentScreen != screen || client.player == null) {
            return;
        }

        firing = true;

        // 关键：不要在 ChatScreen 自己的方法栈里直接 setScreen。
        // 那样会在 ChatScreen.keyPressed 还没返回时就把它 removed()，
        // 轻则焦点错乱，重则在部分输入法/手柄链路里 NPE。
        // execute() 会把任务排到主线程队列，下一次 tick 开头执行，安全得多。
        client.execute(() -> {
            firing = false;
            client.setScreen(new BlockSelectScreen());
            WesClient.LOGGER.debug("[wes] 触发 //set，打开方块选择界面");
        });
    }
}
