package dev.wes.mixin;

import dev.wes.chat.ChatTrigger;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 混入 {@link ChatScreen}，在玩家打字时嗅探输入框内容。
 *
 * <h2>为什么不能只混 keyPressed</h2>
 * <p>1.20.1 的字符输入<b>不</b>走 {@code keyPressed}：普通字符是
 * {@code Keyboard#onChar} → {@code Element#charTyped} → {@code TextFieldWidget#charTyped}
 * 这条链路，空格（0x20）也是走 charTyped 的。</p>
 * <p>而且 {@code ChatScreen} 在 1.20.1 里<b>并没有重写 charTyped</b>
 * （javap 可见它只重写了 init / keyPressed / mouseClicked / mouseScrolled / render），
 * 所以往 {@code ChatScreen#charTyped} 上注入会直接「找不到方法」而崩游戏。</p>
 * <p>结论：真正可靠的挂载点是 {@link TextFieldWidgetMixin}（监听文本变化），
 * 这个类只负责两件事：回车兜底、以及粘贴/输入法上屏后的二次确认。</p>
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {

    /** 原版聊天输入框，1.20.1 里是 {@code protected TextFieldWidget chatField}。 */
    @Shadow
    protected TextFieldWidget chatField;

    /**
     * 回车兜底。
     *
     * <p>正常流程下，玩家敲完那个空格时界面就已经被切走了，这条永远不会命中。
     * 但如果玩家用「粘贴 → 立刻回车」的极速操作，或者别的模组延迟了文本变化事件，
     * 这里可以拦住它，避免把半截 {@code //set } 当命令发给服务器。</p>
     */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void wes$guardEnterKey(int keyCode, int scanCode, int modifiers,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (keyCode != GLFW.GLFW_KEY_ENTER && keyCode != GLFW.GLFW_KEY_KP_ENTER) {
            return;
        }
        if (!ChatTrigger.TRIGGER.equals(this.chatField.getText())) {
            return;
        }
        // 先尝试开界面，再把原版回车「吃掉」
        ChatTrigger.check((ChatScreen) (Object) this, this.chatField.getText());
        cir.setReturnValue(true);
    }

    /**
     * 按键处理完之后再看一眼。
     *
     * <p>覆盖 Ctrl+V 粘贴、以及某些输入法把整段文本一次性塞进来的情况。</p>
     */
    @Inject(method = "keyPressed", at = @At("RETURN"))
    private void wes$afterKeyPressed(int keyCode, int scanCode, int modifiers,
                                     CallbackInfoReturnable<Boolean> cir) {
        ChatTrigger.check((ChatScreen) (Object) this, this.chatField.getText());
    }
}
