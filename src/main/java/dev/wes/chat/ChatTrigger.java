package dev.wes.chat;

import dev.wes.command.BlockCommand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

/**
 * 触发器的唯一入口。
 *
 * <h2>Forge 分支为什么不再混入 EditBox</h2>
 * <p>Fabric 分支挂的是 {@code TextFieldWidget#onChanged} —— 那是 Yarn 名字下的
 * 私有回调，官方映射里没有稳定的对应方法（1.20.1 的 {@code EditBox} 里
 * 打字走 {@code insertText}、程序赋值走 {@code setValue}，没有一个公共的私有
 * 回调可以挂），硬猜方法名只会在运行期崩。</p>
 *
 * <p>所以这里改成<b>每帧轮询</b>：{@code ChatScreenMixin} 在
 * {@code ChatScreen#render} 的开头把输入框当前内容递进来。聊天界面只有打开时
 * 才渲染，轮询范围天然被限制在「聊天框正开着」这一种情况；一次字符串比较的
 * 开销可以忽略，而打字、粘贴、输入法上屏、历史回填全都能被覆盖。</p>
 */
public final class ChatTrigger {

    /**
     * 防重入闸门。
     *
     * <p>同一帧里 render 开头和 keyPressed 都可能命中，不加闸门会连开两次面板。
     * 用 volatile 是因为 {@link Minecraft#execute} 会在主线程任务队列里晚一拍执行。</p>
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
        // 开关关着就完全不介入：聊天框退回原版行为
        if (!ChatToggle.isEnabled()) {
            return;
        }
        BlockCommand command = find(text);
        if (firing || command == null) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        // 必须校验：可能已经有别的钩子先一步把界面换掉了
        if (client.screen != screen || client.player == null) {
            return;
        }
        if (!(screen instanceof ChatPanelHolder holder) || holder.wes$isPanelOpen()) {
            return;
        }

        firing = true;

        // 延后一 tick 再动手：此刻正处在渲染 / 按键的回调里，
        // 直接 setValue("") 会在同一个方法栈内重入输入框，光标状态容易错乱。
        client.execute(() -> {
            firing = false;
            if (client.screen == screen && screen instanceof ChatPanelHolder h) {
                h.wes$openPanel(command);
            }
        });
    }
}
