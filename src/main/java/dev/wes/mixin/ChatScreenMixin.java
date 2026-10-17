package dev.wes.mixin;

import dev.wes.chat.ChatPanelHolder;
import dev.wes.chat.ChatTrigger;
import dev.wes.gui.ChatBlockPanel;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 混入 {@link ChatScreen}：把方块选择面板挂在聊天界面上。
 *
 * <h2>为什么不用 Screen 切换</h2>
 * <p>面板是聊天界面里的浮层（{@link ChatBlockPanel}），所以这里只做四件事：
 * 渲染转发、鼠标转发、按键转发、窗口尺寸变化后重新摆位。</p>
 *
 * <h2>为什么字符输入不用管</h2>
 * <p>1.20.1 的 {@code ChatScreen} 没有重写 {@code charTyped}，而 {@code Screen}
 * 从 {@code ParentElement} 继承来的默认实现是「转发给当前焦点控件」。
 * 面板里的输入框只要用 {@code setFocused} 拿到焦点，打字就会自动进去，
 * 不需要再混入字符输入（那个方法在 ChatScreen 上根本不存在，注入了会崩）。</p>
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin implements ChatPanelHolder {

    @Shadow
    protected TextFieldWidget chatField;

    @Unique
    private ChatBlockPanel wes$panel;

    /* ============================ 面板持有 ============================ */

    @Override
    public ChatBlockPanel wes$panel() {
        if (this.wes$panel == null) {
            // textRenderer 是 Screen 的字段，不是 ChatScreen 自己的：
            // 用 @Shadow 会触发 "Cannot find target for @Shadow field"（AP 只查目标类本身），
            // 运行期拿不到映射就会崩，所以直接从客户端实例取。
            this.wes$panel = new ChatBlockPanel((ChatScreen) (Object) this,
                    MinecraftClient.getInstance().textRenderer, this.chatField);
        }
        return this.wes$panel;
    }

    @Override
    public void wes$openPanel() {
        this.wes$panel().open();
    }

    @Override
    public boolean wes$isPanelOpen() {
        return this.wes$panel != null && this.wes$panel.isOpen();
    }

    /* ============================ 注入点 ============================ */

    /** 窗口尺寸变化会重建 chatField，面板要跟着重新摆位。 */
    @Inject(method = "init", at = @At("TAIL"))
    private void wes$onInit(CallbackInfo ci) {
        if (this.wes$panel != null) {
            this.wes$panel.onResize(this.chatField);
        }
    }

    /**
     * 画在最后：此时聊天输入框和它的补全提示都已经画完了，
     * 面板铺在它们上方，而底部的输入行仍然露出来。
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void wes$onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (this.wes$panel != null && this.wes$panel.isOpen()) {
            this.wes$panel.render(context, mouseX, mouseY, delta);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void wes$onMouseClicked(double mouseX, double mouseY, int button,
                                    CallbackInfoReturnable<Boolean> cir) {
        if (this.wes$panel == null || !this.wes$panel.isOpen()) {
            return;
        }
        if (this.wes$panel.mouseClicked(mouseX, mouseY, button)) {
            cir.setReturnValue(true);
        } else if (!this.wes$panel.contains(mouseX, mouseY)) {
            // 点在面板外面：收起面板，让原版逻辑继续（比如点别处关掉聊天）
            this.wes$panel.close();
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void wes$onMouseScrolled(double mouseX, double mouseY, double amount,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (this.wes$panel != null && this.wes$panel.mouseScrolled(mouseX, mouseY, amount)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void wes$onKeyPressed(int keyCode, int scanCode, int modifiers,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (this.wes$panel != null && this.wes$panel.isOpen()) {
            if (this.wes$panel.keyPressed(keyCode, scanCode, modifiers)) {
                cir.setReturnValue(true);
            }
            return;
        }

        // 面板没开时的兜底：文本已经是 "//set " 又按了回车，别把它当命令发出去
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && ChatTrigger.TRIGGER.equals(this.chatField.getText())) {
            ChatTrigger.check((ChatScreen) (Object) this, this.chatField.getText());
            cir.setReturnValue(true);
        }
    }

    /** 按键处理完再看一眼，覆盖 Ctrl+V 粘贴等一次性写入的情况。 */
    @Inject(method = "keyPressed", at = @At("RETURN"))
    private void wes$afterKeyPressed(int keyCode, int scanCode, int modifiers,
                                     CallbackInfoReturnable<Boolean> cir) {
        ChatTrigger.check((ChatScreen) (Object) this, this.chatField.getText());
    }
}
